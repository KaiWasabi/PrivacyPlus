package org.kaiwasabi.privacyplus.dns;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// Builds DNS responses the way resolvers send them, for the tests
final class DnsAnswers {
    record Answer(String name, int type, int ttl, byte[] data) {
    }

    private DnsAnswers() {
    }

    static Answer a(String name, int ttl, String ip) {
        return new Answer(name, ip.contains(":") ? DnsMessage.AAAA : DnsMessage.A, ttl, address(ip));
    }

    static Answer cname(String name, int ttl, String target) {
        return new Answer(name, DnsMessage.CNAME, ttl, name(target));
    }

    static Answer srv(String name, int ttl, int port, String target) {
        ByteBuffer data = ByteBuffer.allocate(300);
        data.putShort((short) 0).putShort((short) 5).putShort((short) port).put(name(target));
        return new Answer(name, DnsMessage.SRV, ttl, java.util.Arrays.copyOf(data.array(), data.position()));
    }

    // Copies the question from query. Answers owned by the question's name use a compression pointer to it.
    static byte[] respond(byte[] query, int rcode, Answer... answers) {
        int questionEnd = 12;
        while (query[questionEnd] != 0) {
            questionEnd += 1 + query[questionEnd];
        }
        questionEnd += 5;
        String question = readQuestionName(query);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(12);
        header.putShort((short) 0).putShort((short) (0x8180 | rcode)).putShort((short) 1).putShort((short) answers.length).putShort((short) 0).putShort((short) 0);
        out.writeBytes(header.array());
        out.write(query, 12, questionEnd - 12);
        for (Answer answer : answers) {
            out.writeBytes(DnsMessage.sameName(answer.name(), question) ? new byte[] {(byte) 0xC0, 12} : name(answer.name()));
            ByteBuffer fixed = ByteBuffer.allocate(10);
            fixed.putShort((short) answer.type()).putShort((short) 1).putInt(answer.ttl()).putShort((short) answer.data().length);
            out.writeBytes(fixed.array());
            out.writeBytes(answer.data());
        }
        return out.toByteArray();
    }

    static String readQuestionName(byte[] query) {
        StringBuilder name = new StringBuilder();
        int position = 12;
        while (query[position] != 0) {
            if (!name.isEmpty()) {
                name.append('.');
            }
            name.append(new String(query, position + 1, query[position], StandardCharsets.US_ASCII));
            position += 1 + query[position];
        }
        return name.toString();
    }

    static int readQuestionType(byte[] query) {
        int position = 12;
        while (query[position] != 0) {
            position += 1 + query[position];
        }
        return (query[position + 1] & 0xFF) << 8 | query[position + 2] & 0xFF;
    }

    static byte[] name(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String bare = DnsMessage.stripDot(name);
        if (!bare.isEmpty()) {
            for (String label : bare.split("\\.")) {
                out.write(label.length());
                out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
            }
        }
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] address(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
