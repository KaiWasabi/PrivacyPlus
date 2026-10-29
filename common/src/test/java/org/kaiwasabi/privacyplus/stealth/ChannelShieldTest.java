package org.kaiwasabi.privacyplus.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.DiscardedQueryAnswerPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.KnownPack;
import org.junit.jupiter.api.Test;

class ChannelShieldTest {
    private final Set<Integer> unansweredQueries = new HashSet<>();

    private Packet<?> filter(Packet<?> packet, boolean hideChannels, boolean spoofBrand) {
        return ChannelShield.filter(packet, hideChannels, spoofBrand, this.unansweredQueries);
    }

    private static Packet<?> payload(String id) {
        return new ServerboundCustomPayloadPacket(new DiscardedPayload(Identifier.parse(id)));
    }

    private static Packet<?> brand(String brand) {
        return new ServerboundCustomPayloadPacket(new BrandPayload(brand));
    }

    @Test
    void modChannelsAreHeldBack() {
        // What Fabric API and Forge send: channel registrations and their own handshakes
        for (String id : List.of("minecraft:register", "minecraft:unregister", "c:register", "c:version", "fabric:registry/sync/direct",
            "forge:handshake", "forge:split", "voicechat:secret_request")) {
            assertNull(filter(payload(id), true, true), id);
        }
    }

    @Test
    void modChannelsPassWhenNotHidden() {
        Packet<?> register = payload("minecraft:register");
        assertSame(register, filter(register, false, true));
    }

    @Test
    void vanillaBrandAlwaysGoesThrough() {
        Packet<?> vanilla = brand("vanilla");
        assertSame(vanilla, filter(vanilla, true, true));
        assertSame(vanilla, filter(vanilla, true, false));
        assertSame(vanilla, filter(vanilla, false, false));
    }

    @Test
    void modBrandIsMadeVanillaOnlyWhenSpoofing() {
        Packet<?> fabric = brand("fabric");
        Packet<?> sent = filter(fabric, true, true);
        assertEquals(brand("vanilla"), sent);
        assertSame(sent, filter(sent, true, true), "the replacement passes unchanged, so filtering twice is harmless");
        assertSame(fabric, filter(fabric, true, false), "brand spoofing off: the real brand goes out");
    }

    @Test
    void eachLoginQueryGetsOneNotUnderstood() {
        unansweredQueries.addAll(List.of(5, 6));
        Packet<?> vanillaAnswer = new ServerboundCustomQueryAnswerPacket(5, null);
        assertSame(vanillaAnswer, filter(vanillaAnswer, true, true), "vanilla's own answer goes out");
        assertNull(filter(new ServerboundCustomQueryAnswerPacket(5, DiscardedQueryAnswerPayload.INSTANCE), true, true),
            "a mod answering the same query again is held back");

        Packet<?> sent = filter(new ServerboundCustomQueryAnswerPacket(6, DiscardedQueryAnswerPayload.INSTANCE), true, true);
        assertEquals(new ServerboundCustomQueryAnswerPacket(6, null), sent, "a mod that answered first: same transaction, no payload");
        assertSame(sent, filter(sent, true, true), "the replacement passes when it comes back through");
        assertTrue(unansweredQueries.isEmpty());

        assertNull(filter(new ServerboundCustomQueryAnswerPacket(0, DiscardedQueryAnswerPayload.INSTANCE), true, true),
            "an answer to a query never asked (Forge's login wrapper) is held back");
    }

    @Test
    void modLoginAnswersPassWhenNotHidden() {
        unansweredQueries.add(1);
        Packet<?> modAnswer = new ServerboundCustomQueryAnswerPacket(1, DiscardedQueryAnswerPayload.INSTANCE);
        assertSame(modAnswer, filter(modAnswer, false, true));
        assertTrue(unansweredQueries.isEmpty(), "still marked as answered");
    }

    @Test
    void otherPacketsAreUntouched() {
        Packet<?> keepAlive = new ServerboundKeepAlivePacket(123L);
        assertSame(keepAlive, filter(keepAlive, true, true));
    }

    @Test
    void onlyVanillaDataPacksAreKnown() {
        KnownPack core = new KnownPack("minecraft", "core", "26.3");
        KnownPack feature = new KnownPack("minecraft", "trade_rebalance", "26.3");
        KnownPack mod = new KnownPack("fabric", "fabric-api", "0.140.0");
        assertEquals(List.of(core, feature), ChannelShield.vanillaKnownPacks(List.of(core, mod, feature)));
        assertEquals(List.of(core), ChannelShield.vanillaKnownPacks(List.of(core)));
    }
}
