package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.AngleArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import java.util.Collection;



@SuppressWarnings("null")
public class MotionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("motion")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("set")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("strength", FloatArgumentType.floatArg())
                                        .executes(ctx -> execute(ctx, false, null, null, null, null))
                                        .then(Commands.literal("with")
                                                .then(Commands.argument("vx", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("vy", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("vz", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> {
                                                                            Vec3 v = new Vec3(
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
                                        .then(Commands.literal("to")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> execute(ctx, false, null, Vec3Argument.getVec3(ctx, "pos"), null, null))
                                                )
                                                .then(Commands.argument("entity", EntityArgument.entity())
                                                        .executes(ctx -> execute(ctx, false, null, null, EntityArgument.getEntity(ctx, "entity"), null))
                                                )
                                        )
                                        .then(Commands.literal("at")
                                                .then(Commands.argument("yaw", AngleArgument.angle())
                                                        .then(Commands.argument("pitch", AngleArgument.angle())
                                                                .executes(ctx -> {
                                                                float yaw = AngleArgument.getAngle(ctx, "yaw");
                                                                float pitch = AngleArgument.getAngle(ctx, "pitch");
                                                                Vec2 raw = new Vec2(pitch, yaw); // Vec2 is (x=pitch, y=yaw) by Minecraft convention
                                                                return execute(ctx, false, null, null, null, raw);
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("add")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("strength", FloatArgumentType.floatArg())
                                        .executes(ctx -> execute(ctx, true, null, null, null, null))
                                        .then(Commands.literal("with")
                                                .then(Commands.argument("vx", DoubleArgumentType.doubleArg())
                                                        .then(Commands.argument("vy", DoubleArgumentType.doubleArg())
                                                                .then(Commands.argument("vz", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> {
                                                                            Vec3 v = new Vec3(
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
                                        .then(Commands.literal("to")
                                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                                        .executes(ctx -> execute(ctx, true, null, Vec3Argument.getVec3(ctx, "pos"), null, null))
                                                )
                                                .then(Commands.argument("entity", EntityArgument.entity())
                                                        .executes(ctx -> execute(ctx, true, null, null, EntityArgument.getEntity(ctx, "entity"), null))
                                                )
                                        )
                                        .then(Commands.literal("at")
                                                .then(Commands.argument("yaw", AngleArgument.angle())
                                                        .then(Commands.argument("pitch", AngleArgument.angle())
                                                                .executes(ctx -> {
                                                                    float yaw = AngleArgument.getAngle(ctx, "yaw");
                                                                    float pitch = AngleArgument.getAngle(ctx, "pitch");
                                                                    Vec2 raw = new Vec2(pitch, yaw); // Vec2 is (x=pitch, y=yaw) by Minecraft convention
                                                                    return execute(ctx, true, null, null, null, raw);
                                                                })
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(Commands.literal("multiply")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("factor", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> executeMultiply(ctx, "xyz"))
                                        .then(Commands.argument("axes", StringArgumentType.word())
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

    private static int executeMultiply(CommandContext<CommandSourceStack> ctx, String axes)
            throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "targets");
        double factor = DoubleArgumentType.getDouble(ctx, "factor");
        boolean ax = axes.contains("x");
        boolean ay = axes.contains("y");
        boolean az = axes.contains("z");
        int applied = 0;
        for (Entity e : entities) {
            Vec3 vel = e.getDeltaMovement();
            double nx = ax ? vel.x * factor : vel.x;
            double ny = ay ? vel.y * factor : vel.y;
            double nz = az ? vel.z * factor : vel.z;
            e.setDeltaMovement(nx, ny, nz);
            e.hurtMarked = true;
            applied++;
        }
        final int resultCount = applied;
        ctx.getSource().sendSuccess(
                () -> Component.literal("Multiplied motion for " + resultCount + " entities"),
                true
        );
        return applied;
    }

    private static int execute(CommandContext<CommandSourceStack> ctx,
                               boolean additive,
                               Vec3 explicitVec,
                               Vec3 targetPos,
                               Entity targetEntity,
                               Vec2 explicitRot) throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "targets");
        float strength = FloatArgumentType.getFloat(ctx, "strength");

        // Pre-compute a uniform direction if it doesn't depend on each entity's position
        Vec3 uniformDir = null;
        if (explicitVec != null) {
            uniformDir = explicitVec.normalize().scale(strength);
        } else if (explicitRot != null) {
            uniformDir = rotationToDirection(explicitRot).scale(strength);
        } else if (targetPos == null && targetEntity == null) {
            uniformDir = rotationToDirection(ctx.getSource().getRotation()).scale(strength);
        }

        int applied = 0;
        for (Entity e : entities) {
            Vec3 dir = uniformDir;

            // Per-entity direction toward a position or entity
            if (targetPos != null) {
                Vec3 delta = targetPos.subtract(e.position());
                if (delta.lengthSqr() == 0.0) continue;
                dir = delta.normalize().scale(strength);
            } else if (targetEntity != null) {
                Vec3 delta = targetEntity.position().subtract(e.position());
                if (delta.lengthSqr() == 0.0) continue;
                dir = delta.normalize().scale(strength);
            }

            if (dir == null) continue;

            if (additive) {
                // ADD: stack on top of existing velocity
                Vec3 cur = e.getDeltaMovement();
                e.setDeltaMovement(cur.x + dir.x, cur.y + dir.y, cur.z + dir.z);
            } else {
                // SET: replace velocity entirely
                e.setDeltaMovement(dir.x, dir.y, dir.z);
            }
            e.hurtMarked = true;
            applied++;
        }

        final int resultCount = applied;
        final String action = additive ? "Added motion to" : "Set motion for";
        ctx.getSource().sendSuccess(
                () -> Component.literal(action + " " + resultCount + " entities"),
                true
        );
        return applied;
    }

    private static Vec3 rotationToDirection(Vec2 rot) {
        float yawRad   = (float) Math.toRadians(-rot.y);
        float pitchRad = (float) Math.toRadians(-rot.x);
        double x = Math.cos(pitchRad) * Math.sin(yawRad);
        double y = Math.sin(pitchRad);
        double z = Math.cos(pitchRad) * Math.cos(yawRad);
        return new Vec3(x, y, z).normalize();
    }
}