package org.kaiwasabi.privacyplus.dns;

import static org.kaiwasabi.privacyplus.dns.DnsAnswers.a;
import static org.kaiwasabi.privacyplus.dns.DnsAnswers.cname;
import static org.kaiwasabi.privacyplus.dns.DnsAnswers.respond;
import static org.kaiwasabi.privacyplus.dns.DnsAnswers.srv;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrivateDnsTest {
    // name + " " + type -> answers; a missing entry is NXDOMAIN
    private final Map<String, DnsAnswers.Answer[]> zone = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private boolean unreachable;

    @BeforeEach
    void setUp() {
        PrivacyPlusConfig.privateDns = "cloudflare";
        PrivateDns.clearCache();
        PrivateDns.transport = (endpoint, query) -> {
            String question = DnsAnswers.readQuestionName(query) + " " + DnsAnswers.readQuestionType(query);
            this.asked.add(question);
            if (this.unreachable) {
                throw new IOException("connect timed out");
            }
            DnsAnswers.Answer[] answers = this.zone.get(question);
            return answers == null ? respond(query, DnsMessage.NXDOMAIN) : respond(query, 0, answers);
        };
        PrivateDns.onFailure = this.failures::add;
    }

    @AfterEach
    void tearDown() {
        PrivacyPlusConfig.privateDns = "off";
    }

    @Test
    void settings() {
        assertNull(PrivateDns.parse("off"));
        assertNull(PrivateDns.parse(" "));
        assertEquals("cloudflare", PrivateDns.parse("Cloudflare").setting());
        assertEquals("https://9.9.9.9/dns-query", PrivateDns.parse("quad9").endpoint().toString());
        assertEquals("dns.example", PrivateDns.parse("https://dns.example/dns-query").label());
        assertEquals("127.0.0.1", PrivateDns.parse("http://127.0.0.1:5380/dns-query").label(), "plain http to a local proxy");
        assertEquals("[::1]", PrivateDns.parse("http://[::1]:5380/dns-query").label());
        assertThrows(IllegalArgumentException.class, () -> PrivateDns.parse("http://dns.example/dns-query"), "unencrypted over the network");
        assertThrows(IllegalArgumentException.class, () -> PrivateDns.parse("google"));
        assertThrows(IllegalArgumentException.class, () -> PrivateDns.parse("https://"));
    }

    @Test
    void switchCyclesThroughServicesAndRemembersACustomOne() {
        assertEquals("cloudflare", PrivateDns.next("off"));
        assertEquals("quad9", PrivateDns.next("cloudflare"));
        String custom = "https://dns.example/dns-query";
        assertEquals("off", PrivateDns.next(custom));
        assertEquals(custom, PrivateDns.next("quad9"));
    }

    @Test
    void localNamesAndOffAreLeftToVanilla() {
        assertTrue(PrivateDns.handles("mc.example.com"));
        assertTrue(PrivateDns.handles("play.example.com."));
        assertTrue(PrivateDns.handles("203.0.113.9"), "answered without any lookup");
        assertTrue(PrivateDns.handles("2001:db8::1"), "answered without any lookup");
        for (String local : List.of("localhost", "myserver", "nas.local", "mc.lan", "box.home.arpa", "dev.localhost")) {
            assertFalse(PrivateDns.handles(local), local);
        }
        PrivacyPlusConfig.privateDns = "off";
        assertFalse(PrivateDns.handles("mc.example.com"));
    }

    @Test
    void sameResultAsVanillaForAnSrvServer() {
        this.zone.put("mc.example.com 1", new DnsAnswers.Answer[] {a("mc.example.com", 300, "192.0.2.10")});
        this.zone.put("_minecraft._tcp.mc.example.com 33", new DnsAnswers.Answer[] {
            srv("_minecraft._tcp.mc.example.com", 300, 25570, "play.example.com"), srv("_minecraft._tcp.mc.example.com", 300, 25571, "b.example.com")});
        this.zone.put("play.example.com 1", new DnsAnswers.Answer[] {
            cname("play.example.com", 300, "edge.example.net"), a("edge.example.net", 300, "203.0.113.7")});

        // The same steps as ServerNameResolver: the name itself, then the SRV record, then its target
        ServerAddress typed = new ServerAddress("mc.example.com", 25565);
        assertEquals("192.0.2.10", PrivateDns.resolve(typed).orElseThrow().getHostIp());
        ServerAddress redirect = PrivateDns.lookupRedirect(typed).orElseThrow();
        assertEquals("play.example.com.", redirect.getHost(), "vanilla keeps the SRV target's trailing dot");
        assertEquals(25570, redirect.getPort());
        ResolvedServerAddress resolved = PrivateDns.resolve(redirect).orElseThrow();
        InetSocketAddress socket = resolved.asInetSocketAddress();
        assertEquals("203.0.113.7", socket.getAddress().getHostAddress());
        assertEquals(25570, socket.getPort());
        assertEquals("play.example.com.", socket.getHostName(), "the join handshake sends this, as vanilla does");
        assertEquals(List.of("mc.example.com 1", "_minecraft._tcp.mc.example.com 33", "play.example.com 1"), this.asked);
        assertTrue(this.failures.isEmpty());
    }

    @Test
    void ipv6WhenThereIsNoIpv4() {
        this.zone.put("v6.example.com 28", new DnsAnswers.Answer[] {a("v6.example.com", 300, "2001:db8::7")});
        ResolvedServerAddress resolved = PrivateDns.resolve(new ServerAddress("v6.example.com", 25565)).orElseThrow();
        assertEquals("2001:db8:0:0:0:0:0:7", resolved.getHostIp());
        assertEquals(List.of("v6.example.com 1", "v6.example.com 28"), this.asked);
    }

    @Test
    void ipAddressesNeedNoLookupAtAll() {
        ResolvedServerAddress resolved = PrivateDns.resolve(new ServerAddress("203.0.113.9", 25565)).orElseThrow();
        assertEquals("203.0.113.9", resolved.getHostName(), "no reverse lookup through the system DNS");
        assertEquals(Optional.empty(), PrivateDns.lookupRedirect(new ServerAddress("203.0.113.9", 25565)));
        assertTrue(this.asked.isEmpty());
    }

    @Test
    void srvOnlyForTheDefaultPortLikeVanilla() {
        assertEquals(Optional.empty(), PrivateDns.lookupRedirect(new ServerAddress("mc.example.com", 25570)));
        assertTrue(this.asked.isEmpty());
    }

    @Test
    void unknownNamesFailQuietly() {
        assertEquals(Optional.empty(), PrivateDns.resolve(new ServerAddress("gone.example.com", 25565)));
        assertEquals(Optional.empty(), PrivateDns.lookupRedirect(new ServerAddress("gone.example.com", 25565)));
        assertTrue(this.failures.isEmpty());
    }

    @Test
    void failsClosedWhenTheServiceDoesNotAnswer() {
        this.unreachable = true;
        assertEquals(Optional.empty(), PrivateDns.resolve(new ServerAddress("mc.example.com", 25565)));
        assertEquals(Optional.empty(), PrivateDns.lookupRedirect(new ServerAddress("mc.example.com", 25565)));
        assertEquals(2, this.failures.size());
        assertTrue(this.failures.getFirst().startsWith("No answer from Cloudflare for mc.example.com"), this.failures.getFirst());
    }

    @Test
    void serverErrorsAreFailuresNotEmptyAnswers() {
        PrivateDns.transport = (endpoint, query) -> respond(query, 2);
        assertEquals(Optional.empty(), PrivateDns.resolve(new ServerAddress("mc.example.com", 25565)));
        assertEquals(1, this.failures.size());
    }

    @Test
    void answersAreReusedWhileFresh() {
        this.zone.put("mc.example.com 1", new DnsAnswers.Answer[] {a("mc.example.com", 300, "192.0.2.10")});
        PrivateDns.resolve(new ServerAddress("mc.example.com", 25565));
        PrivateDns.resolve(new ServerAddress("MC.example.com", 25565));
        PrivateDns.lookupRedirect(new ServerAddress("mc.example.com", 25565));
        PrivateDns.lookupRedirect(new ServerAddress("mc.example.com", 25565));
        assertEquals(List.of("mc.example.com 1", "_minecraft._tcp.mc.example.com 33"), this.asked);
    }
}
