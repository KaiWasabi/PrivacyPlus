package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.kaiwasabi.privacyplus.stealth.ProbeShield;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// The editor turns the sign's server-supplied lines into strings and sends them all back when closed.
@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {
    // A bare "<init>" selects only the first constructor, which just delegates to this one
    @Redirect(
        method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;ZLnet/minecraft/network/chat/Component;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;")
    )
    private String privacyplus$resolveLikeVanilla(Component line) {
        ProbeAlarm.inspect(ProbeAlarm.Kind.SIGN, line);
        return ProbeShield.resolve(line);
    }
}
