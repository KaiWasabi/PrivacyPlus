package org.kaiwasabi.privacyplus.mixin;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.resources.Identifier;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.guard.TransferGuard;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The client sends its brand ("fabric", "forge") to every server right after login.
// Only the network send is changed; the local "Modded" label and crash reports stay honest.
@Mixin(ClientHandshakePacketListenerImpl.class)
public abstract class ClientHandshakePacketListenerImplMixin {
    @Shadow
    @Final
    private Map<Identifier, byte[]> cookies;
    @Shadow
    @Final
    private @Nullable ServerData serverData;
    @Shadow
    @Final
    private boolean wasTransferredTo;
    @Shadow
    @Final
    private Connection connection;
    @Shadow
    @Final
    private Consumer<Component> updateStatus;

    @Redirect(
        method = "handleLoginFinished",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ClientBrandRetriever;getClientModName()Ljava/lang/String;")
    )
    private String privacyplus$sendVanillaBrand() {
        return PrivacyPlusConfig.spoofClientBrand ? ClientBrandRetriever.VANILLA_NAME : ClientBrandRetriever.getClientModName();
    }

    // During login (network thread). Cookies here can only come from the server that transferred you,
    // so a hit means two servers are working together to recognise you.
    @Inject(method = "handleRequestCookie", at = @At("HEAD"))
    private void privacyplus$noticeCookieRequest(ClientboundCookieRequestPacket packet, CallbackInfo ci) {
        ProbeAlarm.report(ProbeAlarm.Kind.COOKIE_REQUEST, packet.key()
            + (this.cookies.containsKey(packet.key()) ? ", answered with a cookie from the previous server" : ", none stored"), this.serverData);
    }

    // Login queries are how mod loaders and anti-cheat plugins ask a client which mods it has: vanilla servers
    // never send one, and a vanilla client answers every one with "not understood". Forge and Fabric API answer
    // the ones for their own channels, so with hideModChannels this answers like vanilla before they see it.
    // (Any answer a mod sends anyway, ChannelShield holds back.) Login packets arrive on the network thread.
    @Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
    private void privacyplus$answerQueryLikeVanilla(ClientboundCustomQueryPacket packet, CallbackInfo ci) {
        if (this.connection.isMemoryConnection()) {
            return;
        }
        // With hiding off, mod handshakes are expected (a modded server you chose to join), not a probe
        if (PrivacyPlusConfig.hideModChannels) {
            ProbeAlarm.report(ProbeAlarm.Kind.LOGIN_QUERY, "login query " + packet.payload().id(), this.serverData);
            // Exactly what vanilla's handleCustomQuery does (its status line is vanilla's own text)
            this.updateStatus.accept(Component.translatable("connect.negotiating"));
            this.connection.send(new ServerboundCustomQueryAnswerPacket(packet.transactionId(), null));
            ci.cancel();
        }
    }

    // Every login starts here; TransferGuard needs to know which server you're on when the next transfer comes
    @Inject(method = "<init>", at = @At("TAIL"))
    private void privacyplus$noteLogin(CallbackInfo ci) {
        TransferGuard.loginStarted(this.wasTransferredTo);
    }
}
