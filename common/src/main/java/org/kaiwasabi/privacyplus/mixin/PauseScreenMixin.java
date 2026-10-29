package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.gui.SettingsLink;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A small "PrivacyPlus" settings link in the pause menu's top-left corner. Only when the menu shows its
// buttons (not after F3+Esc), and moved below the "Now playing" popup while that is drawn in the same corner.
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    @Unique
    private @Nullable SettingsLink privacyplus$link;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void privacyplus$addSettingsLink(CallbackInfo ci) {
        this.privacyplus$link = ((PauseScreen) (Object) this).showsPauseMenu()
            ? this.addRenderableWidget(new SettingsLink(2, 2, this.font, this))
            : null;
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void privacyplus$avoidNowPlaying(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (this.privacyplus$link != null) {
            boolean nowPlaying = ((PauseScreen) (Object) this).rendersNowPlayingToast()
                && this.minecraft.getMusicManager().getCurrentMusicTranslationKey() != null;
            // The popup is 30 pixels high
            this.privacyplus$link.setY(nowPlaying ? 32 : 2);
        }
    }
}
