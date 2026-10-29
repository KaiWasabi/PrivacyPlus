package org.kaiwasabi.privacyplus.mixin;

import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import org.kaiwasabi.privacyplus.guard.LinkWarningScreen;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Untrusted links that skip Screen.clickUrlAction: the "Report to server" button on the disconnected
// screen opens the bug-report address the server sent. Minecraft's own (trusted) links are left alone.
@Mixin(ConfirmLinkScreen.class)
public abstract class ConfirmLinkScreenMixin {
    @Inject(method = "confirmLinkNow(Lnet/minecraft/client/gui/screens/Screen;Ljava/net/URI;Z)V", at = @At("HEAD"), cancellable = true)
    private static void privacyplus$guardServerLink(@Nullable Screen parentScreen, URI uri, boolean trusted, CallbackInfo ci) {
        if (!trusted && LinkWarningScreen.showIfRisky(Minecraft.getInstance(), parentScreen, uri)) {
            ci.cancel();
        }
    }
}
