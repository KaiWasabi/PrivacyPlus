package org.kaiwasabi.privacyplus.dns;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

// The small part of the DNS wire format (RFC 1035) that server lookups need: a query for one name, and
// the A, AAAA, CNAME and SRV records of the answer. Other record types are skipped.
public final class DnsMessage {
    public static final int A = 1;
    public static final int CNAME = 5;
    public static final int AAAA = 28;
    public static final int SRV = 33;
    private static final int OPT = 41;
    public static final int NOERROR = 0;
    public static final int NXDOMAIN = 3;
    // RFC 8467: queries are padded to a multiple of 128 bytes, so someone watching the encrypted
    // connection can't guess the name from the query's size
    static final int PADDING_BLOCK = 128;
    private static final int MAX_NAME = 253;
    private static final int MAX_CNAMES = 8;

    // address: A and AAAA; target: CNAME and SRV (without the trailing dot, "" for the root); port: SRV
    public record Record(String name, int type, long ttl, byte[] address, String target, int port) {
    }

    public record Response(int rcode, List<Record> answers) {
        // The records of this type for name, following the CNAMEs a resolver puts in front of them
        public List<Record> find(String name, int type) {
            String owner = name;
            for (int hop = 0; hop <= MAX_CNAMES; hop++) {
                String current = owner;
                List<Record> found = this.answers.stream().filter(r -> r.type() == type && sameName(r.name(), current)).toList();
                if (!found.isEmpty()) {
                    return found;
                }
                Optional<Record> alias = this.answers.stream().filter(r -> r.type() == CNAME && sameName(r.name(), current)).findFirst();
                if (alias.isEmpty()) {
                    return List.of();
                }
                owner = alias.get().target();
            }
            return List.of();
        }

        // How long the answer may be reused: the shortest TTL in it
        public long ttl() {
            return this.answers.stream().mapToLong(Record::ttl).min().orElse(0);
        }
    }

    private DnsMessage() {
    }

    // name must already be ASCII (IDN.toASCII). ID 0, as RFC 8484 recommends for DNS-over-HTTPS.
    public static byte[] query(String name, int type) {
        ByteBuffer out = ByteBuffer.allocate(512);
        // Header: recursion desired, one question, one additional record (OPT)
        out.putShort((short) 0).putShort((short) 0x0100).putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) 1);
        writeName(out, name);
        out.putShort((short) type).putShort((short) 1);
        // OPT pseudo-record (RFC 6891): root owner, 1232-byte payload size, no flags
        out.put((byte) 0).putShort((short) OPT).putShort((short) 1232).putInt(0);
        int lengthAt = out.position();
        out.putShort((short) 0);
        // Client subnet with prefix length 0 (RFC 7871): the resolver must not pass part of your IP
        // address on to the server's own DNS
        out.putShort((short) 8).putShort((short) 4).putShort((short) 1).put((byte) 0).put((byte) 0);
        int padding = Math.floorMod(-(out.position() + 4), PADDING_BLOCK);
        out.putShort((short) 12).putShort((short) padding).put(new byte[padding]);
        out.putShort(lengthAt, (short) (out.position() - lengthAt - 2));
        return Arrays.copyOf(out.array(), out.position());
    }

    // Throws IOException unless message is a well-formed, complete answer to this question
    public static Response parse(byte[] message, String name, int type) throws IOException {
        try {
            ByteBuffer in = ByteBuffer.wrap(message);
            // The ID is 0 in both directions; the HTTPS exchange ties the answer to the query
            in.getShort();
            int flags = in.getShort() & 0xFFFF;
            int questions = in.getShort() & 0xFFFF;
            int answers = in.getShort() & 0xFFFF;
            in.getShort();
            in.getShort();
            if ((flags & 0x8000) == 0 || (flags & 0x0200) != 0 || questions != 1) {
                throw new IOException("Not a complete DNS response");
            }
            String question = readName(in, message);
            int questionType = in.getShort() & 0xFFFF;
            in.getShort();
            if (!sameName(question, name) || questionType != type) {
                throw new IOException("DNS response to a different question");
            }
            List<Record> records = new ArrayList<>();
            for (int i = 0; i < answers; i++) {
                String owner = readName(in, message);
                int recordType = in.getShort() & 0xFFFF;
                in.getShort();
                long ttl = in.getInt() & 0xFFFFFFFFL;
                int length = in.getShort() & 0xFFFF;
                int end = in.position() + length;
                switch (recordType) {
                    case A, AAAA -> {
                        byte[] address = new byte[end - in.position()];
                        if (address.length != (recordType == A ? 4 : 16)) {
                            throw new IOException("Malformed address record");
                        }
                        in.get(address);
                        records.add(new Record(owner, recordType, ttl, address, "", 0));
                    }
                    case CNAME -> records.add(new Record(owner, CNAME, ttl, new byte[0], readName(in, message), 0));
                    case SRV -> {
                        in.getShort();
                        in.getShort();
                        int port = in.getShort() & 0xFFFF;
                        records.add(new Record(owner, SRV, ttl, new byte[0], readName(in, message), port));
                    }
                    default -> {
                    }
                }
                in.position(end);
            }
            return new Response(flags & 0xF, List.copyOf(records));
        } catch (BufferUnderflowException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("Malformed DNS response", e);
        }
    }

    static boolean sameName(String a, String b) {
        return stripDot(a).toLowerCase(Locale.ROOT).equals(stripDot(b).toLowerCase(Locale.ROOT));
    }

    static String stripDot(String name) {
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }

    private static void writeName(ByteBuffer out, String name) {
        String ascii = stripDot(name);
        if (ascii.isEmpty() || ascii.length() > MAX_NAME || !StandardCharsets.US_ASCII.newEncoder().canEncode(ascii)) {
            throw new IllegalArgumentException("Not a DNS name: " + name);
        }
        for (String label : ascii.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                throw new IllegalArgumentException("Not a DNS name: " + name);
            }
            out.put((byte) label.length()).put(label.getBytes(StandardCharsets.US_ASCII));
        }
        out.put((byte) 0);
    }

    // Follows compression pointers (RFC 1035 4.1.4), at most 16 of them, so a looping message can't hang it
    private static String readName(ByteBuffer in, byte[] message) {
        StringBuilder name = new StringBuilder();
        int position = in.position();
        int jumps = 0;
        int resumeAt = -1;
        while (true) {
            int length = message[position] & 0xFF;
            if (length == 0) {
                position++;
                break;
            }
            if ((length & 0xC0) == 0xC0) {
                if (++jumps > 16) {
                    throw new IllegalArgumentException("DNS name pointer loop");
                }
                if (resumeAt == -1) {
                    resumeAt = position + 2;
                }
                position = (length & 0x3F) << 8 | message[position + 1] & 0xFF;
                continue;
            }
            if ((length & 0xC0) != 0) {
                throw new IllegalArgumentException("Unknown DNS label type");
            }
            String label = new String(message, position + 1, length, StandardCharsets.ISO_8859_1);
            // Host names are printable ASCII without dots inside labels; anything else isn't a server address
            if (label.chars().anyMatch(c -> c <= ' ' || c > '~' || c == '.')) {
                throw new IllegalArgumentException("Unexpected characters in a DNS name");
            }
            if (!name.isEmpty()) {
                name.append('.');
            }
            name.append(label);
            if (name.length() > MAX_NAME) {
                throw new IllegalArgumentException("DNS name too long");
            }
            position += 1 + length;
        }
        in.position(resumeAt != -1 ? resumeAt : position);
        return name.toString();
    }
}
