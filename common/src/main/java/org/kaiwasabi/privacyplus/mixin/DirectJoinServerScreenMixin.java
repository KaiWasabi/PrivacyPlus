package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Incognito: the Direct Connect address isn't remembered in options.txt ("lastServer")
@Mixin(DirectJoinServerScreen.class)
public abstract class DirectJoinServerScreenMixin {
    @Redirect(
        method = "removed",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;lastMpIp:Ljava/lang/String;", opcode = 181 /* PUTFIELD */)
    )
    private void privacyplus$incognitoLastServer(Options options, String address) {
        if (!PrivacyPlusConfig.incognito) {
            options.lastMpIp = address;
        }
    }
}
