package org.kaiwasabi.privacyplus.chat;

import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// HKDF with HMAC-SHA256 (RFC 5869). The JDK has no HKDF before Java 24's preview API, and the mod
// must run on the Java 21 that Forge's mixin level assumes, so this is the few lines it takes.
final class Hkdf {
    static final int HASH_BYTES = 32;

    private Hkdf() {
    }

    static byte[] derive(byte[] salt, byte[] inputKey, byte[] info, int length) {
        return expand(extract(salt, inputKey), info, length);
    }

    static byte[] extract(byte[] salt, byte[] inputKey) {
        // An empty salt means HashLen zero bytes; HMAC pads short keys with zeros anyway, and the JDK
        // refuses empty keys
        return hmac(salt.length == 0 ? new byte[HASH_BYTES] : salt, inputKey);
    }

    static byte[] expand(byte[] pseudoRandomKey, byte[] info, int length) {
        if (length < 0 || length > 255 * HASH_BYTES) {
            throw new IllegalArgumentException("HKDF output length " + length);
        }
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        for (int done = 0, counter = 1; done < length; counter++) {
            byte[] input = new byte[block.length + info.length + 1];
            System.arraycopy(block, 0, input, 0, block.length);
            System.arraycopy(info, 0, input, block.length, info.length);
            input[input.length - 1] = (byte) counter;
            block = hmac(pseudoRandomKey, input);
            int n = Math.min(block.length, length - done);
            System.arraycopy(block, 0, out, done, n);
            done += n;
        }
        return out;
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
