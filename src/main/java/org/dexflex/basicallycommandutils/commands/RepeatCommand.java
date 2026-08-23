package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import com.mojang.brigadier.arguments.IntegerArgumentType;


@SuppressWarnings("null")
public class RepeatCommand {

    private static final Queue<ScheduledExecution> scheduledExecutions = new LinkedList<>();
    private static boolean tickHandlerRegistered = false;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!tickHandlerRegistered) {
            registerTickHandler();
            tickHandlerRegistered = true;
        }

        dispatcher.register(Commands.literal("repeat")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("amount", IntegerArgumentType.integer())
                        .then(Commands.argument("delay", IntegerArgumentType.integer())
                                .then(Commands.literal("run")
                                        .fork(dispatcher.getRoot(), ctx -> {
                                            int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                            int delay = IntegerArgumentType.getInteger(ctx, "delay");

                                            java.util.Collection<CommandSourceStack> sources = new java.util.ArrayList<>();

                                            if (delay == 0) {
                                                String command = ctx.getInput().substring(ctx.getInput().indexOf("run") + 4);
                                                CommandSourceStack src = ctx.getSource();
                                                for (int i = 0; i < amount; i++) {
                                                    try {
                                                        src.getServer().getCommands().performPrefixedCommand(src, command);
                                                    } catch (Exception e) {
                                                        src.sendFailure(Component.literal("Error: " + e.getMessage()));
                                                    }
                                                }
                                                return java.util.Collections.emptyList(); // stop fork() from ALSO re-running it
                                            }
                                            else {
                                                scheduledExecutions.add(new ScheduledExecution(
                                                        ctx.getSource(),
                                                        amount,
                                                        delay,
                                                        ctx.getInput().substring(ctx.getInput().indexOf("run") + 4)
                                                ));
                                                ctx.getSource().sendSuccess(() ->
                                                                Component.literal("Scheduled " + amount + " executions with " + delay + " tick delay"),
                                                        true
                                                );
                                            }

                                            return sources;
                                        })))));
    }

    private static void registerTickHandler() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            List<ScheduledExecution> toProcess = new ArrayList<>(scheduledExecutions);

            for (ScheduledExecution scheduled : toProcess) {
                if (!scheduledExecutions.contains(scheduled)) {
                    continue;
                }

                scheduled.ticksRemaining--;

                if (scheduled.ticksRemaining <= 0) {
                    try {
                        server.getCommands().performPrefixedCommand(scheduled.source, scheduled.command);
                    } catch (Exception e) {
                        scheduled.source.sendFailure(Component.literal("Error: " + e.getMessage()));
                    }

                    scheduled.executionsRemaining--;

                    if (scheduled.executionsRemaining > 0) {
                        scheduled.ticksRemaining = scheduled.delayTicks;
                    } else {
                        scheduledExecutions.remove(scheduled);
                    }
                }
            }
        });
    }

    private static class ScheduledExecution {
        final CommandSourceStack source;
        final String command;
        int executionsRemaining;
        final int delayTicks;
        int ticksRemaining;

        ScheduledExecution(CommandSourceStack source, int amount, int delay, String command) {
            this.source = source;
            this.command = command;
            this.executionsRemaining = amount;
            this.delayTicks = delay;
            this.ticksRemaining = delay;
        }
    }
}