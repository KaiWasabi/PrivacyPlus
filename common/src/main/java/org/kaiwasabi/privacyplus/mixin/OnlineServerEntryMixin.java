package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.kaiwasabi.privacyplus.stealth.ServerPingGate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    @Final
    private ServerData serverData;
    @Shadow
    private @Nullable Identifier statusIcon;
    @Shadow
    private @Nullable Component statusIconTooltip;

    // extractContent starts the ping when the first state() check sees INITIAL. Reporting any
    // other state there skips the ping; later checks in the method still see the real state.
    @Redirect(
        method = "extractContent",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ServerData;state()Lnet/minecraft/client/multiplayer/ServerData$State;",
            ordinal = 0
        )
    )
    private ServerData.State privacyplus$holdPing(ServerData server) {
        ServerData.State state = server.state();
        if (state == ServerData.State.INITIAL && !ServerPingGate.mayPing(server)) {
            server.motd = ServerPingGate.NOT_PINGED;
            // Vanilla clears status when the ping starts; the rest of this method assumes it isn't null
            server.status = CommonComponents.EMPTY;
            return ServerData.State.PINGING;
        }
        return state;
    }

    // Vanilla shows a one-bar "Pinging..." icon for servers that haven't been pinged yet
    @Inject(method = "refreshStatus", at = @At("TAIL"))
    private void privacyplus$noIconUntilPinged(CallbackInfo ci) {
        if (this.serverData.state() == ServerData.State.INITIAL && !ServerPingGate.mayPing(this.serverData)) {
            this.statusIcon = null;
            this.statusIconTooltip = null;
        }
    }
}
