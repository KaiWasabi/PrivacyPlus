package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.Options;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// The client information sent to every server (on join and whenever options change) includes the
// game language, a strong hint about where the player lives. en_us is by far the most common, so
// reporting it blends in. Only the reported value changes; the game itself stays in your language.
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Redirect(
        method = "buildPlayerInformation",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;languageCode:Ljava/lang/String;")
    )
    private String privacyplus$reportedLanguage(Options options) {
        return PrivacyPlusConfig.spoofLanguage ? "en_us" : options.languageCode;
    }
}
