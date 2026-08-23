package org.dexflex.basicallycommandutils.commands;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.serialization.JsonOps;

import net.minecraft.command.DataCommandStorage;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.command.argument.ScoreHolderArgumentType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Adds a /string command family, since vanilla NBT has no string type
 * distinct from a raw NBT string, no concatenation, and no NBT->JSON
 * serializer. Every subcommand writes its result to a destination storage
 * path rather than returning it, since commands can only return int.
 *
 * NOTE: DataCommandStorage / MinecraftServer#getDataCommandStorage() are the
 * names I'm most confident are correct for Yarn ~1.21.x, but this is the one
 * spot worth double-checking against your actual mapped sources if it
 * doesn't compile - everything else here doesn't depend on that API.
 */
public class StringCommand {

    private static final SimpleCommandExceptionType NOT_A_STRING =
            new SimpleCommandExceptionType(Text.literal("Target NBT value is not a string"));

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> root =
                CommandManager.literal("string").requires(source -> source.hasPermissionLevel(2));

        // /string set storage <id> <path> value <text>
        // /string set storage <id> <path> from storage <id> <path>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> set = CommandManager.literal("set");
        set.then(destStorage(StringCommand::executeSetValue, StringCommand::executeSetFrom));
        root.then(set);

        // /string append storage <id> <path> value <text>
        // /string append storage <id> <path> from storage <id> <path>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> append = CommandManager.literal("append");
        append.then(destStorage(StringCommand::executeAppendValue, StringCommand::executeAppendFrom));
        root.then(append);

        // /string tojson storage <id> <path> from storage <id> <path>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> toJson = CommandManager.literal("tojson");
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> toJsonStorage = CommandManager.literal("storage");
        toJsonStorage.then(CommandManager.argument("destId", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("destPath", NbtPathArgumentType.nbtPath())
                        .then(CommandManager.literal("from")
                                .then(CommandManager.literal("storage")
                                        .then(CommandManager.argument("srcId", IdentifierArgumentType.identifier())
                                                .then(CommandManager.argument("srcPath", NbtPathArgumentType.nbtPath())
                                                        .executes(StringCommand::executeToJson)))))));
        toJson.then(toJsonStorage);
        root.then(toJson);

        // /string concat storage <id> <path> storage <id> <path> storage <id> <path>
        // (dest, a, b - all storage sources)
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> concat = CommandManager.literal("concat");
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> concatStorage = CommandManager.literal("storage");
        concatStorage.then(CommandManager.argument("destId", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("destPath", NbtPathArgumentType.nbtPath())
                        .then(CommandManager.literal("storage")
                                .then(CommandManager.argument("aId", IdentifierArgumentType.identifier())
                                        .then(CommandManager.argument("aPath", NbtPathArgumentType.nbtPath())
                                                .then(CommandManager.literal("storage")
                                                        .then(CommandManager.argument("bId", IdentifierArgumentType.identifier())
                                                                .then(CommandManager.argument("bPath", NbtPathArgumentType.nbtPath())
                                                                        .executes(StringCommand::executeConcat)))))))));
        concat.then(concatStorage);
        root.then(concat);

        // /string length storage <id> <path> <scoreHolder> <objective>
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> length = CommandManager.literal("length");
        com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> lengthStorage = CommandManager.literal("storage");
        lengthStorage.then(CommandManager.argument("srcId", IdentifierArgumentType.identifier())
                .then(CommandManager.argument("srcPath", NbtPathArgumentType.nbtPath())
                        .then(CommandManager.argument("target", ScoreHolderArgumentType.scoreHolder())
                                .then(CommandManager.argument("objective", StringArgumentType.word())
                                        .executes(StringCommand::executeLength)))));
        length.then(lengthStorage);
        root.then(length);

        dispatcher.register(root);
    }

    // ---- grammar helper: "storage <id> <path> (value <text> | from storage <id> <path>)" ----
    private interface ValueExec {
        int run(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<ServerCommandSource, ?> destStorage(
            ValueExec valueExec, ValueExec fromExec) {
        return CommandManager.literal("storage")
                .then(CommandManager.argument("destId", IdentifierArgumentType.identifier())
                        .then(CommandManager.argument("destPath", NbtPathArgumentType.nbtPath())
                                .then(CommandManager.literal("value")
                                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                                .executes(valueExec::run)))
                                .then(CommandManager.literal("from")
                                        .then(CommandManager.literal("storage")
                                                .then(CommandManager.argument("srcId", IdentifierArgumentType.identifier())
                                                        .then(CommandManager.argument("srcPath", NbtPathArgumentType.nbtPath())
                                                                .executes(fromExec::run)))))));
    }

    // ---- implementations ----

    private static int executeSetValue(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String text = StringArgumentType.getString(ctx, "text");
        writeString(ctx, "destId", "destPath", text);
        feedback(ctx, text);
        return 1;
    }

    private static int executeSetFrom(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String value = readString(ctx, "srcId", "srcPath");
        writeString(ctx, "destId", "destPath", value);
        feedback(ctx, value);
        return 1;
    }

    private static int executeAppendValue(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String existing = tryReadString(ctx, "destId", "destPath");
        String appended = existing + StringArgumentType.getString(ctx, "text");
        writeString(ctx, "destId", "destPath", appended);
        feedback(ctx, appended);
        return 1;
    }

    private static int executeAppendFrom(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String existing = tryReadString(ctx, "destId", "destPath");
        String toAppend = readString(ctx, "srcId", "srcPath");
        String result = existing + toAppend;
        writeString(ctx, "destId", "destPath", result);
        feedback(ctx, result);
        return 1;
    }

    private static int executeConcat(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String a = readString(ctx, "aId", "aPath");
        String b = readString(ctx, "bId", "bPath");
        String result = a + b;
        writeString(ctx, "destId", "destPath", result);
        feedback(ctx, result);
        return 1;
    }

    private static int executeToJson(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        NbtElement source = readNbt(ctx, "srcId", "srcPath");
        // Convert arbitrary NBT (compound/list/whatever) into a proper JSON tree,
        // then serialize that to a JSON string with correctly quoted keys/escapes.
        JsonElement json = NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, source);
        String jsonText = new Gson().toJson(json);
        writeString(ctx, "destId", "destPath", jsonText);
        feedback(ctx, jsonText);
        return 1;
    }

    private static int executeLength(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String value = readString(ctx, "srcId", "srcPath");
        ScoreHolder target = ScoreHolderArgumentType.getScoreHolder(ctx, "target");
        String objectiveName = StringArgumentType.getString(ctx, "objective");

        ServerCommandSource source = ctx.getSource();
        net.minecraft.scoreboard.Scoreboard scoreboard = source.getServer().getScoreboard();
        net.minecraft.scoreboard.ScoreboardObjective objective = scoreboard.getNullableObjective(objectiveName);
        if (objective == null) {
            source.sendError(Text.literal("Unknown objective: " + objectiveName));
            return 0;
        }
        scoreboard.getOrCreateScore(target, objective).setScore(value.length());
        source.sendFeedback(() -> Text.literal("Length: " + value.length()), true);
        return value.length();
    }

    // ---- storage plumbing ----
    // This is the one section to verify against your exact mappings if it doesn't compile -
    // the grammar/logic above it doesn't depend on getting this exactly right.

    private static DataCommandStorage storage(CommandContext<ServerCommandSource> ctx) {
        return ctx.getSource().getServer().getDataCommandStorage();
    }

    private static NbtElement readNbt(CommandContext<ServerCommandSource> ctx, String idArg, String pathArg)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Identifier id = IdentifierArgumentType.getIdentifier(ctx, idArg);
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.getNbtPath(ctx, pathArg);
        NbtElement root = storage(ctx).get(id);
        return path.get(root).get(0);
    }

    private static String readString(CommandContext<ServerCommandSource> ctx, String idArg, String pathArg)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        NbtElement element = readNbt(ctx, idArg, pathArg);
        if (element instanceof NbtString nbtString) {
            return nbtString.asString();
        }
        throw NOT_A_STRING.create();
    }

    /** Like readString, but returns "" instead of throwing if the path doesn't exist yet - for append targets. */
    private static String tryReadString(CommandContext<ServerCommandSource> ctx, String idArg, String pathArg) {
        try {
            return readString(ctx, idArg, pathArg);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeString(CommandContext<ServerCommandSource> ctx, String idArg, String pathArg, String value)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Identifier id = IdentifierArgumentType.getIdentifier(ctx, idArg);
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.getNbtPath(ctx, pathArg);
        NbtElement root = storage(ctx).get(id);
        path.put(root, NbtString.of(value));
        storage(ctx).set(id, (net.minecraft.nbt.NbtCompound) root);
    }

    private static void feedback(CommandContext<ServerCommandSource> ctx, String value) {
        ctx.getSource().sendFeedback(() -> Text.literal(value), true);
    }
}