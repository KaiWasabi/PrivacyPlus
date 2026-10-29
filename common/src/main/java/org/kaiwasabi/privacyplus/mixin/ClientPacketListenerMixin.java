package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// What No Chat Reports does on the client: without a profile key pair the client never starts a
// chat session, so messages and command arguments go out unsigned and can't be reported to Mojang.
// Only on servers the player opted in, since the server can see that chat is unsigned.
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Shadow
    public abstract @Nullable ServerData getServerData();

    @Inject(method = "prepareKeyPair", at = @At("HEAD"), cancellable = true)
    private void privacyplus$unsignedChat(CallbackInfo ci) {
        ServerData server = this.getServerData();
        if (server != null && ChatSettings.isUnsigned(server.ip)) {
            ci.cancel();
        }
    }
}
