package org.kaiwasabi.privacyplus.forge.mixin;

import net.minecraftforge.network.NetworkContext;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Forge appends "\0FORGE" to the server address in the handshake packet of every outgoing
// connection (and status ping), which tells any server it's talking to a Forge client.
@Mixin(NetworkContext.class)
public abstract class NetworkContextMixin {
    @Inject(method = "enhanceHostName", at = @At("HEAD"), cancellable = true)
    private static void privacyplus$noForgeMarker(String hostName, CallbackInfoReturnable<String> cir) {
        // Hiding mod channels drops Forge's handshake replies, so a Forge server seeing the marker would
        // wait for them until it times out
        if (PrivacyPlusConfig.stripForgeHandshakeMarker || PrivacyPlusConfig.hideModChannels) {
            cir.setReturnValue(hostName);
        }
    }
}
