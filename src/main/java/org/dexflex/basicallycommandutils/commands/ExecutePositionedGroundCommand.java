package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.block.BlockState;
import net.minecraft.block.pattern.CachedBlockPosition;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.BlockPredicateArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.Collection;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.literal;

public class ExecutePositionedGroundCommand {


    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        CommandNode<ServerCommandSource> executeNode =
                dispatcher.getRoot().getChild("execute");
        if (executeNode == null) return;

        CommandNode<ServerCommandSource> positionedNode =
                executeNode.getChild("positioned");
        if (positionedNode == null) return;

        // Create "over" node
        CommandNode<ServerCommandSource> overNode =
                positionedNode.getChild("over");
        if (overNode == null) return;

        // /execute positioned over closest_ground <max_range>
        overNode.addChild(
                literal("closest_ground")
                        .then(CommandManager.argument("max_range", IntegerArgumentType.integer(1))
                                .redirect(executeNode, ctx -> {
                                    ServerCommandSource src = ctx.getSource();
                                    Vec3d pos = src.getPosition();
                                    int maxRange = IntegerArgumentType.getInteger(ctx, "max_range");
                                    Double groundY = findClosestGround(src.getWorld(), pos, true, maxRange);
                                    if (groundY == null) return src;
                                    return src.withPosition(new Vec3d(pos.x, groundY, pos.z));
                                })
                        )
                        .build()
        );

        // /execute positioned over closest_ground_down <max_range>
        overNode.addChild(
                literal("closest_ground_down")
                        .then(CommandManager.argument("max_range", IntegerArgumentType.integer(1))
                                .redirect(executeNode, ctx -> {
                                    ServerCommandSource src = ctx.getSource();
                                    Vec3d pos = src.getPosition();
                                    int maxRange = IntegerArgumentType.getInteger(ctx, "max_range");
                                    Double groundY = findClosestGround(src.getWorld(), pos, false, maxRange);
                                    if (groundY == null) return src;
                                    return src.withPosition(new Vec3d(pos.x, groundY, pos.z));
                                })
                        )
                        .build()
        );

        // /execute positioned over closest_view <range>
        overNode.addChild(
                literal("closest_view")
                        // Path 1: closest_view <range> (no filter - all blocks)
                        .then(CommandManager.argument("range", FloatArgumentType.floatArg())
                                .redirect(executeNode, ctx -> {
                                    ServerCommandSource src = ctx.getSource();
                                    float range = FloatArgumentType.getFloat(ctx, "range");
                                    Predicate<CachedBlockPosition> filter = (pos) -> true;
                                    Vec3d hitPos = findClosestViewCollision(src.getWorld(), src.getPosition(), src.getRotation(), filter, range);
                                    return src.withPosition(hitPos);
                                })
                        )
                        // Path 2: closest_view * <range> (wildcard - all blocks)
                        .then(literal("*")
                                .then(CommandManager.argument("range", FloatArgumentType.floatArg())
                                        .redirect(executeNode, ctx -> {
                                            ServerCommandSource src = ctx.getSource();
                                            float range = FloatArgumentType.getFloat(ctx, "range");
                                            Predicate<CachedBlockPosition> filter = (pos) -> true;
                                            Vec3d hitPos = findClosestViewCollision(src.getWorld(), src.getPosition(), src.getRotation(), filter, range);
                                            return src.withPosition(hitPos);
                                        })
                                )
                        )
                        // Path 3: closest_view <block_filter> <range> (with filter)
                        .then(CommandManager.argument("block_filter", BlockPredicateArgumentType.blockPredicate(registryAccess))
                                .then(CommandManager.argument("range", FloatArgumentType.floatArg())
                                        .redirect(executeNode, ctx -> {
                                            ServerCommandSource src = ctx.getSource();
                                            Predicate<CachedBlockPosition> filter = BlockPredicateArgumentType.getBlockPredicate(ctx, "block_filter");
                                            float range = FloatArgumentType.getFloat(ctx, "range");
                                            Vec3d hitPos = findClosestViewCollision(src.getWorld(), src.getPosition(), src.getRotation(), filter, range);
                                            return src.withPosition(hitPos);
                                        })
                                )
                        )
                        .build()
        );

        // /execute positioned over closest_view_entity <target> <range> <align>
        overNode.addChild(
                literal("closest_view_entity")
                        .then(CommandManager.argument("target", EntityArgumentType.entities())
                                .then(CommandManager.argument("range", FloatArgumentType.floatArg())
                                        .then(CommandManager.argument("align", BoolArgumentType.bool())
                                                .redirect(executeNode, ctx -> {
                                                    ServerCommandSource src = ctx.getSource();
                                                    Collection<? extends Entity> targets = EntityArgumentType.getEntities(ctx, "target");
                                                    float range = FloatArgumentType.getFloat(ctx, "range");
                                                    boolean align = BoolArgumentType.getBool(ctx, "align");

                                                    Vec3d hitPos = findClosestViewEntity(src.getWorld(), src.getPosition(), src.getRotation(), targets, range, align);
                                                    return src.withPosition(hitPos);
                                                })
                                        )
                                )
                        )
                        .build()
        );
    }

    private static Double findClosestGround(ServerWorld world, Vec3d from, boolean bothWays, int maxScan) {
        int startX = (int) Math.floor(from.x);
        int startZ = (int) Math.floor(from.z);
        int startY = (int) Math.floor(from.y);

        // Scan downward
        Double downY = null;
        for (int dy = 0; dy <= maxScan; dy++) {
            int scanY = startY - dy;
            if (scanY < world.getBottomY()) break;
            if (isMotionBlocking(world, new BlockPos(startX, scanY, startZ))) {
                downY = (double)(scanY + 1);
                break;
            }
        }

        if (!bothWays) return downY;

        // Scan upward — only land on exposed surfaces (block above must be clear)
        Double upY = null;
        for (int dy = 1; dy <= maxScan; dy++) {
            int scanY = startY + dy;
            if (scanY >= world.getTopY()) break;
            BlockPos pos = new BlockPos(startX, scanY, startZ);
            if (isMotionBlocking(world, pos) && !isMotionBlocking(world, pos.up())) {
                upY = (double)(scanY + 1);
                break;
            }
        }

        if (downY == null) return upY;
        if (upY   == null) return downY;
        return Math.abs(upY - from.y) < Math.abs(from.y - downY) ? upY : downY;
    }

    private static boolean isMotionBlocking(ServerWorld world, BlockPos pos) {
        return !world.getBlockState(pos)
                .getCollisionShape(world, pos)
                .isEmpty();
    }

    private static Vec3d findClosestViewCollision(ServerWorld world, Vec3d origin, Vec2f rotation,
                                                  Predicate<CachedBlockPosition> filter, float range) {
        // Extract pitch and yaw from rotation
        float pitch = rotation.x;
        float yaw = rotation.y;

        // Convert rotation to direction vector
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        double xDir = -Math.sin(yawRad) * Math.cos(pitchRad);
        double yDir = -Math.sin(pitchRad);
        double zDir = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3d direction = new Vec3d(xDir, yDir, zDir).normalize();

        // Raycast with small steps to check for collisions
        float stepSize = 0.05f;
        double closestDistance = Double.MAX_VALUE;
        Vec3d closestHitPos = null;

        for (double distance = 0; distance <= range; distance += stepSize) {
            Vec3d checkPos = origin.add(direction.multiply(distance));
            BlockPos blockPos = BlockPos.ofFloored(checkPos);
            BlockState state = world.getBlockState(blockPos);

            if (!state.isAir()) {
                CachedBlockPosition cachedPos = new CachedBlockPosition(world, blockPos, true);
                if (filter.test(cachedPos)) {
                    VoxelShape shape = state.getCollisionShape(world, blockPos);
                    if (!shape.isEmpty()) {
                        // Get the bounding box of the collision shape
                        Box shapeBox = shape.getBoundingBox().offset(blockPos.getX(), blockPos.getY(), blockPos.getZ());

                        // Check if ray intersects this shape
                        Vec3d intersectionPoint = getRayBoxIntersection(origin, direction, shapeBox, range);
                        if (intersectionPoint != null) {
                            double hitDistance = origin.distanceTo(intersectionPoint);
                            if (hitDistance < closestDistance) {
                                closestDistance = hitDistance;
                                closestHitPos = intersectionPoint;
                            }
                        }
                    }
                }
            }
        }

        // If collision found, return it; otherwise return end of range
        if (closestHitPos != null) {
            return closestHitPos;
        }

        return origin.add(direction.multiply(range));
    }

    private static Vec3d getRayBoxIntersection(Vec3d origin, Vec3d direction, Box box, float maxDist) {
        double closestT = Double.MAX_VALUE;

        // Check X planes
        if (Math.abs(direction.x) > 1e-6) {
            // Min X plane
            double t = (box.minX - origin.x) / direction.x;
            if (t > 0 && t <= maxDist) {
                Vec3d hit = origin.add(direction.multiply(t));
                if (hit.y >= box.minY && hit.y <= box.maxY && hit.z >= box.minZ && hit.z <= box.maxZ) {
                    closestT = Math.min(closestT, t);
                }
            }
            // Max X plane
            t = (box.maxX - origin.x) / direction.x;
            if (t > 0 && t <= maxDist) {
                Vec3d hit = origin.add(direction.multiply(t));
                if (hit.y >= box.minY && hit.y <= box.maxY && hit.z >= box.minZ && hit.z <= box.maxZ) {
                    closestT = Math.min(closestT, t);
                }
            }
        }

        // Check Y planes
        if (Math.abs(direction.y) > 1e-6) {
            // Min Y plane
            double t = (box.minY - origin.y) / direction.y;
            if (t > 0 && t <= maxDist) {
                Vec3d hit = origin.add(direction.multiply(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.z >= box.minZ && hit.z <= box.maxZ) {
                    closestT = Math.min(closestT, t);
                }
            }
            // Max Y plane
            t = (box.maxY - origin.y) / direction.y;
            if (t > 0 && t <= maxDist) {
                Vec3d hit = origin.add(direction.multiply(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.z >= box.minZ && hit.z <= box.maxZ) {
                    closestT = Math.min(closestT, t);
                }
            }
        }

        // Check Z planes
        if (Math.abs(direction.z) > 1e-6) {
            // Min Z plane
            double t = (box.minZ - origin.z) / direction.z;
            if (t > 0 && t <= maxDist) {
                Vec3d hit = origin.add(direction.multiply(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.y >= box.minY && hit.y <= box.maxY) {
                    closestT = Math.min(closestT, t);
                }
            }
            // Max Z plane
            t = (box.maxZ - origin.z) / direction.z;
            if (t > 0 && t <= maxDist) {
                Vec3d hit = origin.add(direction.multiply(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.y >= box.minY && hit.y <= box.maxY) {
                    closestT = Math.min(closestT, t);
                }
            }
        }

        // Return the intersection point or null if no intersection
        if (closestT != Double.MAX_VALUE) {
            return origin.add(direction.multiply(closestT));
        }

        return null;
    }

    private static Vec3d findClosestViewEntity(ServerWorld world, Vec3d origin, Vec2f rotation,
                                               Collection<? extends Entity> targets, float range, boolean align) {
        // Extract pitch and yaw from rotation
        float pitch = rotation.x;
        float yaw = rotation.y;

        // Convert rotation to direction vector
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        double xDir = -Math.sin(yawRad) * Math.cos(pitchRad);
        double yDir = -Math.sin(pitchRad);
        double zDir = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3d direction = new Vec3d(xDir, yDir, zDir).normalize();

        double closestDistance = Double.MAX_VALUE;
        Vec3d closestHitPos = null;
        Entity closestEntity = null;

        // Check each target entity
        for (Entity entity : targets) {
            Box entityBox = entity.getBoundingBox();

            // Check if ray intersects this entity's bounding box
            Vec3d intersectionPoint = getRayBoxIntersection(origin, direction, entityBox, range);
            if (intersectionPoint != null) {
                double hitDistance = origin.distanceTo(intersectionPoint);
                if (hitDistance < closestDistance) {
                    closestDistance = hitDistance;
                    closestHitPos = intersectionPoint;
                    closestEntity = entity;
                }
            }
        }

        // If entity collision found
        if (closestEntity != null) {
            if (align) {
                // Return the entity's exact position
                return closestEntity.getPos();
            } else {
                // Return the intersection point
                return closestHitPos;
            }
        }

        // No entity found, return end of range
        return origin.add(direction.multiply(range));
    }
}