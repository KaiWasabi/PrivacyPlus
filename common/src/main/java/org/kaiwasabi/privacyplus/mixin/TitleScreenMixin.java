package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.gui.SettingsLink;
import org.kaiwasabi.privacyplus.incognito.IncognitoToggle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Top-left corner of the title screen: the "PrivacyPlus" settings link with the blocked-telemetry counter,
// and the incognito switch under it. Both are widgets, so they fade in with the rest of the screen.
// Plain strings, not translation keys: servers can probe for mod-added keys.
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void privacyplus$addCornerWidgets(CallbackInfo ci) {
        this.addRenderableWidget(SettingsLink.withTelemetryCount(2, 2, this.font, this));
        this.addRenderableWidget(new IncognitoToggle(2, 12, this.font));
    }
}
