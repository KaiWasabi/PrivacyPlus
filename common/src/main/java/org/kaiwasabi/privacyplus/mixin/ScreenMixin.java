package org.kaiwasabi.privacyplus.mixin;

import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.kaiwasabi.privacyplus.guard.ClipboardPromptScreen;
import org.kaiwasabi.privacyplus.guard.LinkWarningScreen;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Every clickable text a server sends ends up here: chat (through ChatScreen), books, dialogs (including
// the pause screen's server links) and the death screen. Signs run their click actions on the server.
@Mixin(Screen.class)
public abstract class ScreenMixin {
    // Links: vanilla opens them straight away, or after its own prompt. Risky ones get the warning screen
    // either way; when links are switched off in the options nothing opens, as in vanilla.
    @Inject(method = "clickUrlAction", at = @At("HEAD"), cancellable = true)
    private static void privacyplus$guardLink(Minecraft minecraft, @Nullable Screen screen, URI uri, CallbackInfoReturnable<Boolean> cir) {
        if (minecraft.options.chatLinks().get() && LinkWarningScreen.showIfRisky(minecraft, screen, uri)) {
            cir.setReturnValue(true);
        }
    }

    // Copying: vanilla puts the text on the clipboard without asking
    @Inject(method = "defaultHandleClickEvent", at = @At("HEAD"), cancellable = true)
    private static void privacyplus$guardClipboard(ClickEvent event, Minecraft minecraft, @Nullable Screen activeScreen, CallbackInfo ci) {
        if (event instanceof ClickEvent.CopyToClipboard copy && ClipboardPromptScreen.intercept(minecraft, activeScreen, copy)) {
            ci.cancel();
        }
    }
}
