package org.dexflex.basicallycommandutils.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Collection;

@SuppressWarnings("null")
public class HealCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("heal")
                .requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("target", EntityArgument.entities())
                        .then(Commands.argument("amount", FloatArgumentType.floatArg())
                                .executes(HealCommand::execute)
                        )
                )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<? extends Entity> entities = EntityArgument.getEntities(context, "target");
        float amount = FloatArgumentType.getFloat(context, "amount");

        int healed = 0;
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living) {
                float newHealth = Math.min(living.getHealth() + amount, living.getMaxHealth());
                living.setHealth(newHealth);
                healed++;
            }
        }

        final int healedFinal = healed;
        context.getSource().sendSuccess(
                () -> Component.literal("Healed " + healedFinal + " entities."),
                true
        );

        return healedFinal;
    }
}