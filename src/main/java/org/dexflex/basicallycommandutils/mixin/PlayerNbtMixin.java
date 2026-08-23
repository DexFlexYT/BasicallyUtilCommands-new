package org.dexflex.basicallycommandutils.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SuppressWarnings("null")
@Mixin(Player.class)
public abstract class PlayerNbtMixin {
    private String savedPlayerName;

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void writePlayerName(ValueOutput output, CallbackInfo ci) {
        String username = ((Player)(Object)this).getName().getString();
        output.putString("PlayerName", username);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void readPlayerName(ValueInput input, CallbackInfo ci) {
        this.savedPlayerName = input.getStringOr("PlayerName", null);
    }

    public String getSavedPlayerName() {
        return this.savedPlayerName;
    }
}