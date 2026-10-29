package org.kaiwasabi.privacyplus.mixin;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.resources.Identifier;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.guard.TransferGuard;
import org.kaiwasabi.privacyplus.stealth.PackHostGuard;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin {
    @Shadow
    @Final
    protected Minecraft minecraft;
    @Shadow
    @Final
    protected Map<Identifier, byte[]> serverCookies;
    @Shadow
    @Final
    protected @Nullable ServerData serverData;

    // Cookies survive transfers to other servers: a way for servers to recognise you across them.
    // TAIL runs once, on the render thread (the network-thread call stops at ensureRunningOnSameThread).
    @Inject(method = "handleStoreCookie", at = @At("TAIL"))
    private void privacyplus$noticeStoredCookie(ClientboundStoreCookiePacket packet, CallbackInfo ci) {
        ProbeAlarm.report(ProbeAlarm.Kind.COOKIE_STORE, packet.key() + ", " + packet.payload().length + " bytes", this.serverData);
    }

    @Inject(method = "handleRequestCookie", at = @At("TAIL"))
    private void privacyplus$noticeCookieRequest(ClientboundCookieRequestPacket packet, CallbackInfo ci) {
        ProbeAlarm.report(ProbeAlarm.Kind.COOKIE_REQUEST, packet.key()
            + (this.serverCookies.containsKey(packet.key()) ? ", answered with a stored cookie" : ", none stored"), this.serverData);
    }

    // Reported before the client disconnects, while it still knows which server sent it
    @Inject(
        method = "handleTransfer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;disconnect(Lnet/minecraft/network/chat/Component;)V")
    )
    private void privacyplus$noticeTransfer(ClientboundTransferPacket packet, CallbackInfo ci) {
        ProbeAlarm.report(ProbeAlarm.Kind.TRANSFER, packet.host() + ":" + packet.port(), this.serverData);
    }

    // After vanilla has left the old server: connect on only as transferGuard says, with the cookies
    // cookieGuard lets through (TransferGuard). Vanilla always connects, taking every cookie along.
    @Redirect(
        method = "handleTransfer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/ConnectScreen;startConnecting("
            + "Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;"
            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;"
            + "ZLnet/minecraft/client/multiplayer/TransferState;)V")
    )
    private void privacyplus$guardTransfer(Screen parent, Minecraft mc, ServerAddress target, ServerData data, boolean quickPlay,
                                           TransferState state) {
        TransferGuard.onTransfer(parent, mc, target, data, quickPlay, state);
    }

    // Runs for every pushed pack while connected to a saved or direct-connect server. Packs from a
    // server you accepted packs from before normally download without asking; one hosted on
    // another site gets the prompt anyway.
    @Redirect(
        method = "handleResourcePackPush",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ServerData;getResourcePackStatus()Lnet/minecraft/client/multiplayer/ServerData$ServerPackStatus;")
    )
    private ServerData.ServerPackStatus privacyplus$checkPackHost(ServerData server, ClientboundResourcePackPushPacket packet) {
        ServerData.ServerPackStatus status = server.getResourcePackStatus();
        URL url;
        try {
            // Parsed exactly like vanilla does just before, so the URLs compare equal at download time
            url = new URL(packet.url());
        } catch (MalformedURLException e) {
            return status;
        }
        boolean thirdParty = PackHostGuard.noteServerPack(server.ip, url);
        if (thirdParty) {
            ProbeAlarm.report(ProbeAlarm.Kind.PACK, url.getHost(), server);
        }
        if (thirdParty && status == ServerData.ServerPackStatus.ENABLED && PrivacyPlusConfig.askBeforeThirdPartyPacks) {
            return ServerData.ServerPackStatus.PROMPT;
        }
        return status;
    }

    // Adds which site the pack comes from to vanilla's "use this server's resource pack?" prompt
    @Inject(method = "addOrUpdatePackPrompt", at = @At("RETURN"))
    private void privacyplus$namePackHosts(UUID id, URL url, String hash, boolean required, @Nullable Component prompt,
                                           CallbackInfoReturnable<Screen> cir) {
        // The returned prompt isn't shown yet; if a pack prompt is already open, this one extends it
        Screen open = this.minecraft.gui.screen();
        boolean newPrompt = open == null || open.getClass() != cir.getReturnValue().getClass();
        Set<String> hosts = PackHostGuard.promptHosts(newPrompt, url);
        if (!hosts.isEmpty() && cir.getReturnValue() instanceof ConfirmScreen confirm) {
            ConfirmScreenAccessor accessor = (ConfirmScreenAccessor) confirm;
            accessor.privacyplus$setMessage(accessor.privacyplus$getMessage().copy().append(PackHostGuard.promptNote(hosts)));
        }
    }
}
