package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;


@SuppressWarnings("null")
public class EntitifyCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("entitify")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("entity_type", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.asList("falling_block", "block_display"/*, "item_display"*/), builder))
                                .executes(ctx -> execute(ctx, "keep", null))
                                .then(Commands.argument("keep_remove", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                Arrays.asList("keep", "remove"), builder))
                                        .executes(ctx -> execute(ctx,
                                                StringArgumentType.getString(ctx, "keep_remove"), null))
                                        .then(Commands.argument("tag", StringArgumentType.greedyString())
                                                .executes(ctx -> execute(ctx,
                                                        StringArgumentType.getString(ctx, "keep_remove"),
                                                        StringArgumentType.getString(ctx, "tag"))))))));
    }

    private static int execute(CommandContext<CommandSourceStack> context, String keepRemove, String tag)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Level world = source.getLevel();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        BlockState blockState = world.getBlockState(pos);
        if (blockState.isAir()) {
            source.sendFailure(Component.literal("No block found."));
            return 0;
        }

        Vec3 spawnPos = Vec3.atCenterOf(pos);
        String entityType = StringArgumentType.getString(context, "entity_type");
        String tagToUse = (tag == null || tag.isEmpty()) ? "BUC.entitified." + entityType : tag;
        //String blockId = Registries.BLOCK.getId(blockState.getBlock()).toString();
        CompoundTag stateTag = NbtUtils.writeBlockState(blockState);
        double x = spawnPos.x, y = spawnPos.y, z = spawnPos.z;

        if (entityType.equals("falling_block")) {
            String nbt = String.format(
                    "{BlockState:%s,Time:1b,NoGravity:1b,DropItem:0b,Tags:[\"%s\"]}",
                    stateTag, tagToUse
            );
            source.getServer()
                    .getCommands()
                    .getDispatcher()
                    .execute(String.format("summon falling_block %f %f %f %s", x, y, z, nbt), source.withSuppressedOutput());
        } else if (entityType.equals("block_display")) {
            String nbt = String.format(
                    "{block_state:%s,Tags:[\"%s\"]}",
                    stateTag, tagToUse
            );
            source.getServer()
                    .getCommands()
                    .getDispatcher()
                    .execute(String.format("summon block_display %f %f %f %s", x, y, z, nbt), source.withSuppressedOutput());
        }
        /*
        // Commented out item_display for now. Re-enable if needed later:
        else if (entityType.equals("item_display")) {
            String itemId = Registries.ITEM.getId(blockState.getBlock().asItem()).toString();
            String nbt = String.format(
                "{Item:{id:\"%s\",Count:1b},Tags:[\"%s\"]}",
                itemId, tagToUse
            );
            source.getServer()
                  .getCommands()
                  .getDispatcher()
                  .execute(String.format("summon item_display %f %f %f %s", x, y, z, nbt), source.withSuppressedOutput());
        }
        */
        else {
            source.sendFailure(Component.literal("Invalid type. Use falling_block or block_display."));
            return 0;
        }

        if ("remove".equalsIgnoreCase(keepRemove)) {
            world.removeBlock(pos, false);
        }

        source.sendSuccess(() -> Component.literal("Entitified as " + entityType + " with tag '" + tagToUse + "'."), true);
        return 1;
    }
}