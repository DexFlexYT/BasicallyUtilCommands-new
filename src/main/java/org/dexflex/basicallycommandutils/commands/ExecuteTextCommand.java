package org.dexflex.basicallycommandutils.commands;

// src/main/java/org/dexflex/basicallycommandutils/commands/ExecuteTextCommand.java

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Extends /execute if and /execute unless with string comparison subcommands:

 *   /execute if text <input> contains   <substring>
 *   /execute if text <input> exact      <match>
 *   /execute if text <input> ignorecase <match>
 *   /execute if text <input> startswith <prefix>
 *   /execute if text <input> endswith   <suffix>
 *   /execute if text <input> matches    <regex>

 * Mirrors vanilla's addConditionLogic pattern exactly:
 *   - .fork() for chaining further execute subcommands
 *   - .executes() for standalone use, throws CONDITIONAL_FAIL_EXCEPTION on failure
 */
public class ExecuteTextCommand {

    // Reuse vanilla's exception so the failure message is consistent
    private static final SimpleCommandExceptionType CONDITIONAL_FAIL_EXCEPTION =
            new SimpleCommandExceptionType(Text.translatable("commands.execute.conditional.fail"));

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var executeNode = dispatcher.getRoot().getChild("execute");
        if (executeNode == null) return;

        var ifNode = executeNode.getChild("if");
        if (ifNode != null) addTextSubtree(ifNode, executeNode, true);

        var unlessNode = executeNode.getChild("unless");
        if (unlessNode != null) addTextSubtree(unlessNode, executeNode, false);
    }

    private static void addTextSubtree(
            CommandNode<ServerCommandSource> parent,
            CommandNode<ServerCommandSource> executeNode,
            boolean positive) {

        var textNode  = literal("text").build();
        var inputNode = argument("input", StringArgumentType.string()).build();

        addOp(inputNode, executeNode, positive, "contains", ctx -> {
            String a = StringArgumentType.getString(ctx, "input");
            String b = StringArgumentType.getString(ctx, "substring");
            return a.contains(b);
        }, "substring");

        addOp(inputNode, executeNode, positive, "exact", ctx -> {
            String a = StringArgumentType.getString(ctx, "input");
            String b = StringArgumentType.getString(ctx, "match");
            return a.equals(b);
        }, "match");

        addOp(inputNode, executeNode, positive, "ignorecase", ctx -> {
            String a = StringArgumentType.getString(ctx, "input");
            String b = StringArgumentType.getString(ctx, "match");
            return a.equalsIgnoreCase(b);
        }, "match");

        addOp(inputNode, executeNode, positive, "startswith", ctx -> {
            String a = StringArgumentType.getString(ctx, "input");
            String b = StringArgumentType.getString(ctx, "prefix");
            return a.startsWith(b);
        }, "prefix");

        addOp(inputNode, executeNode, positive, "endswith", ctx -> {
            String a = StringArgumentType.getString(ctx, "input");
            String b = StringArgumentType.getString(ctx, "suffix");
            return a.endsWith(b);
        }, "suffix");

        addOp(inputNode, executeNode, positive, "matches", ctx -> {
            String a = StringArgumentType.getString(ctx, "input");
            String b = StringArgumentType.getString(ctx, "regex");
            try { return Pattern.compile(b).matcher(a).find(); }
            catch (PatternSyntaxException e) { return false; }
        }, "regex");

        textNode.addChild(inputNode);
        parent.addChild(textNode);
    }

    @FunctionalInterface
    private interface Condition {
        boolean test(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx)
                throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private static void addOp(
            CommandNode<ServerCommandSource> inputNode,
            CommandNode<ServerCommandSource> executeNode,
            boolean positive,
            String opName,
            Condition condition,
            String argName) {

        var opNode = literal(opName).build();

        // Mirror vanilla's addConditionLogic exactly
        var argNode = argument(argName, StringArgumentType.string())
                .fork(executeNode, ctx -> {
                    boolean result;
                    try { result = condition.test(ctx); }
                    catch (Exception e) { result = false; }
                    Collection<ServerCommandSource> sources = (result == positive)
                            ? Collections.singleton(ctx.getSource())
                            : Collections.emptyList();
                    return sources;
                })
                .executes(ctx -> {
                    boolean result;
                    try { result = condition.test(ctx); }
                    catch (Exception e) { result = false; }
                    if (positive == result) {
                        ctx.getSource().sendFeedback(
                                () -> Text.translatable("commands.execute.conditional.pass"),
                                false);
                        return 1;
                    } else {
                        throw CONDITIONAL_FAIL_EXCEPTION.create();
                    }
                })
                .build();

        opNode.addChild(argNode);
        inputNode.addChild(opNode);
    }
}