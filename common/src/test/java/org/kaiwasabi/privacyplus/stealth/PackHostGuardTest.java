package org.kaiwasabi.privacyplus.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PackHostGuardTest {
    private static final Map<String, String> VANILLA_HEADERS = Map.of(
        "X-Minecraft-Username", "KaiWasabi",
        "X-Minecraft-UUID", "0123456789abcdef0123456789abcdef",
        "X-Minecraft-Version", "26.3",
        "User-Agent", "Minecraft Java/26.3"
    );

    private static URL url(String spec) throws Exception {
        return URI.create(spec).toURL();
    }

    @AfterEach
    void resetConfig() {
        PrivacyPlusConfig.hidePackIdentity = true;
    }

    @Test
    void sharedHostSubdomainsAreSeparateSites() {
        assertFalse(PackHostGuard.sameSite("alice.aternos.me", "bob.aternos.me"), "different Aternos customers");
        assertTrue(PackHostGuard.sameSite("play.alice.aternos.me", "alice.aternos.me"));
        assertFalse(PackHostGuard.sameSite("x.ply.gg", "y.ply.gg"));
        assertTrue(PackHostGuard.sameSite("play.example.com", "lobby.example.com"), "ordinary sites unchanged");
    }

    @Test
    void sameSiteRules() {
        assertTrue(PackHostGuard.sameSite("play.example.com", "play.example.com"));
        assertTrue(PackHostGuard.sameSite("example.com", "packs.example.com"), "subdomain of the server's site");
        assertTrue(PackHostGuard.sameSite("mc.example.co.uk", "cdn.example.co.uk"));
        assertTrue(PackHostGuard.sameSite("PLAY.Example.com.", "play.example.com"), "case and trailing dot");
        assertTrue(PackHostGuard.sameSite("203.0.113.7", "203.0.113.7"));

        assertFalse(PackHostGuard.sameSite("play.example.com", "mc-packs.net"));
        assertFalse(PackHostGuard.sameSite("mc.example.co.uk", "evil.co.uk"), "co.uk is not a site");
        assertFalse(PackHostGuard.sameSite("localhost", "127.0.0.1"));
        assertFalse(PackHostGuard.sameSite("203.0.113.7", "203.0.113.8"));
        assertFalse(PackHostGuard.sameSite("example.com", null));
    }

    @Test
    void firstPartyPacksKeepVanillaHeaders() throws Exception {
        URL pack = url("https://packs.example.com/server.zip");
        assertFalse(PackHostGuard.noteServerPack("play.example.com:25565", pack));
        assertSame(VANILLA_HEADERS, PackHostGuard.headersFor(pack, VANILLA_HEADERS), "looks exactly like vanilla");
    }

    @Test
    void thirdPartyPacksLoseIdentityHeaders() throws Exception {
        URL pack = url("https://download.mc-packs.net/pack/abc123.zip");
        assertTrue(PackHostGuard.noteServerPack("play.example.com", pack));
        Map<String, String> sent = PackHostGuard.headersFor(pack, VANILLA_HEADERS);
        assertFalse(sent.containsKey("X-Minecraft-Username"));
        assertFalse(sent.containsKey("X-Minecraft-UUID"));
        assertEquals("26.3", sent.get("X-Minecraft-Version"), "version headers stay so hosts can pick the right pack");
    }

    @Test
    void identityKeptWhenDisabled() throws Exception {
        PrivacyPlusConfig.hidePackIdentity = false;
        URL pack = url("https://other-host.example.org/pack.zip");
        PackHostGuard.noteServerPack("play.example.com", pack);
        assertSame(VANILLA_HEADERS, PackHostGuard.headersFor(pack, VANILLA_HEADERS));
    }

    @Test
    void promptNamesEachThirdPartyHostOnce() throws Exception {
        URL first = url("https://cdn-one.example.net/a.zip");
        URL second = url("https://cdn-two.example.org/b.zip");
        URL own = url("https://example.com/c.zip");
        PackHostGuard.noteServerPack("example.com", first);
        PackHostGuard.noteServerPack("example.com", second);
        PackHostGuard.noteServerPack("example.com", own);

        PackHostGuard.promptHosts(true, first);
        PackHostGuard.promptHosts(false, own);
        String note = PackHostGuard.promptNote(PackHostGuard.promptHosts(false, second)).getString();
        assertTrue(note.contains("cdn-one.example.net, cdn-two.example.org, not from this server"), note);
        assertTrue(PackHostGuard.promptHosts(true, own).isEmpty(), "a new prompt starts fresh");
    }
}
