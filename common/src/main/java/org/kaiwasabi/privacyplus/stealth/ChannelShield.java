package org.kaiwasabi.privacyplus.stealth;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.ClientBrandRetriever;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.KnownPack;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// Mods give themselves away in what the client sends: channel registrations (minecraft:register,
// c:register), handshakes on their own channels (c:version, fabric:*, forge:*), answers to login queries
// and the data packs they report as already known. A vanilla client only ever sends its brand as a custom
// payload, answers each login query once with "not understood" and knows only vanilla's own data packs, so
// with hideModChannels that is all a server gets. Only real servers are shielded: your own single-player
// world (an in-memory connection) is left alone, so mods that need their server side keep working there.
public final class ChannelShield {
    private static final Identifier BRAND = BrandPayload.TYPE.id();
    private static final AtomicInteger CHANGED = new AtomicInteger();

    private ChannelShield() {
    }

    // Called for every packet a client sends to a real server. Returns what to send instead: the packet
    // itself, a vanilla replacement, or null for nothing. unansweredQueries: the connection's login queries
    // still waiting for an answer.
    public static @Nullable Packet<?> outgoing(Packet<?> packet, Set<Integer> unansweredQueries) {
        Packet<?> sent = filter(packet, PrivacyPlusConfig.hideModChannels, PrivacyPlusConfig.spoofClientBrand, unansweredQueries);
        if (sent != packet) {
            int total = CHANGED.incrementAndGet();
            String what = packet instanceof ServerboundCustomPayloadPacket(CustomPacketPayload payload)
                ? "custom payload " + payload.type().id()
                : packet.type().toString();
            PrivacyPlus.LOG.debug("{} {} ({} this session)", sent == null ? "Held back" : "Made vanilla", what, total);
        }
        return sent;
    }

    // A replacement passes unchanged when filtered again, so it can go back through the same send path
    static @Nullable Packet<?> filter(Packet<?> packet, boolean hideChannels, boolean spoofBrand, Set<Integer> unansweredQueries) {
        if (packet instanceof ServerboundCustomPayloadPacket(CustomPacketPayload payload)) {
            if (payload.type().id().equals(BRAND)) {
                // Vanilla's own brand packet is already spoofed where it's built; this catches any other
                boolean vanilla = payload instanceof BrandPayload(String brand) && brand.equals(ClientBrandRetriever.VANILLA_NAME);
                return spoofBrand && !vanilla
                    ? new ServerboundCustomPayloadPacket(new BrandPayload(ClientBrandRetriever.VANILLA_NAME))
                    : packet;
            }
            return hideChannels ? null : packet;
        }
        if (packet instanceof ServerboundCustomQueryAnswerPacket answer) {
            boolean awaited = unansweredQueries.remove(answer.transactionId());
            if (!hideChannels || answer.payload() == null) {
                return packet;
            }
            // A mod's answer: the server gets "not understood" if it still waits for one, otherwise nothing
            // (a second answer, or one it never asked for, isn't something a vanilla client sends)
            return awaited ? new ServerboundCustomQueryAnswerPacket(answer.transactionId(), null) : null;
        }
        return packet;
    }

    // The data packs a server offers as "you may already have these" and that the client then says it has.
    // A vanilla client only has vanilla's own (namespace minecraft); a loader may add mods' packs.
    public static List<KnownPack> knownPacks(List<KnownPack> offered) {
        return PrivacyPlusConfig.hideModChannels ? vanillaKnownPacks(offered) : offered;
    }

    static List<KnownPack> vanillaKnownPacks(List<KnownPack> offered) {
        return offered.stream().filter(KnownPack::isVanilla).toList();
    }

    // Packets held back or made vanilla this session
    public static int changedCount() {
        return CHANGED.get();
    }
}
