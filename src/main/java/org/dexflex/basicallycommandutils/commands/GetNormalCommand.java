package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

public class GetNormalCommand {

    // Direction.values() order: DOWN(0), UP(1), NORTH(2), SOUTH(3), WEST(4), EAST(5)
    private static final Vec3d[] FACE_CENTERS = {
            new Vec3d(0.5, 0.0, 0.5), // DOWN
            new Vec3d(0.5, 1.0, 0.5), // UP
            new Vec3d(0.5, 0.5, 0.0), // NORTH
            new Vec3d(0.5, 0.5, 1.0), // SOUTH
            new Vec3d(0.0, 0.5, 0.5), // WEST
            new Vec3d(1.0, 0.5, 0.5), // EAST
    };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("getnormal")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
                        .then(CommandManager.argument("axis", StringArgumentType.word())
                                .then(CommandManager.argument("exposed_only", BoolArgumentType.bool())
                                        .executes(ctx -> {
                                            Vec3d pos = Vec3ArgumentType.getVec3(ctx, "pos");
                                            String axis = StringArgumentType.getString(ctx, "axis").toLowerCase();
                                            boolean exposedOnly = BoolArgumentType.getBool(ctx, "exposed_only");

                                            if (!axis.equals("pitch") && !axis.equals("yaw")) {
                                                ctx.getSource().sendError(
                                                        net.minecraft.text.Text.literal("Axis must be 'pitch' or 'yaw'")
                                                );
                                                return 0;
                                            }

                                            ServerWorld world = ctx.getSource().getWorld();

                                            // Find nearest non-air block within radius
                                            BlockPos nearestBlock = null;
                                            double nearestBlockDist = Double.MAX_VALUE;

                                            int radius = 2;
                                            BlockPos center = BlockPos.ofFloored(pos);
                                            for (int dx = -radius; dx <= radius; dx++) {
                                                for (int dy = -radius; dy <= radius; dy++) {
                                                    for (int dz = -radius; dz <= radius; dz++) {
                                                        BlockPos candidate = center.add(dx, dy, dz);
                                                        if (!world.getBlockState(candidate).isAir()) {
                                                            double dist = pos.squaredDistanceTo(
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
                                                ctx.getSource().sendError(
                                                        net.minecraft.text.Text.literal("No solid block found near position")
                                                );
                                                return 0;
                                            }

                                            Direction bestFace = findBestFace(world, pos, nearestBlock, exposedOnly);

                                            Vec3d normal = new Vec3d(
                                                    bestFace.getOffsetX(),
                                                    bestFace.getOffsetY(),
                                                    bestFace.getOffsetZ()
                                            );

                                            int result = axisAngle(normal, axis, 1);
                                            ctx.getSource().sendFeedback(
                                                    () -> net.minecraft.text.Text.literal(String.valueOf(result)), false
                                            );
                                            return result;
                                        })
                                )
                        )
                )
        );
    }

    private static Direction findBestFace(ServerWorld world, Vec3d pos, BlockPos block, boolean exposedOnly) {
        Direction[] directions = Direction.values();

        Direction bestFace = null;
        double bestDist = Double.MAX_VALUE;

        // First pass: respect exposedOnly
        for (int i = 0; i < directions.length; i++) {
            Direction dir = directions[i];

            if (exposedOnly && isFaceCovered(world, block, dir)) {
                continue;
            }

            Vec3d faceCenter = new Vec3d(
                    block.getX() + FACE_CENTERS[i].x,
                    block.getY() + FACE_CENTERS[i].y,
                    block.getZ() + FACE_CENTERS[i].z
            );
            double dist = pos.squaredDistanceTo(faceCenter);
            if (dist < bestDist) {
                bestDist = dist;
                bestFace = dir;
            }
        }

        // Fallback: all faces were covered, retry ignoring exposure
        if (bestFace == null) {
            for (int i = 0; i < directions.length; i++) {
                Vec3d faceCenter = new Vec3d(
                        block.getX() + FACE_CENTERS[i].x,
                        block.getY() + FACE_CENTERS[i].y,
                        block.getZ() + FACE_CENTERS[i].z
                );
                double dist = pos.squaredDistanceTo(faceCenter);
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
    private static boolean isFaceCovered(ServerWorld world, BlockPos block, Direction face) {
        BlockPos neighbor = block.offset(face);
        VoxelShape collisionShape = world.getBlockState(neighbor).getCollisionShape(world, neighbor);
        return !collisionShape.isEmpty();
    }

    public static int axisAngle(Vec3d normal, String axis, int multiplier) {
        if (axis.equals("yaw")) {
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