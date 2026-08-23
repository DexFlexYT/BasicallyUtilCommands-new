package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.Collections;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Extends /execute if and /execute unless with eval subcommand:
 *   /execute if eval <expression>
 *   /execute unless eval <expression>
 *
 * Expression is evaluated through EvalCommand's evaluator.
 * Test passes if result >= 1, fails if result == 0.
 *
 * Examples:
 *   /execute if eval "round(@s?cooldown/10)=0" run say "recharged!"
 *   /execute if eval "@s?score>5 && @a[distance=..10]?score<10" run say "in range!"
 */

@SuppressWarnings("null")
public class ExecuteEvalCommand {

    // Reuse vanilla's exception so the failure message is consistent
    private static final SimpleCommandExceptionType CONDITIONAL_FAIL_EXCEPTION =
            new SimpleCommandExceptionType(Component.translatable("commands.execute.conditional.fail"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var executeNode = dispatcher.getRoot().getChild("execute");
        if (executeNode == null) return;

        var ifNode = executeNode.getChild("if");
        if (ifNode != null) addEvalSubtree(ifNode, executeNode, true);

        var unlessNode = executeNode.getChild("unless");
        if (unlessNode != null) addEvalSubtree(unlessNode, executeNode, false);
    }

    private static void addEvalSubtree(
            CommandNode<CommandSourceStack> parent,
            CommandNode<CommandSourceStack> executeNode,
            boolean positive) {

        var evalNode = literal("eval")
                .then(argument("expression", StringArgumentType.string())
                        .fork(executeNode, ctx -> {
                            double result = evaluateExpression(ctx);
                            boolean passes = (result >= 1.0);
                            Collection<CommandSourceStack> sources = (passes == positive)
                                    ? Collections.singleton(ctx.getSource())
                                    : Collections.emptyList();
                            return sources;
                        })
                        .executes(ctx -> {
                            double result = evaluateExpression(ctx);
                            boolean passes = (result >= 1.0);
                            if (passes == positive) {
                                ctx.getSource().sendSuccess(
                                        () -> Component.translatable("commands.execute.conditional.pass"),
                                        false);
                                return 1;
                            } else {
                                throw CONDITIONAL_FAIL_EXCEPTION.create();
                            }
                        })
                )
                .build();

        parent.addChild(evalNode);
    }

    private static double evaluateExpression(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String expression = StringArgumentType.getString(ctx, "expression");
        try {
            return EvalCommand.evaluateExpression(expression, ctx.getSource());
        } catch (EvalCommand.EvalException e) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
        }
    }
}