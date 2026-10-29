package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

// The SHA-256 test vectors of RFC 5869, appendix A (cases 1 to 3)
class HkdfTest {
    private static final HexFormat HEX = HexFormat.of();

    private static byte[] range(int from, int to) {
        byte[] bytes = new byte[to - from];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (from + i);
        }
        return bytes;
    }

    private static void check(byte[] salt, byte[] ikm, byte[] info, int length, String prk, String okm) {
        assertArrayEquals(HEX.parseHex(prk), Hkdf.extract(salt, ikm), "PRK");
        assertArrayEquals(HEX.parseHex(okm), Hkdf.derive(salt, ikm, info, length), "OKM");
    }

    @Test
    void basicCase() {
        check(HEX.parseHex("000102030405060708090a0b0c"), HEX.parseHex("0b".repeat(22)), HEX.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42,
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    @Test
    void longerInputsAndOutput() {
        check(range(0x60, 0xb0), range(0x00, 0x50), range(0xb0, 0x100), 82,
            "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244",
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71"
                + "cc30c58179ec3e87c14c01d5c1f3434f1d87");
    }

    @Test
    void emptySaltAndInfo() {
        check(new byte[0], HEX.parseHex("0b".repeat(22)), new byte[0], 42,
            "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04",
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
    }
}
