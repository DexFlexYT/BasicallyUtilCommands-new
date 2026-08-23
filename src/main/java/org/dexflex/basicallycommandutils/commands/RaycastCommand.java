package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import java.util.UUID;

public class RaycastCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("raycast")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("steps", IntegerArgumentType.integer())
                        .then(CommandManager.argument("step_length", FloatArgumentType.floatArg())
                                .then(CommandManager.literal("run")
                                        .fork(dispatcher.getRoot(), ctx -> {
                                            int steps = IntegerArgumentType.getInteger(ctx, "steps");
                                            float stepLength = FloatArgumentType.getFloat(ctx, "step_length");

                                            // Fixed: Get command text after the current command's arguments
                                            String fullInput = ctx.getInput();
                                            String command = fullInput.substring(ctx.getRange().getEnd()).trim();
                                            if (command.startsWith("run ")) {
                                                command = command.substring(4);
                                            }

                                            ServerCommandSource source = ctx.getSource();

                                            ServerWorld world = source.getWorld();
                                            EntityAnchorArgumentType.EntityAnchor anchor = source.getEntityAnchor();
                                            Vec3d startPos = anchor.positionAt(source);
                                            Vec2f rot = source.getRotation();
                                            Vec3d dir = Vec3d.fromPolar(rot.x, rot.y);

                                            MarkerEntity marker = new MarkerEntity(EntityType.MARKER, world);
                                            marker.setPosition(startPos);
                                            marker.addCommandTag("BUC.raycast");
                                            world.spawnEntity(marker);

                                            runRaycastStep(world, source, command, startPos, dir, stepLength, steps, 0, marker.getUuid());

                                            source.sendFeedback(() ->
                                                            Text.literal("Raycasting " + steps + " steps with length " + stepLength),
                                                    false
                                            );

                                            return java.util.Collections.emptyList();
                                        })))));
    }

    private static void runRaycastStep(ServerWorld world,
                                       ServerCommandSource originalSource,
                                       String command,
                                       Vec3d startPos,
                                       Vec3d dir,
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
        if (!(entity instanceof MarkerEntity marker) || !marker.isAlive()) {
            return;
        }

        // Move marker to next step position
        Vec3d stepPos = startPos.add(dir.multiply(currentStep * stepLength));
        marker.setPosition(stepPos);

        // Execute command as marker at new position
        ServerCommandSource stepSource = originalSource
                .withPosition(stepPos)
                .withEntity(marker)
                .withSilent();

        try {
            world.getServer().getCommandManager().executeWithPrefix(stepSource, command);
        } catch (Exception e) {
            originalSource.sendError(Text.literal("Raycast error at step " + currentStep + ": " + e.getMessage()));
            marker.discard();
            return;
        }

        // IMPORTANT: check again after command execution
        // If the function killed the marker, stop here and DO NOT schedule next step
        Entity after = world.getEntity(markerId);
        if (!(after instanceof MarkerEntity afterMarker) || !afterMarker.isAlive()) {
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