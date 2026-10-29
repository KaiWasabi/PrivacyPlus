package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.jspecify.annotations.Nullable;
import org.kaiwasabi.privacyplus.stealth.ServerPingGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Selecting a server (click or arrow keys) is the player asking for its status
@Mixin(ServerSelectionList.class)
public abstract class ServerSelectionListMixin {
    @Inject(method = "setSelected(Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList$Entry;)V", at = @At("HEAD"))
    private void privacyplus$pingWhenSelected(ServerSelectionList.@Nullable Entry entry, CallbackInfo ci) {
        if (entry instanceof ServerSelectionList.OnlineServerEntry server) {
            ServerPingGate.approve(server.getServerData());
        }
    }
}
