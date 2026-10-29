package org.kaiwasabi.privacyplus.chat;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.XECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

// Long-term X25519 identities and what two friends derive from them: their keys (X25519, then HKDF-SHA256
// bound to both public keys and both names: one key per direction, so a message can only have come from the
// side whose send key opens it, and a separate key for the reply tag), and the safety code they compare outside
// Minecraft. Public keys travel as unpadded URL-safe base64 of the 32-byte RFC 7748 encoding, and only
// ever inside encrypted messages: under a channel key both players have, or under a one-time friend code.
//
// The safety code also covers a random nonce from each side, and the side that starts commits to its
// nonce before seeing the other's. A server in the middle therefore can't search offline for keys that
// give both victims the same code: each attempt succeeds with probability 1 in 10^16.
public final class FriendCrypto {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 16;
    static final int TAG_BYTES = 16;
    // Friend codes: 15 characters of Crockford's base32 (no I, L, O or U to misread), 75 random bits, shown
    // as three groups of five
    static final String CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    static final int CODE_CHARS = 15;
    // As slow as passphrase keys: a server that recorded a key exchange can't find the code by guessing
    private static final int CODE_KDF_ITERATIONS = 600_000;
    private static final String CODE_SALT = "privacyplus/friend-code/v1/";
    private static final byte[] FRIEND_LABEL = "privacyplus/friend/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BASE_POINT = new byte[KEY_BYTES];
    private static final BigInteger FIELD_PRIME = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final long CODE_MODULUS = 10_000_000_000_000_000L;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        BASE_POINT[0] = 9;
    }

    // publicKey is the base64 form, which is also how friends are identified
    public record Identity(PrivateKey privateKey, String publicKey) {
        @Override
        public String toString() {
            return "Identity[" + this.publicKey + "]";
        }
    }

    // What two friends share. Each side encrypts with its send key, which is the other side's receive key; a
    // message opened with our receive key can only have been written by the friend, never reflected from us.
    public record PairKeys(SecretKey send, SecretKey receive, SecretKey tag) {
        @Override
        public String toString() {
            return "PairKeys[redacted]";
        }
    }

    private FriendCrypto() {
    }

    public static Identity generate() {
        try {
            XECPrivateKey key = (XECPrivateKey) KeyPairGenerator.getInstance("X25519").generateKeyPair().getPrivate();
            return fromPrivate(key.getScalar().orElseThrow());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    // scalar is the 32-byte private key as stored; the public key is computed from it
    public static Identity fromPrivate(byte[] scalar) {
        if (scalar.length != KEY_BYTES) {
            throw new IllegalArgumentException("X25519 private keys are 32 bytes");
        }
        try {
            PrivateKey key = KeyFactory.getInstance("X25519").generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar.clone()));
            byte[] publicKey = agree(key, BASE_POINT);
            if (publicKey == null) {
                throw new IllegalArgumentException("Not a usable private key");
            }
            return new Identity(key, encode(publicKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("X25519 unavailable", e);
        }
    }

    static byte[] privateBytes(Identity identity) {
        return ((XECPrivateKey) identity.privateKey()).getScalar().orElseThrow();
    }

    public static boolean isPublicKey(String text) {
        byte[] bytes = decode(text);
        return bytes != null && bytes.length == KEY_BYTES;
    }

    // The X25519 shared secret, or null if theirPublic is malformed or a weak point (all-zero result)
    static byte @Nullable [] agree(PrivateKey mine, byte[] theirPublic) {
        if (theirPublic.length != KEY_BYTES) {
            return null;
        }
        try {
            byte[] bigEndian = new byte[KEY_BYTES];
            for (int i = 0; i < KEY_BYTES; i++) {
                bigEndian[i] = theirPublic[KEY_BYTES - 1 - i];
            }
            bigEndian[0] &= 0x7F; // RFC 7748: ignore the top bit
            BigInteger u = new BigInteger(1, bigEndian).mod(FIELD_PRIME);
            PublicKey key = KeyFactory.getInstance("X25519").generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u));
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(mine);
            agreement.doPhase(key, true);
            byte[] secret = agreement.generateSecret();
            return isAllZero(secret) ? null : secret;
        } catch (GeneralSecurityException | IllegalStateException e) {
            // The JDK refuses small-order points
            return null;
        }
    }

    // The keys two friends share, or null if theirKey isn't a usable public key. Names are bound as they were
    // when the keys were exchanged (lowercased: Minecraft names are case-insensitive), so a later name change
    // doesn't change the keys.
    public static @Nullable PairKeys pairwiseKeys(Identity mine, String myName, String theirKey, String theirName) {
        byte[] theirs = decode(theirKey);
        if (theirs == null) {
            return null;
        }
        byte[] shared = agree(mine.privateKey(), theirs);
        if (shared == null) {
            return null;
        }
        byte[] ours = decode(mine.publicKey());
        boolean mineFirst = Arrays.compareUnsigned(ours, theirs) <= 0;
        byte[] pair = join(mineFirst ? ours : theirs, name(mineFirst ? myName : theirName), mineFirst ? theirs : ours,
            name(mineFirst ? theirName : myName));
        byte[] secret = Hkdf.extract(FRIEND_LABEL, shared);
        SecretKey fromFirst = new SecretKeySpec(Hkdf.expand(secret, join(FRIEND_LABEL, pair, label("from the first key")), KEY_BYTES), "AES");
        SecretKey fromSecond = new SecretKeySpec(Hkdf.expand(secret, join(FRIEND_LABEL, pair, label("from the second key")), KEY_BYTES), "AES");
        SecretKey tag = new SecretKeySpec(Hkdf.expand(secret, join(FRIEND_LABEL, pair, label("reply tag")), KEY_BYTES), "HmacSHA256");
        return mineFirst ? new PairKeys(fromFirst, fromSecond, tag) : new PairKeys(fromSecond, fromFirst, tag);
    }

    // What the side starting a key exchange sends instead of its nonce, which it reveals at the end
    public static byte[] commitment(String publicKey, byte[] nonce) {
        byte[] key = decode(publicKey);
        if (key == null) {
            throw new IllegalArgumentException("Not a public key");
        }
        return Arrays.copyOf(sha256(join("privacyplus/commit/v1".getBytes(StandardCharsets.UTF_8), key, nonce)), TAG_BYTES);
    }

    // Proves the reply's sender holds its private key (it derived the pair's tag key) and ties the reply to
    // the request it answers
    public static byte[] replyTag(SecretKey tagKey, byte[] commitment, byte[] replyNonce) {
        byte[] mac = Hkdf.hmac(tagKey.getEncoded(), join("privacyplus/reply/v1".getBytes(StandardCharsets.UTF_8), commitment, replyNonce));
        return Arrays.copyOf(mac, TAG_BYTES);
    }

    public static boolean sameBytes(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    // 16 digits in groups of four, the same on both sides: each side's key travels with its own nonce,
    // ordered by key
    public static String safetyCode(String keyA, byte[] nonceA, String keyB, byte[] nonceB) {
        byte[] a = decode(keyA);
        byte[] b = decode(keyB);
        boolean aFirst = Arrays.compareUnsigned(a, b) <= 0;
        byte[] digest = sha256(join("privacyplus/safety/v1".getBytes(StandardCharsets.UTF_8),
            aFirst ? a : b, aFirst ? nonceA : nonceB, aFirst ? b : a, aFirst ? nonceB : nonceA));
        String digits = String.format(Locale.ROOT, "%016d", Long.remainderUnsigned(ByteBuffer.wrap(digest).getLong(), CODE_MODULUS));
        return digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 12) + " " + digits.substring(12);
    }

    // A short, stable label for a public key, e.g. to tell two keys apart in a warning
    public static String fingerprint(String publicKey) {
        byte[] bytes = decode(publicKey);
        byte[] digest = sha256(bytes == null ? new byte[0] : bytes);
        return String.format(Locale.ROOT, "%02X%02X-%02X%02X", digest[0], digest[1], digest[2], digest[3]);
    }

    // A new friend code, like "7K2MQ-X9DPA-4RW3Z"
    public static String newFriendCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_CHARS; i++) {
            if (i > 0 && i % 5 == 0) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    // A typed or pasted code in its canonical form, or null if it isn't one. Spaces, dashes and case don't
    // matter, and the letters base32 leaves out read as what they look like (O as 0, I and L as 1).
    public static @Nullable String normalizeCode(String typed) {
        String chars = typed.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "").replace('O', '0').replace('I', '1').replace('L', '1');
        if (chars.length() != CODE_CHARS || !chars.chars().allMatch(c -> CODE_ALPHABET.indexOf(c) >= 0)) {
            return null;
        }
        return chars.substring(0, 5) + "-" + chars.substring(5, 10) + "-" + chars.substring(10);
    }

    // The key a key exchange started with a friend code travels under. ownerName is the player who made the
    // code (the one the request goes to), so a code only works towards them. Deliberately slow.
    public static SecretKey codeKey(String code, String ownerName) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            throw new IllegalArgumentException("Not a friend code");
        }
        byte[] salt = (CODE_SALT + ownerName.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8);
        try {
            PBEKeySpec spec = new PBEKeySpec(normalized.replace("-", "").toCharArray(), salt, CODE_KDF_ITERATIONS, KEY_BYTES * 8);
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    public static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String encode(byte[] bytes) {
        return ENCODER.encodeToString(bytes);
    }

    // Null if text isn't unpadded URL-safe base64
    public static byte @Nullable [] decode(String text) {
        if (!text.matches("[A-Za-z0-9_-]*")) {
            return null;
        }
        try {
            return DECODER.decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static SecretKey aesKey(byte[] bytes) {
        return new SecretKeySpec(bytes, "AES");
    }

    private static byte[] label(String label) {
        return label.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] name(String name) {
        return name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
    }

    // Each part prefixed with its length, so no two different inputs give the same bytes
    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part.length >>> 8);
            out.write(part.length);
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isAllZero(byte[] bytes) {
        int bits = 0;
        for (byte b : bytes) {
            bits |= b;
        }
        return bits == 0;
    }
}
