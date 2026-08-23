package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;



@SuppressWarnings("null")
public class GetNormalCommand {

    // Direction.values() order: DOWN(0), UP(1), NORTH(2), SOUTH(3), WEST(4), EAST(5)
    private static final Vec3[] FACE_CENTERS = {
            new Vec3(0.5, 0.0, 0.5), // DOWN
            new Vec3(0.5, 1.0, 0.5), // UP
            new Vec3(0.5, 0.5, 0.0), // NORTH
            new Vec3(0.5, 0.5, 1.0), // SOUTH
            new Vec3(0.0, 0.5, 0.5), // WEST
            new Vec3(1.0, 0.5, 0.5), // EAST
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("getnormal")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .then(Commands.argument("axis", StringArgumentType.word())
                                .then(Commands.argument("exposed_only", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                            String axis = StringArgumentType.getString(ctx, "axis").toLowerCase();
                                            boolean exposedOnly = BoolArgumentType.getBool(ctx, "exposed_only");

                                            if (!axis.equals("pitch") && !axis.equals("yaw")) {
                                                ctx.getSource().sendFailure(
                                                        Component.literal("Axis must be 'pitch' or 'yaw'")
                                                );
                                                return 0;
                                            }

                                            ServerLevel world = ctx.getSource().getLevel();

                                            // Find nearest non-air block within radius
                                            BlockPos nearestBlock = null;
                                            double nearestBlockDist = Double.MAX_VALUE;

                                            int radius = 2;
                                            BlockPos center = BlockPos.containing(pos);
                                            for (int dx = -radius; dx <= radius; dx++) {
                                                for (int dy = -radius; dy <= radius; dy++) {
                                                    for (int dz = -radius; dz <= radius; dz++) {
                                                        BlockPos candidate = center.offset(dx, dy, dz);
                                                        if (!world.getBlockState(candidate).isAir()) {
                                                            double dist = pos.distanceToSqr(
                                                                    candidate.getX() + 0.5,
                                                                    candidate.getY() + 0.5,
                                                                    candidate.getZ() + 0.5
                                                            );
                                                            if (dist < nearestBlockDist) {
                                                                nearestBlockDist = dist;
                                                                nearestBlock = candidate;
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            if (nearestBlock == null) {
                                                ctx.getSource().sendFailure(
                                                        Component.literal("No solid block found near position")
                                                );
                                                return 0;
                                            }

                                            Direction bestFace = findBestFace(world, pos, nearestBlock, exposedOnly);

                                            Vec3 normal = new Vec3(
                                                    bestFace.getStepX(),
                                                    bestFace.getStepY(),
                                                    bestFace.getStepZ()
                                            );

                                            int result = axisAngle(normal, axis, 1);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal(String.valueOf(result)), false
                                            );
                                            return result;
                                        })
                                )
                        )
                )
        );
    }

    private static Direction findBestFace(ServerLevel world, Vec3 pos, BlockPos block, boolean exposedOnly) {
        Direction[] directions = Direction.values();

        Direction bestFace = null;
        double bestDist = Double.MAX_VALUE;

        // First pass: respect exposedOnly
        for (int i = 0; i < directions.length; i++) {
            Direction dir = directions[i];

            if (exposedOnly && isFaceCovered(world, block, dir)) {
                continue;
            }

            Vec3 faceCenter = new Vec3(
                    block.getX() + FACE_CENTERS[i].x,
                    block.getY() + FACE_CENTERS[i].y,
                    block.getZ() + FACE_CENTERS[i].z
            );
            double dist = pos.distanceToSqr(faceCenter);
            if (dist < bestDist) {
                bestDist = dist;
                bestFace = dir;
            }
        }

        // Fallback: all faces were covered, retry ignoring exposure
        if (bestFace == null) {
            for (int i = 0; i < directions.length; i++) {
                Vec3 faceCenter = new Vec3(
                        block.getX() + FACE_CENTERS[i].x,
                        block.getY() + FACE_CENTERS[i].y,
                        block.getZ() + FACE_CENTERS[i].z
                );
                double dist = pos.distanceToSqr(faceCenter);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestFace = directions[i];
                }
            }
        }

        return bestFace;
    }

    /**
     * A face is considered covered if the adjacent block in that direction
     * has a collidable collision shape that is non-empty.
     */
    private static boolean isFaceCovered(ServerLevel world, BlockPos block, Direction face) {
        BlockPos neighbor = block.relative(face);
        VoxelShape collisionShape = world.getBlockState(neighbor).getCollisionShape(world, neighbor);
        return !collisionShape.isEmpty();
    }

    public static int axisAngle(Vec3 normal, String axis, int multiplier) {
        if (axis.equals("pitch")) {
            double pitch = Math.toDegrees(Math.asin(
                    Math.max(-1.0, Math.min(1.0, normal.y))
            ));
            return (int) Math.round(-pitch * multiplier);
        } else {
            double yaw = Math.toDegrees(Math.atan2(-normal.x, normal.z));
            return (int) Math.round(yaw * multiplier);
        }
    }
}