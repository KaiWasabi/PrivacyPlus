package org.kaiwasabi.privacyplus.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import org.kaiwasabi.privacyplus.stealth.ChannelShield;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// sendPacket is where every packet goes out, whatever the phase (login, configuration, play) and whoever
// sends it: vanilla, Fabric API and Forge all call send(), which ends here (directly, or from the queue of
// packets sent before the connection was up). Only the client's connections to real servers are filtered;
// the integrated server's connections, and the client's in-memory one to it, stay untouched.
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    // Login queries the server sent that haven't been answered yet (added on the network thread)
    @Unique
    private final Set<Integer> privacyplus$unansweredQueries = ConcurrentHashMap.newKeySet();

    @Shadow
    private Channel channel;

    @Shadow
    public abstract PacketFlow getSending();

    @Shadow
    public abstract boolean isMemoryConnection();

    @Shadow
    private void sendPacket(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
    }

    // Noted before any listener, or a mod's hook in one, gets to answer it
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void privacyplus$noteLoginQuery(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundCustomQueryPacket query && this.privacyplus$toRealServer()) {
            this.privacyplus$unansweredQueries.add(query.transactionId());
        }
    }

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void privacyplus$sendLikeVanilla(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (!this.privacyplus$toRealServer()) {
            return;
        }
        Packet<?> sent = ChannelShield.outgoing(packet, this.privacyplus$unansweredQueries);
        if (sent == packet) {
            return;
        }
        ci.cancel();
        if (sent != null) {
            // Comes back through here and passes: replacements are already vanilla
            this.sendPacket(sent, listener, flush);
        } else if (listener != null) {
            // Whoever sent it may wait to hear it went out; it "did"
            this.channel.newSucceededFuture().addListener(listener);
        }
    }

    @Unique
    private boolean privacyplus$toRealServer() {
        return this.getSending() == PacketFlow.SERVERBOUND && !this.isMemoryConnection();
    }
}
