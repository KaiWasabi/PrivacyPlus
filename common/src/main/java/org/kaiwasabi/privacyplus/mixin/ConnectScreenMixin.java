package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.ConnectScreen;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Incognito: latest.log doesn't record "Connecting to <server>, <port>"
@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Redirect(method = "connect", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"))
    private void privacyplus$incognitoConnectLog(Logger logger, String message, Object host, Object port) {
        if (!PrivacyPlusConfig.incognito) {
            logger.info(message, host, port);
        }
    }
}
