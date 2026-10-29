package org.kaiwasabi.privacyplus.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogScrubberTest {
    private static final UUID ACCOUNT = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final String ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNSJ9.c2lnbmF0dXJlLXZhbHVl";
    private static final String OTHER_UUID = "11e147a4-5282-4787-99b3-789a3a9b2ee4";

    private static final LogScrubber MAC = new LogScrubber(new Identity(Identity.homeSpellings("/Users/alice", null), "alice",
        Set.of(), Set.of(), Set.of()).withAccount("Wasabi_Fan", ACCOUNT, Arrays.asList(ACCESS_TOKEN, "2535412345678901", null)));
    private static final LogScrubber WINDOWS = new LogScrubber(new Identity(
        Identity.homeSpellings("C:\\Users\\Bob Smith", "C:\\Users\\BOBSMI~1\\AppData\\Local\\Temp\\"), "Bob Smith",
        Set.of(), Set.of(), Set.of()));

    @Test
    void homeFolderBecomesTilde() {
        assertEquals("Loading mods from ~/Library/minecraft/mods", MAC.clean("Loading mods from /Users/alice/Library/minecraft/mods"));
        assertEquals("Source=file:~/.gradle/x.jar Service=Knot", MAC.clean("Source=file:/Users/alice/.gradle/x.jar Service=Knot"));
        assertEquals("in ~", MAC.clean("in /Users/alice"));
        assertEquals("~/x", MAC.clean("/Users/ALICE/x"), "paths are matched in any case");
        assertEquals("/Users/alicex/y", MAC.clean("/Users/alicex/y"), "another folder that only starts the same");
    }

    @Test
    void windowsHomeInEverySpelling() {
        assertEquals("~\\AppData\\Roaming\\.minecraft", WINDOWS.clean("C:\\Users\\Bob Smith\\AppData\\Roaming\\.minecraft"));
        assertEquals("~/AppData/Roaming/.minecraft", WINDOWS.clean("C:/Users/Bob Smith/AppData/Roaming/.minecraft"));
        assertEquals("\"~\\\\AppData\"", WINDOWS.clean("\"C:\\\\Users\\\\Bob Smith\\\\AppData\""), "escaped in JSON");
        assertEquals("file:///~/mods/a.jar", WINDOWS.clean("file:///C:/Users/Bob%20Smith/mods/a.jar"), "URL-encoded");
        assertEquals("~\\AppData\\Local\\Temp\\lwjgl", WINDOWS.clean("C:\\Users\\BOBSMI~1\\AppData\\Local\\Temp\\lwjgl"), "8.3 short name");
        assertEquals("~\\x", WINDOWS.clean("c:\\users\\bob smith\\x"), "drive letter and folder in any case");
    }

    @Test
    void computerUserNameAsWholeWordOnly() {
        assertEquals("logged in as <user> on this computer", MAC.clean("logged in as alice on this computer"));
        assertEquals("malice and alicehouse", MAC.clean("malice and alicehouse"));
        assertEquals("Alice", MAC.clean("Alice"), "the user name is case-sensitive");
        assertEquals("<user>'s computer", WINDOWS.clean("Bob Smith's computer"));
    }

    @Test
    void shortOrCommonUserNamesAreKept() {
        for (String user : List.of("al", "admin", "user", "Player", "root")) {
            LogScrubber scrubber = new LogScrubber(new Identity(List.of(), user, Set.of(), Set.of(), Set.of()));
            String line = "Setting " + user + " up for admin and user and root, al";
            assertEquals(line, scrubber.clean(line), user);
        }
    }

    @Test
    void signedInAccountNameAndUuid() {
        assertEquals("Setting user: <player>", MAC.clean("Setting user: Wasabi_Fan"));
        assertEquals("[CHAT] <<player>> hi, <player>!", MAC.clean("[CHAT] <Wasabi_Fan> hi, wasabi_fan!"));
        assertEquals("<player>[local:E:1a2b] logged in", MAC.clean("Wasabi_Fan[local:E:1a2b] logged in"));
        assertEquals("--uuid <uuid> --uuid <uuid> --uuid <uuid>",
            MAC.clean("--uuid " + ACCOUNT + " --uuid 0f1e2d3c4b5a69788796a5b4c3d2e1f0 --uuid " + ACCOUNT.toString().toUpperCase()));
    }

    @Test
    void otherPlayersAndUuidsAreKept() {
        String chat = "[CHAT] <Other_Player> plain text sanity check " + OTHER_UUID + " for Wasabi_Fan2 and xWasabi_Fan";
        assertEquals(chat, MAC.clean(chat));
        String entity = "Entity 0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f1 and 0f1e2d3c4b5a69788796a5b4c3d2e1f0aa";
        assertEquals(entity, MAC.clean(entity), "a different UUID, and a longer hex string containing the account's");
    }

    @Test
    void accountNamedLikeACommonWordOnlyInItsOwnCase() {
        LogScrubber scrubber = new LogScrubber(new Identity(List.of(), null, Set.of(), Set.of(), Set.of())
            .withAccount("Steve", null, List.of()));
        assertEquals("Setting user: <player>, default skin steve", scrubber.clean("Setting user: Steve, default skin steve"));
    }

    @Test
    void tokens() {
        assertEquals("(Session ID is <token>)", MAC.clean("(Session ID is token:" + ACCESS_TOKEN + ":0f1e2d3c4b5a69788796a5b4c3d2e1f0)"));
        assertEquals("(Session ID is <token>)", MAC.clean("(Session ID is token:0:0123456789abcdef0123456789abcdef)"),
            "even a dev token, and any account's UUID");
        assertEquals("Authorization: Bearer <token>",
            MAC.clean("Authorization: Bearer eyJ0eXAiOiJKV1QifQ.eyJzdWIiOiJzb21lb25lIn0.SflKxwRJSMeKKF2QT4fwpM"));
        assertEquals("--accessToken <token> --version 26.3", MAC.clean("--accessToken abcdef1234567890 --version 26.3"));
        assertEquals("args [--username, <player>, --accessToken, <token>, --xuid, <token>, --clientId, <token>]", MAC.clean(
            "args [--username, Wasabi_Fan, --accessToken, ❄❄❄❄❄❄❄❄, --xuid, 2535412345678901, --clientId, ZTVlNDNjZjQtMjU1]"));
        assertEquals("{\"access_token\":\"<token>\",\"expires_in\":86400}",
            MAC.clean("{\"access_token\":\"EwB4A8l6BAAU-x.y\",\"expires_in\":86400}"));
        assertEquals("Authorization: XBL3.0 x=<token>", MAC.clean("Authorization: XBL3.0 x=1234567890123;eyJ0b2tlbiI6MX0abcdef"));
        assertEquals("token <token> leaked", MAC.clean("token " + ACCESS_TOKEN + " leaked"), "the account's own token anywhere");
        assertEquals("xuid=<token>", MAC.clean("xuid=2535412345678901"));
        assertEquals("textures=<token>", MAC.clean("textures=eyJ0aW1lc3RhbXAiOjE2MDAwMDAwMDAsInByb2ZpbGVJZCI6IjEyMyJ9"),
            "base64 JSON such as the skin property, which holds the profile name");
    }

    @Test
    void noFalseTokens() {
        for (String line : List.of("Invalid token: none given", "Failed to refresh token: timed out", "Bearer of bad news",
            "Received 3 tokens from the access token cache", "keyed by clientId 42")) {
            assertEquals(line, MAC.clean(line));
        }
    }

    @Test
    void publicIpAddresses() {
        assertEquals("Connecting to <ip>, 25565", MAC.clean("Connecting to 93.184.216.34, 25565"));
        assertEquals("dns <ip>:53 and <ip>.", MAC.clean("dns 8.8.8.8:53 and 1.1.1.1."));
        assertEquals("[<ip>]:443", MAC.clean("[2a00:1450:4001:82a::200e]:443"));
        assertEquals("at <ip> now", MAC.clean("at 2606:4700:4700::1111 now"));
    }

    @Test
    void localAddressesAndVersionsAreKept() {
        for (String line : List.of("127.0.0.1:25602", "192.168.1.20", "10.0.0.5", "172.16.4.1", "100.64.0.1", "169.254.1.1",
            "0.0.0.0:25565", "255.255.255.255", "224.0.0.251", "::1", "fe80::1%en0", "[::ffff:127.0.0.1]", "2001:db8::1",
            "Minecraft 1.21.4", "Version 26.3", "lwjgl 3.4.3-snapshot", "mod 1.0.0.0", "Windows 10.0.19045.1234",
            "netty 4.2.7.Final", "v1.2.3.4", "1.2.3.4-SNAPSHOT", "time 12:34:56", "MAC aa:bb:cc:dd:ee:ff", "std::vector")) {
            assertEquals(line, MAC.clean(line));
        }
    }

    @Test
    void maskKeepsTheByteLength() {
        assertEquals("Setting user: **********", MAC.mask("Setting user: Wasabi_Fan", StandardCharsets.UTF_8));
        LogScrubber umlaut = new LogScrubber(new Identity(Identity.homeSpellings("/home/jürgen", null), "jürgen",
            Set.of(), Set.of(), Set.of()));
        String line = "[12:00:00] [main/INFO]: Loading /home/jürgen/mods for jürgen from 93.184.216.34";
        String masked = umlaut.mask(line, StandardCharsets.UTF_8);
        assertFalse(masked.contains("jürgen") || masked.contains("93.184"), masked);
        assertEquals(line.getBytes(StandardCharsets.UTF_8).length, masked.getBytes(StandardCharsets.UTF_8).length);
        String latin1 = umlaut.mask(line, StandardCharsets.ISO_8859_1);
        assertEquals(line.getBytes(StandardCharsets.ISO_8859_1).length, latin1.getBytes(StandardCharsets.ISO_8859_1).length);
    }

    @Test
    void cleaningIsStable() {
        String line = "Setting user: Wasabi_Fan from /Users/alice at 93.184.216.34 with token:abc:0f1e2d3c4b5a69788796a5b4c3d2e1f0";
        String once = MAC.clean(line);
        assertEquals(once, MAC.clean(once));
        assertTrue(once.contains("<player>") && once.contains("~") && once.contains("<ip>") && once.contains("<token>"), once);
    }
}
