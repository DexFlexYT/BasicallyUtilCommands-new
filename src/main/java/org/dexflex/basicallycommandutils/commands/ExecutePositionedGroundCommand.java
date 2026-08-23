package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockPredicateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;


@SuppressWarnings("null")

public class ExecutePositionedGroundCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess) {
        CommandNode<CommandSourceStack> executeNode =
                dispatcher.getRoot().getChild("execute");
        if (executeNode == null) return;

        CommandNode<CommandSourceStack> positionedNode =
                executeNode.getChild("positioned");
        if (positionedNode == null) return;

        // Create "over" node
        CommandNode<CommandSourceStack> overNode =
                positionedNode.getChild("over");
        if (overNode == null) return;

        // /execute positioned over closest_ground <max_range>
        overNode.addChild(
                literal("closest_ground")
                        .then(argument("max_range", IntegerArgumentType.integer(1))
                                .redirect(executeNode, ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    Vec3 pos = src.getPosition();
                                    int maxRange = IntegerArgumentType.getInteger(ctx, "max_range");
                                    Double groundY = findClosestGround(src.getLevel(), pos, true, maxRange);
                                    if (groundY == null) return src;
                                    return src.withPosition(new Vec3(pos.x, groundY, pos.z));
                                })
                        )
                        .build()
        );

        // /execute positioned over closest_ground_down <max_range>
        overNode.addChild(
                literal("closest_ground_down")
                        .then(argument("max_range", IntegerArgumentType.integer(1))
                                .redirect(executeNode, ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    Vec3 pos = src.getPosition();
                                    int maxRange = IntegerArgumentType.getInteger(ctx, "max_range");
                                    Double groundY = findClosestGround(src.getLevel(), pos, false, maxRange);
                                    if (groundY == null) return src;
                                    return src.withPosition(new Vec3(pos.x, groundY, pos.z));
                                })
                        )
                        .build()
        );

        // /execute positioned over closest_view <range>
        overNode.addChild(
                literal("closest_view")
                        // Path 1: closest_view <range> (no filter - all blocks)
                        .then(argument("range", FloatArgumentType.floatArg())
                                .redirect(executeNode, ctx -> {
                                    CommandSourceStack src = ctx.getSource();
                                    float range = FloatArgumentType.getFloat(ctx, "range");
                                    Predicate<BlockInWorld> filter = (pos) -> true;
                                    Vec3 hitPos = findClosestViewCollision(src.getLevel(), src.getPosition(), src.getRotation(), filter, range);
                                    return src.withPosition(hitPos);
                                })
                        )
                        // Path 2: closest_view * <range> (wildcard - all blocks)
                        .then(literal("*")
                                .then(argument("range", FloatArgumentType.floatArg())
                                        .redirect(executeNode, ctx -> {
                                            CommandSourceStack src = ctx.getSource();
                                            float range = FloatArgumentType.getFloat(ctx, "range");
                                            Predicate<BlockInWorld> filter = (pos) -> true;
                                            Vec3 hitPos = findClosestViewCollision(src.getLevel(), src.getPosition(), src.getRotation(), filter, range);
                                            return src.withPosition(hitPos);
                                        })
                                )
                        )
                        // Path 3: closest_view <block_filter> <range> (with filter)
                        .then(argument("block_filter", BlockPredicateArgument.blockPredicate(registryAccess))
                                .then(argument("range", FloatArgumentType.floatArg())
                                        .redirect(executeNode, ctx -> {
                                            CommandSourceStack src = ctx.getSource();
                                            Predicate<BlockInWorld> filter = BlockPredicateArgument.getBlockPredicate(ctx, "block_filter");
                                            float range = FloatArgumentType.getFloat(ctx, "range");
                                            Vec3 hitPos = findClosestViewCollision(src.getLevel(), src.getPosition(), src.getRotation(), filter, range);
                                            return src.withPosition(hitPos);
                                        })
                                )
                        )
                        .build()
        );

        // /execute positioned over closest_view_entity <target> <range> <align>
        overNode.addChild(
                literal("closest_view_entity")
                        .then(argument("target", EntityArgument.entities())
                                .then(argument("range", FloatArgumentType.floatArg())
                                        .then(argument("align", BoolArgumentType.bool())
                                                .redirect(executeNode, ctx -> {
                                                    CommandSourceStack src = ctx.getSource();
                                                    Collection<? extends Entity> targets = EntityArgument.getEntities(ctx, "target");
                                                    float range = FloatArgumentType.getFloat(ctx, "range");
                                                    boolean align = BoolArgumentType.getBool(ctx, "align");

                                                    Vec3 hitPos = findClosestViewEntity(src.getLevel(), src.getPosition(), src.getRotation(), targets, range, align);
                                                    return src.withPosition(hitPos);
                                                })
                                        )
                                )
                        )
                        .build()
        );
    }

    private static Double findClosestGround(ServerLevel world, Vec3 from, boolean bothWays, int maxScan) {
        int startX = (int) Math.floor(from.x);
        int startZ = (int) Math.floor(from.z);
        int startY = (int) Math.floor(from.y);

        // Scan downward
        Double downY = null;
        for (int dy = 0; dy <= maxScan; dy++) {
            int scanY = startY - dy;
            if (scanY < world.getMinY()) break;
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
            if (scanY >= world.getMaxY()) break;
            BlockPos pos = new BlockPos(startX, scanY, startZ);
            if (isMotionBlocking(world, pos) && !isMotionBlocking(world, pos.above())) {
                upY = (double)(scanY + 1);
                break;
            }
        }

        if (downY == null) return upY;
        if (upY   == null) return downY;
        return Math.abs(upY - from.y) < Math.abs(from.y - downY) ? upY : downY;
    }

    private static boolean isMotionBlocking(ServerLevel world, BlockPos pos) {
        return !world.getBlockState(pos)
                .getCollisionShape(world, pos)
                .isEmpty();
    }

    private static Vec3 findClosestViewCollision(ServerLevel world, Vec3 origin, Vec2 rotation,
                                                  Predicate<BlockInWorld> filter, float range) {
        // Extract pitch and yaw from rotation
        float pitch = rotation.x;
        float yaw = rotation.y;

        // Convert rotation to direction vector
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        double xDir = -Math.sin(yawRad) * Math.cos(pitchRad);
        double yDir = -Math.sin(pitchRad);
        double zDir = Math.cos(yawRad) * Math.cos(pitchRad);

        Vec3 direction = new Vec3(xDir, yDir, zDir).normalize();

        // Raycast with small steps to check for collisions
        float stepSize = 0.05f;
        double closestDistance = Double.MAX_VALUE;
        Vec3 closestHitPos = null;

        for (double distance = 0; distance <= range; distance += stepSize) {
            Vec3 checkPos = origin.add(direction.scale(distance));
            BlockPos blockPos = BlockPos.containing(checkPos);
            BlockState state = world.getBlockState(blockPos);

            if (!state.isAir()) {
                BlockInWorld cachedPos = new BlockInWorld(world, blockPos, true);
                if (filter.test(cachedPos)) {
                    VoxelShape shape = state.getCollisionShape(world, blockPos);
                    if (!shape.isEmpty()) {
                        // Get the bounding box of the collision shape
                        AABB shapeBox = shape.bounds().move(blockPos.getX(), blockPos.getY(), blockPos.getZ());

                        // Check if ray intersects this shape
                        Vec3 intersectionPoint = getRayBoxIntersection(origin, direction, shapeBox, range);
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

        return origin.add(direction.scale(range));
    }

    private static Vec3 getRayBoxIntersection(Vec3 origin, Vec3 direction, AABB box, float maxDist) {
        double closestT = Double.MAX_VALUE;

        // Check X planes
        if (Math.abs(direction.x) > 1e-6) {
            // Min X plane
            double t = (box.minX - origin.x) / direction.x;
            if (t > 0 && t <= maxDist) {
                Vec3 hit = origin.add(direction.scale(t));
                if (hit.y >= box.minY && hit.y <= box.maxY && hit.z >= box.minZ && hit.z <= box.maxZ) {
                    closestT = Math.min(closestT, t);
                }
            }
            // Max X plane
            t = (box.maxX - origin.x) / direction.x;
            if (t > 0 && t <= maxDist) {
                Vec3 hit = origin.add(direction.scale(t));
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
                Vec3 hit = origin.add(direction.scale(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.z >= box.minZ && hit.z <= box.maxZ) {
                    closestT = Math.min(closestT, t);
                }
            }
            // Max Y plane
            t = (box.maxY - origin.y) / direction.y;
            if (t > 0 && t <= maxDist) {
                Vec3 hit = origin.add(direction.scale(t));
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
                Vec3 hit = origin.add(direction.scale(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.y >= box.minY && hit.y <= box.maxY) {
                    closestT = Math.min(closestT, t);
                }
            }
            // Max Z plane
            t = (box.maxZ - origin.z) / direction.z;
            if (t > 0 && t <= maxDist) {
                Vec3 hit = origin.add(direction.scale(t));
                if (hit.x >= box.minX && hit.x <= box.maxX && hit.y >= box.minY && hit.y <= box.maxY) {
                    closestT = Math.min(closestT, t);
                }
            }
        }

        // Return the intersection point or null if no intersection
        if (closestT != Double.MAX_VALUE) {
            return origin.add(direction.scale(closestT));
        }

        return null;
    }

    private static Vec3 findClosestViewEntity(ServerLevel world, Vec3 origin, Vec2 rotation,
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

        Vec3 direction = new Vec3(xDir, yDir, zDir).normalize();

        double closestDistance = Double.MAX_VALUE;
        Vec3 closestHitPos = null;
        Entity closestEntity = null;

        // Check each target entity
        for (Entity entity : targets) {
            AABB entityBox = entity.getBoundingBox();

            // Check if ray intersects this entity's bounding box
            Vec3 intersectionPoint = getRayBoxIntersection(origin, direction, entityBox, range);
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
                return closestEntity.position();
            } else {
                // Return the intersection point
                return closestHitPos;
            }
        }

        // No entity found, return end of range
        return origin.add(direction.scale(range));
    }
}