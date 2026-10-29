package org.kaiwasabi.privacyplus.dns;

import static org.kaiwasabi.privacyplus.dns.DnsAnswers.a;
import static org.kaiwasabi.privacyplus.dns.DnsAnswers.cname;
import static org.kaiwasabi.privacyplus.dns.DnsAnswers.respond;
import static org.kaiwasabi.privacyplus.dns.DnsAnswers.srv;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DnsMessageTest {
    @Test
    void queriesArePaddedSoTheirSizeHidesTheName() {
        for (String name : List.of("a.io", "mc.example.com", "_minecraft._tcp.a-much-longer-server-name.example.org", "x".repeat(63) + ".example")) {
            byte[] query = DnsMessage.query(name, DnsMessage.A);
            assertEquals(0, query.length % DnsMessage.PADDING_BLOCK, name);
            assertEquals(name, DnsAnswers.readQuestionName(query));
            assertEquals(DnsMessage.A, DnsAnswers.readQuestionType(query));
            assertEquals(0x01, query[2], "recursion desired");
        }
        assertEquals(128, DnsMessage.query("a.io", DnsMessage.A).length);
    }

    @Test
    void queriesAskResolversNotToPassOnTheClientSubnet() {
        String hex = HexFormat.of().formatHex(DnsMessage.query("mc.example.com", DnsMessage.SRV));
        // option 8 (client subnet), length 4, IPv4, source prefix 0, scope 0
        assertTrue(hex.contains("0008000400010000"), hex);
    }

    @Test
    void rejectsNamesDnsCantCarry() {
        assertThrows(IllegalArgumentException.class, () -> DnsMessage.query("", DnsMessage.A));
        assertThrows(IllegalArgumentException.class, () -> DnsMessage.query("a..example", DnsMessage.A));
        assertThrows(IllegalArgumentException.class, () -> DnsMessage.query("x".repeat(64) + ".example", DnsMessage.A));
        assertThrows(IllegalArgumentException.class, () -> DnsMessage.query("bücher.example", DnsMessage.A));
    }

    @Test
    void followsCnamesToTheAddress() throws IOException {
        byte[] query = DnsMessage.query("mc.example.com", DnsMessage.A);
        byte[] response = respond(query, 0,
            cname("mc.example.com", 300, "edge.example.net"),
            cname("edge.example.net", 60, "edge-7.example.net."),
            a("unrelated.example", 300, "192.0.2.1"),
            a("EDGE-7.example.net", 120, "203.0.113.7"));
        DnsMessage.Response parsed = DnsMessage.parse(response, "mc.example.com", DnsMessage.A);
        List<DnsMessage.Record> found = parsed.find("mc.example.com", DnsMessage.A);
        assertEquals(1, found.size());
        assertArrayEquals(new byte[] {(byte) 203, 0, 113, 7}, found.getFirst().address());
        assertEquals(60, parsed.ttl());
    }

    @Test
    void readsSrvRecordsInOrder() throws IOException {
        String name = "_minecraft._tcp.mc.example.com";
        byte[] response = respond(DnsMessage.query(name, DnsMessage.SRV), 0,
            srv(name, 300, 25570, "play.example.com"), srv(name, 300, 25571, "backup.example.com"));
        List<DnsMessage.Record> found = DnsMessage.parse(response, name, DnsMessage.SRV).find(name, DnsMessage.SRV);
        assertEquals(List.of("play.example.com", "backup.example.com"), found.stream().map(DnsMessage.Record::target).toList());
        assertEquals(25570, found.getFirst().port());
    }

    @Test
    void nxdomainIsAnEmptyAnswer() throws IOException {
        DnsMessage.Response parsed = DnsMessage.parse(respond(DnsMessage.query("gone.example", DnsMessage.A), 3), "gone.example", DnsMessage.A);
        assertEquals(DnsMessage.NXDOMAIN, parsed.rcode());
        assertTrue(parsed.find("gone.example", DnsMessage.A).isEmpty());
    }

    @Test
    void anythingMalformedIsAnIOException() {
        byte[] good = respond(DnsMessage.query("mc.example.com", DnsMessage.A), 0,
            cname("mc.example.com", 300, "edge.example.net"), a("edge.example.net", 300, "203.0.113.7"));
        // Every truncation fails cleanly, never with another exception
        for (int length = 0; length < good.length; length++) {
            byte[] cut = Arrays.copyOf(good, length);
            assertThrows(IOException.class, () -> DnsMessage.parse(cut, "mc.example.com", DnsMessage.A), "cut at " + length);
        }
        assertThrows(IOException.class, () -> DnsMessage.parse(good, "other.example.com", DnsMessage.A), "answer to another name");
        assertThrows(IOException.class, () -> DnsMessage.parse(good, "mc.example.com", DnsMessage.AAAA), "answer to another type");
        byte[] notResponse = good.clone();
        notResponse[2] &= 0x7F;
        assertThrows(IOException.class, () -> DnsMessage.parse(notResponse, "mc.example.com", DnsMessage.A), "query echoed back");
        byte[] truncatedFlag = good.clone();
        truncatedFlag[2] |= 0x02;
        assertThrows(IOException.class, () -> DnsMessage.parse(truncatedFlag, "mc.example.com", DnsMessage.A), "TC set");
        // The answer's owner name points at itself
        byte[] loop = good.clone();
        int answerStart = 12 + DnsAnswers.name("mc.example.com").length + 4;
        loop[answerStart] = (byte) 0xC0;
        loop[answerStart + 1] = (byte) answerStart;
        assertThrows(IOException.class, () -> DnsMessage.parse(loop, "mc.example.com", DnsMessage.A), "pointer loop");
    }

    @Test
    void namesWithOddCharactersAreRejected() {
        byte[] query = DnsMessage.query("mc.example.com", DnsMessage.SRV);
        byte[] response = respond(query, 0, srv("mc.example.com", 300, 25565, "evil\u0000name.example"));
        assertThrows(IOException.class, () -> DnsMessage.parse(response, "mc.example.com", DnsMessage.SRV));
    }
}
