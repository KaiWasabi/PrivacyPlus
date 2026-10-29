package org.kaiwasabi.privacyplus.guard;

import static org.kaiwasabi.privacyplus.guard.TransferGuard.decide;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.resources.Identifier;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig.CookieGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TransferGuardTest {
    private static TransferGuard.Decision decision(boolean sameSite, boolean passCookies, String site) {
        return new TransferGuard.Decision(sameSite, passCookies, site);
    }

    @AfterEach
    void reset() {
        TransferGuard.loginStarted(false);
    }

    @Test
    void sameSiteKeepsCookies() {
        assertEquals(decision(true, true, "example.com"), decide(CookieGuard.SAME_SITE, "play.example.com", "lobby.example.com"));
        assertEquals(decision(true, true, "example.com"), decide(CookieGuard.SAME_SITE, "example.com", "EU.Example.com"));
        assertEquals(decision(true, true, "example.co.uk"), decide(CookieGuard.SAME_SITE, "mc.example.co.uk", "hub.example.co.uk"));
        assertEquals(decision(true, true, "203.0.113.7"), decide(CookieGuard.SAME_SITE, "203.0.113.7", "203.0.113.7"));
    }

    @Test
    void otherSitesDontGetCookies() {
        assertEquals(decision(false, false, "evil.net"), decide(CookieGuard.SAME_SITE, "play.example.com", "mc.evil.net"));
        assertEquals(decision(false, false, "127.0.0.1"), decide(CookieGuard.SAME_SITE, "localhost", "127.0.0.1"));
        assertEquals(decision(false, false, "other.co.uk"), decide(CookieGuard.SAME_SITE, "mc.example.co.uk", "other.co.uk"));
        assertEquals(decision(false, false, "203.0.113.8"), decide(CookieGuard.SAME_SITE, "203.0.113.7", "203.0.113.8"));
        assertEquals(decision(false, false, "::1"), decide(CookieGuard.SAME_SITE, "example.com", "::1"));
    }

    @Test
    void neverAndAllow() {
        assertEquals(decision(true, false, "example.com"), decide(CookieGuard.NEVER, "play.example.com", "lobby.example.com"));
        assertEquals(decision(false, true, "evil.net"), decide(CookieGuard.ALLOW, "play.example.com", "evil.net"));
    }

    @Test
    void filteredStateKeepsEverythingElse() {
        Map<UUID, PlayerInfo> seen = Map.of();
        TransferState state = new TransferState(Map.of(Identifier.fromNamespaceAndPath("example", "id"), new byte[]{1, 2, 3}), seen, true);
        assertSame(state, TransferGuard.withCookies(state, true), "vanilla's own state when cookies go along");
        TransferState filtered = TransferGuard.withCookies(state, false);
        assertTrue(filtered.cookies().isEmpty());
        assertSame(seen, filtered.seenPlayers());
        assertTrue(filtered.seenInsecureChatWarning());
    }

    @Test
    void currentServerFollowsTransfers() {
        String joined = "play.example.com";
        TransferGuard.loginStarted(false);
        assertEquals("play.example.com", TransferGuard.currentServer(joined));

        // play.example.com sends you to mc.evil.net; ServerData still names play.example.com there
        TransferGuard.noteTransfer("mc.evil.net:25565");
        TransferGuard.loginStarted(true);
        assertEquals("mc.evil.net:25565", TransferGuard.currentServer(joined));

        // An ordinary join afterwards starts over
        TransferGuard.loginStarted(false);
        assertEquals("play.example.com", TransferGuard.currentServer(joined));
    }
}
