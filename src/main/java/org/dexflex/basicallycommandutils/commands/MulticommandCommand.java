package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MulticommandCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("multicommand")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("commands", StringArgumentType.greedyString())
                        .executes(MulticommandCommand::executeMulticommand)
                )
        );
    }

    private static int executeMulticommand(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String input = StringArgumentType.getString(context, "commands");
        List<String> commands = parseCommands(input);
        
        if (commands.isEmpty()) {
            context.getSource().sendError(Text.literal("No commands found. Use format: <c>command1</c><c>command2</c><c>etc...</c>"));
            return 0;
        }
        
        ServerCommandSource source = context.getSource();
        CommandDispatcher<ServerCommandSource> dispatcher = source.getServer().getCommandManager().getDispatcher();
        
        int successCount = 0;
        for (String command : commands) {
            try {
                int result = dispatcher.execute(command, source);
                if (result > 0) {
                    successCount++;
                }
            } catch (CommandSyntaxException e) {
                source.sendError(Text.literal("Error in command: " + command + " - " + e.getMessage()));
            } catch (Exception e) {
                source.sendError(Text.literal("Error in command: " + command + " - " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        
        return successCount;
    }

    private static List<String> parseCommands(String input) {
        List<String> commands = new ArrayList<>();
        // Pattern matches: <c:optional_opening_comment>command</c:optional_closing_comment>
        Pattern pattern = Pattern.compile("<c(?::.*?)?>(.+?)</c(?::.*?)?>");
        Matcher matcher = pattern.matcher(input);
        
        while (matcher.find()) {
            String command = matcher.group(1).trim();
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        
        return commands;
    }
}
