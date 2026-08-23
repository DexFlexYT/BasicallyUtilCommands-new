package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;


@SuppressWarnings("null")
public class RaycastCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("raycast")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("steps", IntegerArgumentType.integer())
                        .then(Commands.argument("step_length", FloatArgumentType.floatArg())
                                .then(Commands.literal("run")
                                        .fork(dispatcher.getRoot(), ctx -> {
                                            int steps = IntegerArgumentType.getInteger(ctx, "steps");
                                            float stepLength = FloatArgumentType.getFloat(ctx, "step_length");

                                            // Fixed: Get command text after the current command's arguments
                                            String fullInput = ctx.getInput();
                                            String command = fullInput.substring(ctx.getRange().getEnd()).trim();
                                            if (command.startsWith("run ")) {
                                                command = command.substring(4);
                                            }

                                            CommandSourceStack source = ctx.getSource();

                                            ServerLevel world = source.getLevel();
                                            EntityAnchorArgument.Anchor anchor = source.getAnchor();
                                            Vec3 startPos = anchor.apply(source);
                                            Vec2 rot = source.getRotation();
                                            Vec3 dir = Vec3.directionFromRotation(rot);

                                            Marker marker = new Marker(EntityTypes.MARKER, world);
                                            marker.setPos(startPos);
                                            marker.addTag("BUC.raycast");
                                            world.addFreshEntity(marker);

                                            runRaycastStep(world, source, command, startPos, dir, stepLength, steps, 0, marker.getUUID());

                                            source.sendSuccess(() ->
                                                            Component.literal("Raycasting " + steps + " steps with length " + stepLength),
                                                    false
                                            );

                                            return java.util.Collections.emptyList();
                                        })))));
    }

    private static void runRaycastStep(ServerLevel world,
                                       CommandSourceStack originalSource,
                                       String command,
                                       Vec3 startPos,
                                       Vec3 dir,
                                       float stepLength,
                                       int maxSteps,
                                       int currentStep,
                                       UUID markerId) {

        // Stop if finished
        if (currentStep >= maxSteps) {
            Entity entity = world.getEntity(markerId);
            if (entity != null) entity.discard();
            return;
        }

        // Always re-fetch the marker every step
        Entity entity = world.getEntity(markerId);

        // If it was killed inside a previous command, stop immediately
        if (!(entity instanceof Marker marker) || !marker.isAlive()) {
            return;
        }

        // Move marker to next step position
        Vec3 stepPos = startPos.add(dir.scale(currentStep * stepLength));
        marker.setPos(stepPos);

        // Execute command as marker at new position
        CommandSourceStack stepSource = originalSource
                .withPosition(stepPos)
                .withEntity(marker)
                .withSuppressedOutput();

        try {
            world.getServer().getCommands().performPrefixedCommand(stepSource, command);
        } catch (Exception e) {
            originalSource.sendFailure(Component.literal("Raycast error at step " + currentStep + ": " + e.getMessage()));
            marker.discard();
            return;
        }

        // IMPORTANT: check again after command execution
        // If the function killed the marker, stop here and DO NOT schedule next step
        Entity after = world.getEntity(markerId);
        if (!(after instanceof Marker afterMarker) || !afterMarker.isAlive()) {
            return;
        }

        // Schedule next step (sub-tick consecutive execution)
        world.getServer().submit(() ->
                runRaycastStep(world,
                        originalSource,
                        command,
                        startPos,
                        dir,
                        stepLength,
                        maxSteps,
                        currentStep + 1,
                        markerId)
        );
    }
}