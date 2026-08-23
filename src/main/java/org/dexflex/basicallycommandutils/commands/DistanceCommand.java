package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

@SuppressWarnings("null")
public class DistanceCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("distance")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("origin", Vec3Argument.vec3())
                        .then(Commands.argument("target", Vec3Argument.vec3())
                                .executes(ctx -> executeVecs(ctx, 1.0f))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg())
                                        .executes(ctx -> executeVecs(ctx, FloatArgumentType.getFloat(ctx, "scale")))
                                )
                        )
                )
                // Merged: both entity→entity and entity→vec3 live under one "originEntity" node
                .then(Commands.argument("originEntity", EntityArgument.entity())
                        .then(Commands.argument("targetEntity", EntityArgument.entity())
                                .executes(ctx -> executeEntities(ctx, 1.0f))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg())
                                        .executes(ctx -> executeEntities(ctx, FloatArgumentType.getFloat(ctx, "scale")))
                                )
                        )
                        .then(Commands.argument("targetVec", Vec3Argument.vec3())
                                .executes(ctx -> executeEntityVec(ctx, 1.0f))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg())
                                        .executes(ctx -> executeEntityVec(ctx, FloatArgumentType.getFloat(ctx, "scale")))
                                )
                        )
                )
                .then(Commands.argument("originVec", Vec3Argument.vec3())
                        .then(Commands.argument("targetEntity", EntityArgument.entity())
                                .executes(ctx -> executeVecEntity(ctx, 1.0f))
                                .then(Commands.argument("scale", FloatArgumentType.floatArg())
                                        .executes(ctx -> executeVecEntity(ctx, FloatArgumentType.getFloat(ctx, "scale")))
                                )
                        )
                )
        );
    }

    private static int executeVecs(CommandContext<CommandSourceStack> context, float scale) throws CommandSyntaxException {
        Vec3 origin = Vec3Argument.getVec3(context, "origin");
        Vec3 target = Vec3Argument.getVec3(context, "target");
        return sendDistance(context, origin, target, scale);
    }

    private static int executeEntities(CommandContext<CommandSourceStack> context, float scale) throws CommandSyntaxException {
        Entity origin = EntityArgument.getEntity(context, "originEntity");
        Entity target = EntityArgument.getEntity(context, "targetEntity");
        return sendDistance(context, origin.position(), target.position(), scale);
    }

    private static int executeEntityVec(CommandContext<CommandSourceStack> context, float scale) throws CommandSyntaxException {
        Entity origin = EntityArgument.getEntity(context, "originEntity");
        Vec3 target = Vec3Argument.getVec3(context, "targetVec");
        return sendDistance(context, origin.position(), target, scale);
    }

    private static int executeVecEntity(CommandContext<CommandSourceStack> context, float scale) throws CommandSyntaxException {
        Vec3 origin = Vec3Argument.getVec3(context, "originVec");
        Entity target = EntityArgument.getEntity(context, "targetEntity");
        return sendDistance(context, origin, target.position(), scale);
    }

    private static int sendDistance(CommandContext<CommandSourceStack> context, Vec3 origin, Vec3 target, float scale) {
        double distance = origin.distanceTo(target) * scale;
        context.getSource().sendSuccess(() -> Component.literal(String.format("Distance: %.3f", distance)), false);
        return (int) distance;
    }
}