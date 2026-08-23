package org.dexflex.basicallycommandutils.commands;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.serialization.JsonOps;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.commands.arguments.NbtPathArgument.NbtPath;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.ScoreHolderArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreAccess;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

/**
 * Adds a /string command family, since vanilla NBT has no string type
 * distinct from a raw NBT string, no concatenation, and no NBT->JSON
 * serializer. Every subcommand writes its result to a destination storage
 * path rather than returning it, since commands can only return int.
 */



@SuppressWarnings("null")
public class StringCommand {

    private static final SimpleCommandExceptionType NOT_A_STRING =
            new SimpleCommandExceptionType(Component.literal("Target NBT value is not a string"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root =
                Commands.literal("string")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));

        // /string set storage <id> <path> value <text>
        // /string set storage <id> <path> from storage <id> <path>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> set = Commands.literal("set");
        set.then(destStorage(StringCommand::executeSetValue, StringCommand::executeSetFrom));
        root.then(set);

        // /string append storage <id> <path> value <text>
        // /string append storage <id> <path> from storage <id> <path>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> append = Commands.literal("append");
        append.then(destStorage(StringCommand::executeAppendValue, StringCommand::executeAppendFrom));
        root.then(append);

        // /string tojson storage <id> <path> from storage <id> <path>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> toJson = Commands.literal("tojson");
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> toJsonStorage = Commands.literal("storage");
        toJsonStorage.then(Commands.argument("destId", IdentifierArgument.id())
                .then(Commands.argument("destPath", NbtPathArgument.nbtPath())
                        .then(Commands.literal("from")
                                .then(Commands.literal("storage")
                                        .then(Commands.argument("srcId", IdentifierArgument.id())
                                                .then(Commands.argument("srcPath", NbtPathArgument.nbtPath())
                                                        .executes(StringCommand::executeToJson)))))));
        toJson.then(toJsonStorage);
        root.then(toJson);

        // /string concat storage <id> <path> storage <id> <path> storage <id> <path>
        // (dest, a, b - all storage sources)
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> concat = Commands.literal("concat");
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> concatStorage = Commands.literal("storage");
        concatStorage.then(Commands.argument("destId", IdentifierArgument.id())
                .then(Commands.argument("destPath", NbtPathArgument.nbtPath())
                        .then(Commands.literal("storage")
                                .then(Commands.argument("aId", IdentifierArgument.id())
                                        .then(Commands.argument("aPath", NbtPathArgument.nbtPath())
                                                .then(Commands.literal("storage")
                                                        .then(Commands.argument("bId", IdentifierArgument.id())
                                                                .then(Commands.argument("bPath", NbtPathArgument.nbtPath())
                                                                        .executes(StringCommand::executeConcat)))))))));
        concat.then(concatStorage);
        root.then(concat);

        // /string length storage <id> <path> <scoreHolder> <objective>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> length = Commands.literal("length");
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> lengthStorage = Commands.literal("storage");
        lengthStorage.then(Commands.argument("srcId", IdentifierArgument.id())
                .then(Commands.argument("srcPath", NbtPathArgument.nbtPath())
                        .then(Commands.argument("target", ScoreHolderArgument.scoreHolder())
                                .then(Commands.argument("objective", StringArgumentType.word())
                                        .executes(StringCommand::executeLength)))));
        length.then(lengthStorage);
        root.then(length);

        dispatcher.register(root);
    }

    // ---- grammar helper: "storage <id> <path> (value <text> | from storage <id> <path>)" ----
    private interface ValueExec {
        int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> destStorage(
            ValueExec valueExec, ValueExec fromExec) {
        return Commands.literal("storage")
                .then(Commands.argument("destId", IdentifierArgument.id())
                        .then(Commands.argument("destPath", NbtPathArgument.nbtPath())
                                .then(Commands.literal("value")
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(valueExec::run)))
                                .then(Commands.literal("from")
                                        .then(Commands.literal("storage")
                                                .then(Commands.argument("srcId", IdentifierArgument.id())
                                                        .then(Commands.argument("srcPath", NbtPathArgument.nbtPath())
                                                                .executes(fromExec::run)))))));
    }

    // ---- implementations ----

    private static int executeSetValue(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String text = StringArgumentType.getString(ctx, "text");
        writeString(ctx, "destId", "destPath", text);
        feedback(ctx, text);
        return 1;
    }

    private static int executeSetFrom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String value = readString(ctx, "srcId", "srcPath");
        writeString(ctx, "destId", "destPath", value);
        feedback(ctx, value);
        return 1;
    }

    private static int executeAppendValue(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String existing = tryReadString(ctx, "destId", "destPath");
        String appended = existing + StringArgumentType.getString(ctx, "text");
        writeString(ctx, "destId", "destPath", appended);
        feedback(ctx, appended);
        return 1;
    }

    private static int executeAppendFrom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String existing = tryReadString(ctx, "destId", "destPath");
        String toAppend = readString(ctx, "srcId", "srcPath");
        String result = existing + toAppend;
        writeString(ctx, "destId", "destPath", result);
        feedback(ctx, result);
        return 1;
    }

    private static int executeConcat(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String a = readString(ctx, "aId", "aPath");
        String b = readString(ctx, "bId", "bPath");
        String result = a + b;
        writeString(ctx, "destId", "destPath", result);
        feedback(ctx, result);
        return 1;
    }

    private static int executeToJson(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Tag source = readNbt(ctx, "srcId", "srcPath");
        // Convert arbitrary NBT (compound/list/whatever) into a proper JSON tree,
        // then serialize that to a JSON string with correctly quoted keys/escapes.
        JsonElement json = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, source);
        String jsonText = new Gson().toJson(json);
        writeString(ctx, "destId", "destPath", jsonText);
        feedback(ctx, jsonText);
        return 1;
    }

    private static int executeLength(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        String value = readString(ctx, "srcId", "srcPath");
        ScoreHolder target = ScoreHolderArgument.getName(ctx, "target");
        String objectiveName = StringArgumentType.getString(ctx, "objective");

        CommandSourceStack source = ctx.getSource();
        Scoreboard scoreboard = source.getServer().getScoreboard();
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) {
            source.sendFailure(Component.literal("Unknown objective: " + objectiveName));
            return 0;
        }
        ScoreAccess access = scoreboard.getOrCreatePlayerScore(target, objective);
        access.set(value.length());
        source.sendSuccess(() -> Component.literal("Length: " + value.length()), true);
        return value.length();
    }

    // ---- storage plumbing ----

    private static CommandStorage storage(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getServer().getCommandStorage();
    }

    private static Tag readNbt(CommandContext<CommandSourceStack> ctx, String idArg, String pathArg)
            throws CommandSyntaxException {
        Identifier id = IdentifierArgument.getId(ctx, idArg);
        NbtPath path = NbtPathArgument.getPath(ctx, pathArg);
        CompoundTag root = storage(ctx).get(id);
        return path.get(root).get(0);
    }

    private static String readString(CommandContext<CommandSourceStack> ctx, String idArg, String pathArg)
            throws CommandSyntaxException {
        Tag element = readNbt(ctx, idArg, pathArg);
        if (element instanceof StringTag stringTag) {
            return stringTag.value();
        }
        throw NOT_A_STRING.create();
    }

    /** Like readString, but returns "" instead of throwing if the path doesn't exist yet - for append targets. */
    private static String tryReadString(CommandContext<CommandSourceStack> ctx, String idArg, String pathArg) {
        try {
            return readString(ctx, idArg, pathArg);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeString(CommandContext<CommandSourceStack> ctx, String idArg, String pathArg, String value)
            throws CommandSyntaxException {
        Identifier id = IdentifierArgument.getId(ctx, idArg);
        NbtPath path = NbtPathArgument.getPath(ctx, pathArg);
        CompoundTag root = storage(ctx).get(id);
        path.set(root, StringTag.valueOf(value));
        storage(ctx).set(id, root);
    }

    private static void feedback(CommandContext<CommandSourceStack> ctx, String value) {
        ctx.getSource().sendSuccess(() -> Component.literal(value), true);
    }
}