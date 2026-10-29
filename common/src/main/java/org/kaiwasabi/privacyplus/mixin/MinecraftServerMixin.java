package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Incognito: in singleplayer (and when hosting a LAN world) the built-in server also writes every chat
// line to latest.log. Client-only mixin, so it never touches real servers.
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "logChatMessage", at = @At("HEAD"), cancellable = true)
    private void privacyplus$incognitoLog(Component message, ChatType.Bound chatType, @Nullable String tag, CallbackInfo ci) {
        if (PrivacyPlusConfig.incognito) {
            ci.cancel();
        }
    }
}
