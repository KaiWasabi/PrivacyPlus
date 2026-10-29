package org.kaiwasabi.privacyplus.guard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.IDN;
import java.net.URI;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.kaiwasabi.privacyplus.guard.LinkGuard.Analysis;
import org.kaiwasabi.privacyplus.guard.LinkGuard.Kind;
import org.junit.jupiter.api.Test;

class LinkGuardTest {
    private static Analysis analyze(String link) throws Exception {
        return LinkGuard.analyze(new URI(link));
    }

    private static Set<Kind> kinds(String link) throws Exception {
        return analyze(link).warnings().stream().map(LinkGuard.Warning::kind)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Kind.class)));
    }

    private static String emphasized(Analysis analysis) {
        return analysis.address().substring(analysis.siteStart(), analysis.siteEnd());
    }

    @Test
    void ordinaryLinksHaveNoWarnings() throws Exception {
        String[] links = {
            "https://minecraft.wiki/w/Creeper",
            "https://www.minecraft.net/en-us/article/minecraft-java-edition",
            "https://github.com/kaiwasabi/privacyplus/issues?q=is%3Aopen",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10s",
            "https://discord.gg/minecraft",
            "https://en.wikipedia.org/wiki/Minecraft",
            "https://modrinth.com/mod/sodium",
            "https://www.curseforge.com/minecraft/mc-mods",
            "https://aka.ms/MinecraftEULA",
            "https://bugs.mojang.com/browse/MCPE-28723",
            "https://example.com:443/path",
            "https://EXAMPLE.com/",
            "https://shop.example.co.uk/basket#top",
            "https://www.reddit.com/r/Minecraft/",
            "https://mastodon.social/@kaiwasabi",
            "https://art.co/gallery",
            "https://bitly.example.com/",
            "https://grabify.link.example.com/",
            "https://notgrabify.link/",
            "https://t.com/",
            "https://123.example.com/",
        };
        for (String link : links) {
            Analysis analysis = analyze(link);
            assertFalse(analysis.risky(), link + " was flagged: " + analysis.warnings());
        }
    }

    @Test
    void ipLoggers() throws Exception {
        String[] links = {
            "https://grabify.link/ABC123",
            "https://www.grabify.link/ABC123",
            "https://GRABIFY.LINK/x",
            "https://grabify.link./x",
            "https://iplogger.org/2abc",
            "https://iplogger.com/1xyz",
            "https://iplogger.ru/1xyz",
            "https://2no.co/1a2b3c",
            "https://yip.su/xyz",
            "https://bmwforum.co/abc",
            "https://leancoding.co/abc",
            "https://spottyfly.com/abc",
            "https://stopify.co/abc",
            "https://headshot.monster/abc",
            "https://joinmy.site/abc",
            "https://blasze.com/x",
            "https://canarytokens.com/about/abc/index.html",
            "https://webhook.site/0b4c-1234",
        };
        for (String link : links) {
            assertTrue(kinds(link).contains(Kind.IP_LOGGER), link);
        }
        assertEquals(EnumSet.of(Kind.IP_LOGGER), kinds("https://grabify.link/ABC123"), "nothing else wrong with it");
        assertTrue(analyze("https://grabify.link/ABC123").warnings().getFirst().text().contains("grabify.link"));
    }

    @Test
    void disguisedIpLoggerNames() throws Exception {
        // Percent-escapes, fullwidth letters and odd dots all reach grabify.link in a browser
        assertTrue(kinds("https://%67rabify.link/x").contains(Kind.IP_LOGGER));
        assertTrue(kinds("https://\uFF47\uFF52\uFF41\uFF42\uFF49\uFF46\uFF59.link/x").contains(Kind.IP_LOGGER));
        assertTrue(kinds("https://grabify\u3002link/x").contains(Kind.IP_LOGGER));
        // Grabify's lookalike domains, written in Unicode and as punycode
        assertEquals(EnumSet.of(Kind.IP_LOGGER, Kind.LOOKALIKE), kinds("https://yoütu.be/watch"));
        assertEquals(EnumSet.of(Kind.IP_LOGGER, Kind.LOOKALIKE), kinds("https://" + IDN.toASCII("yoütu.be") + "/watch"));
        assertEquals(EnumSet.of(Kind.IP_LOGGER, Kind.LOOKALIKE), kinds("https://" + IDN.toASCII("discörd.com") + "/invite"));
        assertEquals("discörd.com", analyze("https://" + IDN.toASCII("discörd.com") + "/invite").host());
    }

    @Test
    void shorteners() throws Exception {
        String[] links = {"https://bit.ly/3abcd", "https://tinyurl.com/abc", "https://t.co/xyz", "https://is.gd/abc", "https://cutt.ly/x",
            "https://rb.gy/x", "https://ow.ly/x", "https://goo.gl/x", "https://shorturl.at/abc", "https://preview.tinyurl.com/abc"};
        for (String link : links) {
            assertEquals(EnumSet.of(Kind.SHORTENER), kinds(link), link);
        }
    }

    @Test
    void plainHttp() throws Exception {
        assertEquals(EnumSet.of(Kind.NOT_HTTPS), kinds("http://example.com/"));
        assertEquals(EnumSet.of(Kind.NOT_HTTPS), kinds("HTTP://example.com:80/"), "the default port is fine");
        assertEquals(EnumSet.of(Kind.NOT_HTTPS, Kind.SHORTENER), kinds("http://bit.ly/x"));
    }

    @Test
    void ipAddresses() throws Exception {
        assertEquals(EnumSet.of(Kind.IP_ADDRESS), kinds("https://203.0.113.7/login"));
        assertEquals(EnumSet.of(Kind.IP_ADDRESS, Kind.NOT_HTTPS), kinds("http://192.168.1.1/"));
        assertEquals(EnumSet.of(Kind.IP_ADDRESS), kinds("https://[2001:db8::1]/"));
        assertEquals(EnumSet.of(Kind.IP_ADDRESS, Kind.PORT), kinds("https://localhost:3000/"));
        assertEquals("203.0.113.7", analyze("https://203.0.113.7/").site());
    }

    @Test
    void disguisedIpAddresses() throws Exception {
        assertTrue(analyze("http://3232235777/").warnings().stream()
            .anyMatch(w -> w.kind() == Kind.IP_ADDRESS && w.text().contains("192.168.1.1")));
        assertTrue(analyze("http://0x7f.1/").warnings().stream().anyMatch(w -> w.text().contains("127.0.0.1")));
        assertTrue(analyze("http://0177.0.0.1/").warnings().stream().anyMatch(w -> w.text().contains("127.0.0.1")));
        assertEquals("127.0.0.1", LinkGuard.ipv4("2130706433"));
        assertEquals("127.0.0.1", LinkGuard.ipv4("0x7F000001"));
        assertEquals("10.0.0.1", LinkGuard.ipv4("10.1"));
        assertNull(LinkGuard.ipv4("example.com"));
        assertNull(LinkGuard.ipv4("example.123"));
        assertNull(LinkGuard.ipv4("1.2.3.256"));
        assertNull(LinkGuard.ipv4("1.2.3.4.5"));
    }

    @Test
    void lookalikeLetters() throws Exception {
        // "аррӏе" is all Cyrillic; "pаypal" has one Cyrillic а among Latin letters
        Analysis apple = analyze("https://xn--80ak6aa92e.com/");
        assertEquals(EnumSet.of(Kind.LOOKALIKE), kinds("https://xn--80ak6aa92e.com/"));
        assertTrue(apple.warnings().getFirst().text().contains("xn--80ak6aa92e.com"));
        Analysis paypal = analyze("https://" + IDN.toASCII("p\u0430ypal.com") + "/signin");
        assertTrue(paypal.warnings().getFirst().text().contains("mixes letters"), paypal.warnings().toString());
        assertTrue(paypal.warnings().getFirst().text().contains("Cyrillic"));
        assertEquals(EnumSet.of(Kind.LOOKALIKE), kinds("https://p\u0430ypal.com/"), "written directly in Unicode too");
        assertTrue(kinds("https://xn--invalid-.com/").contains(Kind.LOOKALIKE), "broken punycode still counts");
        // Japanese names mix scripts legitimately: still non-English letters, but not "mixed alphabets"
        Analysis japanese = analyze("https://" + IDN.toASCII("ひらがなカタカナ漢字.jp") + "/");
        assertFalse(japanese.warnings().getFirst().text().contains("mixes letters"));
    }

    @Test
    void decoyBeforeAt() throws Exception {
        Analysis analysis = analyze("https://google.com@evil.example/login");
        assertEquals(EnumSet.of(Kind.USER_INFO), kinds("https://google.com@evil.example/login"));
        assertEquals("evil.example", analysis.host());
        assertEquals("evil.example", emphasized(analysis));
        assertTrue(analysis.warnings().getFirst().text().contains("\"google.com\""));
        assertTrue(kinds("https://user:secret@example.com/").contains(Kind.USER_INFO));
        assertTrue(kinds("https://www.minecraft.net%2Flogin@grabify.link/x").containsAll(Set.of(Kind.USER_INFO, Kind.IP_LOGGER)));
    }

    @Test
    void unusualPorts() throws Exception {
        assertEquals(EnumSet.of(Kind.PORT), kinds("https://example.com:8080/"));
        assertEquals(EnumSet.of(Kind.PORT), kinds("https://example.com:25565/"));
        assertEquals(EnumSet.of(Kind.PORT, Kind.NOT_HTTPS), kinds("http://example.com:443/"));
        assertTrue(kinds("https://example.com:/").isEmpty(), "an empty port is the default one");
    }

    @Test
    void noSiteName() throws Exception {
        assertTrue(kinds("https:grabify.link").contains(Kind.UNREADABLE));
    }

    @Test
    void siteIsEmphasized() throws Exception {
        assertEquals("example.co.uk", emphasized(analyze("https://shop.example.co.uk/path")));
        assertEquals("evil.example", emphasized(analyze("https://www.google.com.evil.example/accounts")));
        assertEquals("minecraft.wiki", emphasized(analyze("https://minecraft.wiki/w/Creeper")));
        assertEquals("evil.example", analyze("https://www.google.com.evil.example/accounts").site());
        assertEquals("203.0.113.7", emphasized(analyze("https://203.0.113.7:8443/")));
        Analysis upper = analyze("https://WWW.Example.COM/");
        assertEquals("Example.COM", emphasized(upper));
        assertEquals("www.example.com", upper.host());
    }

    @Test
    void percentDecoding() {
        assertEquals("grabify.link", LinkGuard.percentDecode("%67rabify%2Elink"));
        assertEquals("ü", LinkGuard.percentDecode("%C3%BC"));
        assertEquals("100%", LinkGuard.percentDecode("100%"));
        assertEquals("%zz", LinkGuard.percentDecode("%zz"));
    }
}
