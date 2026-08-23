package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

@SuppressWarnings("null")
public class RandCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> rand = Commands.literal("rand")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));

        rand.then(Commands.literal("offset")
                .then(Commands.argument("range", StringArgumentType.string())
                        .then(Commands.argument("snapping", StringArgumentType.string())
                                .redirect(dispatcher.getRoot(), ctx -> {
                                    Vec3 offset = getRandomOffset(ctx);
                                    return ctx.getSource().withPosition(ctx.getSource().getPosition().add(offset));
                                }))));

        rand.then(Commands.literal("rotate")
                .then(Commands.argument("range", StringArgumentType.string())
                        .then(Commands.argument("snapping", StringArgumentType.string())
                                .redirect(dispatcher.getRoot(), ctx -> {
                                    Vec2 rotation = getRandomRotation(ctx);
                                    return ctx.getSource().withRotation(rotation);
                                }))));

        dispatcher.register(rand);
    }

    private static Vec3 getRandomOffset(CommandContext<CommandSourceStack> ctx) {
        String[] parts = StringArgumentType.getString(ctx, "range").split("-");
        double x = 0, y = 0, z = 0;
        if (parts.length == 1) {
            x = y = z = Double.parseDouble(parts[0]);
        } else if (parts.length == 2) {
            x = Double.parseDouble(parts[0]);
            y = Double.parseDouble(parts[1]);
            z = 0;
        } else if (parts.length == 3) {
            x = Double.parseDouble(parts[0]);
            y = Double.parseDouble(parts[1]);
            z = Double.parseDouble(parts[2]);
        }

        double snapping = Double.parseDouble(StringArgumentType.getString(ctx, "snapping"));
        Random rand = new Random();
        double dx = snap((rand.nextDouble() * 2 - 1) * x, snapping);
        double dy = snap((rand.nextDouble() * 2 - 1) * y, snapping);
        double dz = snap((rand.nextDouble() * 2 - 1) * z, snapping);

        return new Vec3(dx, dy, dz);
    }

    private static Vec2 getRandomRotation(CommandContext<CommandSourceStack> ctx) {
        String[] parts = StringArgumentType.getString(ctx, "range").split("-");
        float yaw = 0, pitch = 0;
        if (parts.length == 1) {
            yaw = pitch = Float.parseFloat(parts[0]);
        } else if (parts.length == 2) {
            yaw = Float.parseFloat(parts[0]);
            pitch = Float.parseFloat(parts[1]);
        }

        float snapping = Float.parseFloat(StringArgumentType.getString(ctx, "snapping"));
        Random rand = new Random();
        float yawOffset = snap((float)(rand.nextDouble() * 2 - 1) * yaw, snapping);
        float pitchOffset = snap((float)(rand.nextDouble() * 2 - 1) * pitch, snapping);

        Vec2 current = ctx.getSource().getRotation();
        return new Vec2(current.x + yawOffset, current.y + pitchOffset);
    }

    private static float snap(float val, float step) {
        if (step == 0) return val;
        return Math.round(val / step) * step;
    }

    private static double snap(double val, double step) {
        if (step == 0) return val;
        return Math.round(val / step) * step;
    }
}