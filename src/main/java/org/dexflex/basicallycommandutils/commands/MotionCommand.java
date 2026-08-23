package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.RotationArgumentType;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import java.util.Collection;

public class MotionCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("motion")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .then(CommandManager.argument("strength", FloatArgumentType.floatArg())
                                        .executes(ctx -> execute(ctx, false, null, null, null, null))
                                        .then(CommandManager.literal("with")
                                                .then(CommandManager.argument("vx", DoubleArgumentType.doubleArg())
                                                        .then(CommandManager.argument("vy", DoubleArgumentType.doubleArg())
                                                                .then(CommandManager.argument("vz", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> {
                                                                            Vec3d v = new Vec3d(
                                                                                    DoubleArgumentType.getDouble(ctx, "vx"),
                                                                                    DoubleArgumentType.getDouble(ctx, "vy"),
                                                                                    DoubleArgumentType.getDouble(ctx, "vz")
                                                                            );
                                                                            return execute(ctx, false, v, null, null, null);
                                                                        })
                                                                )
                                                        )
                                                )
                                        )
                                        .then(CommandManager.literal("to")
                                                .then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
                                                        .executes(ctx -> execute(ctx, false, null, Vec3ArgumentType.getVec3(ctx, "pos"), null, null))
                                                )
                                                .then(CommandManager.argument("entity", EntityArgumentType.entity())
                                                        .executes(ctx -> execute(ctx, false, null, null, EntityArgumentType.getEntity(ctx, "entity"), null))
                                                )
                                        )
                                        .then(CommandManager.literal("at")
                                                .then(CommandManager.argument("rotation", RotationArgumentType.rotation())
                                                        .executes(ctx -> {
                                                            Vec2f raw = RotationArgumentType.getRotation(ctx, "rotation")
                                                                    .toAbsoluteRotation(ctx.getSource());
                                                            return execute(ctx, false, null, null, null, new Vec2f(raw.x, raw.y));
                                                        })
                                                )
                                        )
                                )
                        )
                )
                .then(CommandManager.literal("add")
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .then(CommandManager.argument("strength", FloatArgumentType.floatArg())
                                        .executes(ctx -> execute(ctx, true, null, null, null, null))
                                        .then(CommandManager.literal("with")
                                                .then(CommandManager.argument("vx", DoubleArgumentType.doubleArg())
                                                        .then(CommandManager.argument("vy", DoubleArgumentType.doubleArg())
                                                                .then(CommandManager.argument("vz", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> {
                                                                            Vec3d v = new Vec3d(
                                                                                    DoubleArgumentType.getDouble(ctx, "vx"),
                                                                                    DoubleArgumentType.getDouble(ctx, "vy"),
                                                                                    DoubleArgumentType.getDouble(ctx, "vz")
                                                                            );
                                                                            return execute(ctx, true, v, null, null, null);
                                                                        })
                                                                )
                                                        )
                                                )
                                        )
                                        .then(CommandManager.literal("to")
                                                .then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
                                                        .executes(ctx -> execute(ctx, true, null, Vec3ArgumentType.getVec3(ctx, "pos"), null, null))
                                                )
                                                .then(CommandManager.argument("entity", EntityArgumentType.entity())
                                                        .executes(ctx -> execute(ctx, true, null, null, EntityArgumentType.getEntity(ctx, "entity"), null))
                                                )
                                        )
                                        .then(CommandManager.literal("at")
                                                .then(CommandManager.argument("rotation", RotationArgumentType.rotation())
                                                        .executes(ctx -> {
                                                            Vec2f raw = RotationArgumentType.getRotation(ctx, "rotation")
                                                                    .toAbsoluteRotation(ctx.getSource());
                                                            return execute(ctx, true, null, null, null, new Vec2f(raw.x, raw.y));
                                                        })
                                                )
                                        )
                                )
                        )
                )
                .then(CommandManager.literal("multiply")
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .then(CommandManager.argument("factor", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> executeMultiply(ctx, "xyz"))
                                        .then(CommandManager.argument("axes", StringArgumentType.word())
                                                .executes(ctx -> executeMultiply(
                                                        ctx,
                                                        StringArgumentType.getString(ctx, "axes").toLowerCase()
                                                ))
                                        )
                                )
                        )
                )
        );
    }

    private static int executeMultiply(CommandContext<ServerCommandSource> ctx, String axes)
            throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgumentType.getEntities(ctx, "targets");
        double factor = DoubleArgumentType.getDouble(ctx, "factor");
        boolean ax = axes.contains("x");
        boolean ay = axes.contains("y");
        boolean az = axes.contains("z");
        int applied = 0;
        for (Entity e : entities) {
            Vec3d vel = e.getVelocity();
            double nx = ax ? vel.x * factor : vel.x;
            double ny = ay ? vel.y * factor : vel.y;
            double nz = az ? vel.z * factor : vel.z;
            e.setVelocity(nx, ny, nz);
            e.velocityModified = true;
            applied++;
        }
        final int resultCount = applied;
        ctx.getSource().sendFeedback(
                () -> Text.literal("Multiplied motion for " + resultCount + " entities"),
                true
        );
        return applied;
    }

    private static int execute(CommandContext<ServerCommandSource> ctx,
                               boolean additive,
                               Vec3d explicitVec,
                               Vec3d targetPos,
                               Entity targetEntity,
                               Vec2f explicitRot) throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgumentType.getEntities(ctx, "targets");
        float strength = FloatArgumentType.getFloat(ctx, "strength");

        // Pre-compute a uniform direction if it doesn't depend on each entity's position
        Vec3d uniformDir = null;
        if (explicitVec != null) {
            uniformDir = explicitVec.normalize().multiply(strength);
        } else if (explicitRot != null) {
            uniformDir = rotationToDirection(explicitRot).multiply(strength);
        } else if (targetPos == null && targetEntity == null) {
            uniformDir = rotationToDirection(ctx.getSource().getRotation()).multiply(strength);
        }

        int applied = 0;
        for (Entity e : entities) {
            Vec3d dir = uniformDir;

            // Per-entity direction toward a position or entity
            if (targetPos != null) {
                Vec3d delta = targetPos.subtract(e.getPos());
                if (delta.lengthSquared() == 0.0) continue;
                dir = delta.normalize().multiply(strength);
            } else if (targetEntity != null) {
                Vec3d delta = targetEntity.getPos().subtract(e.getPos());
                if (delta.lengthSquared() == 0.0) continue;
                dir = delta.normalize().multiply(strength);
            }

            if (dir == null) continue;

            if (additive) {
                // ADD: stack on top of existing velocity
                Vec3d cur = e.getVelocity();
                e.setVelocity(cur.x + dir.x, cur.y + dir.y, cur.z + dir.z);
            } else {
                // SET: replace velocity entirely
                e.setVelocity(dir.x, dir.y, dir.z);
            }
            e.velocityModified = true;
            applied++;
        }

        final int resultCount = applied;
        final String action = additive ? "Added motion to" : "Set motion for";
        ctx.getSource().sendFeedback(
                () -> Text.literal(action + " " + resultCount + " entities"),
                true
        );
        return applied;
    }

    private static Vec3d rotationToDirection(Vec2f rot) {
        float yawRad   = (float) Math.toRadians(-rot.y);
        float pitchRad = (float) Math.toRadians(-rot.x);
        double x = Math.cos(pitchRad) * Math.sin(yawRad);
        double y = Math.sin(pitchRad);
        double z = Math.cos(pitchRad) * Math.cos(yawRad);
        return new Vec3d(x, y, z).normalize();
    }
}