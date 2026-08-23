package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collection;
import java.util.function.Predicate;

@SuppressWarnings("null")
public class CheckCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        dispatcher.register(Commands.literal("check")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("blocks")
                        .then(Commands.argument("distance", IntegerArgumentType.integer(1))
                                .then(Commands.argument("block", BlockStateArgument.block(registryAccess))
                                        .executes(CheckCommand::executeBlocks)
                                )
                        )
                )
                .then(Commands.literal("intersection")
                        .then(Commands.argument("selector", EntityArgument.entities())
                                .executes(CheckCommand::executeIntersection)
                        )
                )

                .then(Commands.literal("collision")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> executeCollision(ctx, null))
                                .then(Commands.argument("block_filter", BlockPredicateArgument.blockPredicate(registryAccess))
                                        .executes(ctx -> {
                                            var filter = BlockPredicateArgument.getBlockPredicate(ctx, "block_filter");
                                            return executeCollision(ctx, filter);
                                        })
                                )
                        )
                )
        );
    }

    private static int executeBlocks(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        int distance = IntegerArgumentType.getInteger(ctx, "distance");
        BlockInput blockArg = BlockStateArgument.getBlock(ctx, "block");

        int count = 0;
        for (int dx = -distance; dx <= distance; dx++) {
            for (int dy = -distance; dy <= distance; dy++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (blockArg.test(new BlockInWorld(world, pos, true))) {
                        count++;
                    }
                }
            }
        }

        final int found = count;
        final int dist = distance;
        source.sendSuccess(
                () -> Component.literal("Found " + found + " matching blocks within " + dist + " blocks."),
                false
        );
        return found;
    }

    private static int executeIntersection(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Vec3 pos = source.getPosition();
        double x = pos.x, y = pos.y, z = pos.z;

        Collection<? extends Entity> ents = EntityArgument.getEntities(ctx, "selector");

        int hits = 0;
        for (Entity e : ents) {
            AABB box = e.getBoundingBox();
            if (box.contains(x, y, z)) {
                hits++;
            }
        }

        final int result = hits;
        source.sendSuccess(() ->
                        Component.literal(String.format("Found %d intersections.", result)),
                false
        );
        return result;
    }

    private static int executeCollision(CommandContext<CommandSourceStack> ctx,
                                        Predicate<BlockInWorld> filter)
            throws CommandSyntaxException {

        CommandSourceStack source = ctx.getSource();
        ServerLevel world = source.getLevel();
        Entity target = EntityArgument.getEntity(ctx, "target");
        // offset bounding box, by deez nuts
        AABB originalBox = target.getBoundingBox();
        Vec3 entityPos = target.position();
        Vec3 contextPos = source.getPosition();
        Vec3 offset = contextPos.subtract(entityPos);
        AABB entityBox = originalBox.move(offset);
        int minX = Mth.floor(entityBox.minX - 1);
        int minY = Mth.floor(entityBox.minY - 1);
        int minZ = Mth.floor(entityBox.minZ - 1);
        int maxX = Mth.floor(entityBox.maxX + 1);
        int maxY = Mth.floor(entityBox.maxY + 1);
        int maxZ = Mth.floor(entityBox.maxZ + 1);

        int hits = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);

                    if (state.isAir()) continue;

                    if (filter != null && !filter.test(new BlockInWorld(world, pos, true))) continue;

                    VoxelShape shape = state.getCollisionShape(world, pos);
                    if (shape.isEmpty()) continue;

                    if (Shapes.joinIsNotEmpty(
                            shape.move(pos.getX(), pos.getY(), pos.getZ()),
                            Shapes.create(entityBox),
                            BooleanOp.AND)) {
                        hits++;
                    }
                }
            }
        }
        final int result = hits;
        source.sendSuccess(
                () -> Component.literal(result > 0
                        ? "Found " + result + " block collisions."
                        : "Not found any block collisions."),
                false
        );
        return result;
    }
}