package org.kaiwasabi.privacyplus.chat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

// Wire format of one encrypted chat token (canonical unpadded URL-safe base64, no marker):
//   nonce (12) | AES-256-GCM( header | payload | 0x80 0x00... ) | tag (16)
//   header (17) = version and flags (1) | part, part count (4 bits each) | minutes since 1970 (3, wraps after
//                 ~32 years) | message id (4, the same in every part of one message, so parts of different
//                 messages can't be spliced together unnoticed) | sender check (8: SHA-256 of the sender's
//                 name, truncated, too long to find another name with the same check)
// The payload is the UTF-8 text, or the text deflated with a chat dictionary when that is shorter. Padding
// (0x80 then zeros) fills a random number of 16-byte blocks, between half the room the token has and all of
// it (at least what the payload needs), so short chat, long chat and key exchanges all draw their lengths
// from the same spread instead of having telltale sizes.
// Versions 1 (full sender name) and 2 (32-bit sender check) can still be read; their sender isn't verified.
public final class ChatCrypto {
    public static final int VERSION = 3;
    private static final int COMPRESSED = 0x80;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int HEADER_BYTES = 17;
    private static final int VERSION_2_HEADER_BYTES = 9;
    private static final int PAD_TO = 16;
    private static final int MAX_PARTS = 15;
    private static final int MAX_TEXT_BYTES = 4096;
    // OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256: the server sees every ciphertext,
    // so weak passphrases must be expensive to guess offline
    private static final int KDF_ITERATIONS = 600_000;
    private static final byte[] KDF_SALT = "privacyplus/chat/v1".getBytes(StandardCharsets.UTF_8);
    // Preset deflate dictionary: common chat words and Minecraft terms, most frequent last
    private static final byte[] DICTIONARY = (
        " coords nether portal stronghold village villager trade enchant netherite elytra shulker"
            + " creeper zombie skeleton spawn base farm iron gold diamond diamonds pickaxe sword armor"
            + " food torch torches cave mine chest house build come here there where when what how why"
            + " who need want going gonna wanna later now today tomorrow ok okay yes yeah no lol brb gg"
            + " thanks please sorry wait meet bring find found have has had will would could should can"
            + " just like with from this that they them your you're it's i'm don't can't for and the you to "
    ).getBytes(StandardCharsets.UTF_8);

    // The smallest possible token (one padding block, version 2) is 52 bytes, i.e. 70 characters
    public static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{70,}");

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    // sender is the name for version 1 tokens; later versions only carry senderCheck. nonce (base64) is
    // unique to the token, whatever else is the same, so a repeat of it is a replay. messageId ties the
    // parts of one message together (0 before version 3).
    public record Message(@Nullable String sender, long senderCheck, Instant sentAt, int part, int parts, String text, int version,
                          int messageId, String nonce) {
        public boolean writtenBy(String name) {
            return switch (this.version) {
                case 1 -> this.sender != null && this.sender.equalsIgnoreCase(name);
                case 2 -> ChatCrypto.senderCheck(name) == (int) this.senderCheck;
                default -> ChatCrypto.senderCheck64(name) == this.senderCheck;
            };
        }

        // Whether the sender check is long enough to trust: older versions' can be matched by made-up names
        public boolean senderVerifiable() {
            return this.version >= VERSION;
        }

        @Override
        public String toString() {
            return "Message[version=" + this.version + ", part=" + this.part + "/" + this.parts + "]";
        }
    }

    private ChatCrypto() {
    }

    public static SecretKey deriveKey(String passphrase) {
        try {
            PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), KDF_SALT, KDF_ITERATIONS, 256);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    // Encrypts text as one or more tokens, each at most maxTokenChars long. Long text is split
    // into self-contained parts so a lost or reordered line never breaks the others.
    public static List<String> encrypt(SecretKey key, String sender, String text, int maxTokenChars, Instant now) {
        int maxBytes = maxTokenChars * 3 / 4;
        int paddedBudget = (maxBytes - NONCE_BYTES - TAG_BYTES - HEADER_BYTES) / PAD_TO * PAD_TO;
        if (paddedBudget < PAD_TO) {
            throw new IllegalArgumentException("Token limit of " + maxTokenChars + " characters is too small");
        }

        List<byte[]> chunks = splitUtf8(text, paddedBudget - 1);
        if (chunks.size() > MAX_PARTS) {
            throw new IllegalArgumentException("Message too long");
        }
        int minutes = (int) (now.getEpochSecond() / 60) & 0xFFFFFF;
        long check = senderCheck64(sender);
        int messageId = RANDOM.nextInt();
        List<String> tokens = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            byte[] deflated = deflate(chunk);
            boolean compressed = deflated.length < chunk.length;
            byte[] payload = compressed ? deflated : chunk;
            int padded = paddedLength(payload.length + 1, paddedBudget);
            ByteBuffer plain = ByteBuffer.allocate(HEADER_BYTES + padded)
                .put((byte) (VERSION | (compressed ? COMPRESSED : 0)))
                .put((byte) ((i + 1) << 4 | chunks.size()))
                .put((byte) (minutes >>> 16)).put((byte) (minutes >>> 8)).put((byte) minutes)
                .putInt(messageId)
                .putLong(check)
                .put(payload)
                .put((byte) 0x80);
            tokens.add(ENCODER.encodeToString(seal(key, plain.array())));
        }
        return tokens;
    }

    // A random number of blocks between half of what fits and all of it, but at least what's needed
    static int paddedLength(int needed, int budget) {
        int neededBlocks = (needed + PAD_TO - 1) / PAD_TO;
        int maxBlocks = budget / PAD_TO;
        int target = (maxBlocks + 1) / 2 + RANDOM.nextInt(maxBlocks - (maxBlocks + 1) / 2 + 1);
        return Math.max(neededBlocks, target) * PAD_TO;
    }

    // The nonce of a token we made (as Message.nonce has it), or null if it isn't one
    public static @Nullable String nonceOf(String token) {
        try {
            byte[] sealed = DECODER.decode(token);
            return sealed.length < NONCE_BYTES ? null : ENCODER.encodeToString(Arrays.copyOf(sealed, NONCE_BYTES));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Optional<Message> decrypt(SecretKey key, String token) {
        return decrypt(key, token, Instant.now());
    }

    static Optional<Message> decrypt(SecretKey key, String token, Instant now) {
        byte[] sealed;
        try {
            sealed = DECODER.decode(token);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // The decoder ignores the unused bits of the last character, so several spellings decode the same:
        // only the one we would write counts, or a replay could pass as a different token
        if (sealed.length < NONCE_BYTES + TAG_BYTES + 8 || !ENCODER.encodeToString(sealed).equals(token)) {
            return Optional.empty();
        }
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, sealed, 0, NONCE_BYTES));
            plain = cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            // Wrong key, not one of ours, or tampered with
            return Optional.empty();
        }
        String nonce = ENCODER.encodeToString(Arrays.copyOf(sealed, NONCE_BYTES));
        return switch (plain[0] & 0x0F) {
            case 1 -> readVersion1(plain, nonce);
            case 2 -> readVersion2Or3(plain, 2, VERSION_2_HEADER_BYTES, now, nonce);
            case VERSION -> readVersion2Or3(plain, VERSION, HEADER_BYTES, now, nonce);
            default -> Optional.empty();
        };
    }

    // version 2: version (1) | parts (1) | minutes (3) | sender check (4); version 3 adds the message id and a
    // longer check (see the top)
    private static Optional<Message> readVersion2Or3(byte[] plain, int version, int headerBytes, Instant now, String nonce) {
        if (plain.length < headerBytes + 1) {
            return Optional.empty();
        }
        ByteBuffer buffer = ByteBuffer.wrap(plain);
        boolean compressed = (buffer.get() & COMPRESSED) != 0;
        int parts = Byte.toUnsignedInt(buffer.get());
        int minutes = (Byte.toUnsignedInt(buffer.get()) << 16) | (Byte.toUnsignedInt(buffer.get()) << 8) | Byte.toUnsignedInt(buffer.get());
        int messageId = version >= 3 ? buffer.getInt() : 0;
        long check = version >= 3 ? buffer.getLong() : buffer.getInt();

        // Strip 0x80 0x00... padding
        int end = plain.length - 1;
        while (end >= headerBytes && plain[end] == 0) {
            end--;
        }
        if (end < headerBytes || plain[end] != (byte) 0x80) {
            return Optional.empty();
        }
        byte[] payload = Arrays.copyOfRange(plain, headerBytes, end);
        Optional<byte[]> text = compressed ? inflate(payload) : Optional.of(payload);
        if (text.isEmpty()) {
            return Optional.empty();
        }

        // The 24-bit minute count wraps; take the occurrence closest to now
        long nowMinutes = now.getEpochSecond() / 60;
        long sentMinutes = nowMinutes - ((nowMinutes - minutes) & 0xFFFFFF);
        if (nowMinutes - sentMinutes > 0x800000) {
            sentMinutes += 0x1000000;
        }
        return Optional.of(new Message(null, check, Instant.ofEpochSecond(sentMinutes * 60), parts >>> 4, parts & 0x0F,
            new String(text.get(), StandardCharsets.UTF_8), version, messageId, nonce));
    }

    // version (1) | unix seconds (4) | part (1) | part count (1) | name length (1) | name | text | zeros
    private static Optional<Message> readVersion1(byte[] plain, String nonce) {
        ByteBuffer buffer = ByteBuffer.wrap(plain);
        buffer.get();
        Instant sentAt = Instant.ofEpochSecond(Integer.toUnsignedLong(buffer.getInt()));
        int part = Byte.toUnsignedInt(buffer.get());
        int parts = Byte.toUnsignedInt(buffer.get());
        int nameLength = Byte.toUnsignedInt(buffer.get());
        if (nameLength > buffer.remaining()) {
            return Optional.empty();
        }
        byte[] name = new byte[nameLength];
        buffer.get(name);
        int end = plain.length;
        while (end > buffer.position() && plain[end - 1] == 0) {
            end--;
        }
        String sender = new String(name, StandardCharsets.UTF_8);
        String text = new String(plain, buffer.position(), end - buffer.position(), StandardCharsets.UTF_8);
        return Optional.of(new Message(sender, senderCheck(sender), sentAt, part, parts, text, 1, 0, nonce));
    }

    // Version 2's check: 32 bits, few enough that a made-up name with the same check can be found in seconds
    static int senderCheck(String name) {
        return ByteBuffer.wrap(sha256("privacyplus/sender/" + name.toLowerCase(Locale.ROOT))).getInt();
    }

    // Lets receivers check which name on the line wrote the message without spelling the name out
    static long senderCheck64(String name) {
        return ByteBuffer.wrap(sha256("privacyplus/sender/v3/" + name.toLowerCase(Locale.ROOT))).getLong();
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] seal(SecretKey key, byte[] plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, nonce));
            byte[] sealed = new byte[NONCE_BYTES + plain.length + TAG_BYTES];
            System.arraycopy(nonce, 0, sealed, 0, NONCE_BYTES);
            cipher.doFinal(plain, 0, plain.length, sealed, NONCE_BYTES);
            return sealed;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable", e);
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setDictionary(DICTIONARY);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    // Bounded, so a forged-but-authenticated (i.e. from a channel member) token can't inflate without limit
    private static Optional<byte[]> inflate(byte[] data) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setDictionary(DICTIONARY);
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_TEXT_BYTES) {
                    return Optional.empty();
                }
            }
            return inflater.finished() ? Optional.of(out.toByteArray()) : Optional.empty();
        } catch (DataFormatException e) {
            return Optional.empty();
        } finally {
            inflater.end();
        }
    }

    // Splits on code point boundaries so no chunk ends in half a character
    private static List<byte[]> splitUtf8(String text, int maxBytes) {
        List<byte[]> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (currentBytes + size > maxBytes) {
                chunks.add(current.toString().getBytes(StandardCharsets.UTF_8));
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(character);
            currentBytes += size;
            i += Character.charCount(codePoint);
        }
        chunks.add(current.toString().getBytes(StandardCharsets.UTF_8));
        return chunks;
    }
}
