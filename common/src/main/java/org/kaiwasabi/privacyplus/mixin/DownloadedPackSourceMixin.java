package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.resources.server.DownloadedPackSource;
import org.kaiwasabi.privacyplus.stealth.ServerPackLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Leaving a server drops its resource packs (the resource reload that follows notices that too, but
// only once it has finished)
@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {
    @Inject(method = "cleanupAfterDisconnect", at = @At("HEAD"))
    private void privacyplus$forgetServerTranslations(CallbackInfo ci) {
        ServerPackLanguage.forget();
    }
}
