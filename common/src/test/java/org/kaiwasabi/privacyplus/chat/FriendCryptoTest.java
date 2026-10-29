package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.SecretKey;
import org.kaiwasabi.privacyplus.chat.FriendCrypto.Identity;
import org.kaiwasabi.privacyplus.chat.FriendCrypto.PairKeys;
import org.junit.jupiter.api.Test;

class FriendCryptoTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final Identity ALICE = FriendCrypto.generate();
    private static final Identity BOB = FriendCrypto.generate();
    private static final Identity CAROL = FriendCrypto.generate();

    @Test
    void x25519MatchesRfc7748() {
        // RFC 7748, section 6.1
        Identity a = FriendCrypto.fromPrivate(HEX.parseHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"));
        Identity b = FriendCrypto.fromPrivate(HEX.parseHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"));
        assertArrayEquals(HEX.parseHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"), FriendCrypto.decode(a.publicKey()));
        assertArrayEquals(HEX.parseHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"), FriendCrypto.decode(b.publicKey()));
        byte[] shared = HEX.parseHex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
        assertArrayEquals(shared, FriendCrypto.agree(a.privateKey(), FriendCrypto.decode(b.publicKey())));
        assertArrayEquals(shared, FriendCrypto.agree(b.privateKey(), FriendCrypto.decode(a.publicKey())));
    }

    @Test
    void storedPrivateKeyGivesTheSameIdentity() {
        Identity again = FriendCrypto.fromPrivate(FriendCrypto.privateBytes(ALICE));
        assertEquals(ALICE.publicKey(), again.publicKey());
        assertEquals(43, ALICE.publicKey().length(), "32 bytes as unpadded base64");
        assertTrue(FriendCrypto.isPublicKey(ALICE.publicKey()));
    }

    @Test
    void bothSidesDeriveMatchingKeysOnePerDirection() {
        PairKeys alice = FriendCrypto.pairwiseKeys(ALICE, "Alice", BOB.publicKey(), "Bob");
        PairKeys bob = FriendCrypto.pairwiseKeys(BOB, "Bob", ALICE.publicKey(), "Alice");
        assertArrayEquals(alice.send().getEncoded(), bob.receive().getEncoded(), "what Alice sends, Bob opens");
        assertArrayEquals(alice.receive().getEncoded(), bob.send().getEncoded(), "and the other way");
        assertArrayEquals(alice.tag().getEncoded(), bob.tag().getEncoded());
        assertFalse(Arrays.equals(alice.send().getEncoded(), alice.receive().getEncoded()), "a message can't be bounced back as theirs");
        assertFalse(Arrays.equals(alice.send().getEncoded(), alice.tag().getEncoded()), "separate keys for encryption and the tag");
        assertEquals(32, alice.send().getEncoded().length);
        assertArrayEquals(alice.send().getEncoded(), FriendCrypto.pairwiseKeys(ALICE, "ALICE", BOB.publicKey(), "bob").send().getEncoded(),
            "Minecraft names are case-insensitive");
        assertFalse(alice.toString().contains("SecretKeySpec"), "no key material in logs: " + alice);
    }

    @Test
    void differentPairsAndNamesGiveDifferentKeys() {
        byte[] aliceBob = FriendCrypto.pairwiseKeys(ALICE, "Alice", BOB.publicKey(), "Bob").send().getEncoded();
        byte[] aliceCarol = FriendCrypto.pairwiseKeys(ALICE, "Alice", CAROL.publicKey(), "Carol").send().getEncoded();
        byte[] bobCarol = FriendCrypto.pairwiseKeys(BOB, "Bob", CAROL.publicKey(), "Carol").send().getEncoded();
        assertFalse(Arrays.equals(aliceBob, aliceCarol));
        assertFalse(Arrays.equals(aliceBob, bobCarol));
        assertFalse(Arrays.equals(aliceCarol, bobCarol));
        byte[] renamed = FriendCrypto.pairwiseKeys(ALICE, "Alice", BOB.publicKey(), "Mallory").send().getEncoded();
        assertFalse(Arrays.equals(aliceBob, renamed), "the keys are bound to the names");
    }

    @Test
    void weakAndMalformedKeysAreRefused() {
        assertNull(FriendCrypto.pairwiseKeys(ALICE, "Alice", FriendCrypto.encode(new byte[32]), "Zero"), "small-order point");
        byte[] one = new byte[32];
        one[0] = 1;
        assertNull(FriendCrypto.pairwiseKeys(ALICE, "Alice", FriendCrypto.encode(one), "One"), "small-order point");
        assertNull(FriendCrypto.pairwiseKeys(ALICE, "Alice", "not base64!", "Bob"));
        assertNull(FriendCrypto.pairwiseKeys(ALICE, "Alice", FriendCrypto.encode(new byte[16]), "Bob"), "wrong length");
        assertFalse(FriendCrypto.isPublicKey(FriendCrypto.encode(new byte[31])));
    }

    @Test
    void safetyCodeIsTheSameOnBothSides() {
        byte[] aliceNonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] bobNonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        String aliceSees = FriendCrypto.safetyCode(ALICE.publicKey(), aliceNonce, BOB.publicKey(), bobNonce);
        String bobSees = FriendCrypto.safetyCode(BOB.publicKey(), bobNonce, ALICE.publicKey(), aliceNonce);
        assertEquals(aliceSees, bobSees);
        assertTrue(aliceSees.matches("\\d{4} \\d{4} \\d{4} \\d{4}"), aliceSees);
        assertNotEquals(aliceSees, FriendCrypto.safetyCode(ALICE.publicKey(), aliceNonce, CAROL.publicKey(), bobNonce), "another key");
        assertNotEquals(aliceSees, FriendCrypto.safetyCode(ALICE.publicKey(), bobNonce, BOB.publicKey(), aliceNonce), "nonces swapped");
    }

    @Test
    void commitmentAndReplyTagCatchTampering() {
        byte[] nonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] commitment = FriendCrypto.commitment(ALICE.publicKey(), nonce);
        assertTrue(FriendCrypto.sameBytes(commitment, FriendCrypto.commitment(ALICE.publicKey(), nonce)));
        assertFalse(FriendCrypto.sameBytes(commitment, FriendCrypto.commitment(ALICE.publicKey(), FriendCrypto.randomBytes(16))), "other nonce");
        assertFalse(FriendCrypto.sameBytes(commitment, FriendCrypto.commitment(BOB.publicKey(), nonce)), "other key");

        SecretKey key = FriendCrypto.pairwiseKeys(ALICE, "Alice", BOB.publicKey(), "Bob").tag();
        SecretKey other = FriendCrypto.pairwiseKeys(ALICE, "Alice", CAROL.publicKey(), "Carol").tag();
        byte[] replyNonce = FriendCrypto.randomBytes(FriendCrypto.NONCE_BYTES);
        byte[] tag = FriendCrypto.replyTag(key, commitment, replyNonce);
        SecretKey bobSide = FriendCrypto.pairwiseKeys(BOB, "Bob", ALICE.publicKey(), "Alice").tag();
        assertTrue(FriendCrypto.sameBytes(tag, FriendCrypto.replyTag(bobSide, commitment, replyNonce)));
        assertFalse(FriendCrypto.sameBytes(tag, FriendCrypto.replyTag(other, commitment, replyNonce)), "only the key holder can make it");
    }

    @Test
    void friendCodesAreRandomAndEasyToType() {
        String code = FriendCrypto.newFriendCode();
        assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}"), code);
        assertTrue(FriendCrypto.CODE_CHARS * 5 >= 60, "at least 60 random bits");
        assertNotEquals(code, FriendCrypto.newFriendCode());
        assertEquals(code, FriendCrypto.normalizeCode(code));
        String typed = " " + code.toLowerCase(java.util.Locale.ROOT).replace("-", " ") + " ";
        assertEquals(code, FriendCrypto.normalizeCode(typed), "spaces and case don't matter");
        assertEquals("0111V-ABCDE-FGHJK", FriendCrypto.normalizeCode("OIl1v abcde fghjk"), "O reads as 0, I and L as 1");
        assertNull(FriendCrypto.normalizeCode("0011V-ABCDE-FGHJ"), "too short");
        assertNull(FriendCrypto.normalizeCode("0011V-ABCDE-FGHJKM"), "too long");
        assertNull(FriendCrypto.normalizeCode("0011U-ABCDE-FGHJK"), "U isn't used");
    }

    @Test
    void friendCodeKeysAreBoundToTheCodeAndItsMaker() {
        String code = "7K2MQ-X9DPA-4RW3Z";
        byte[] key = FriendCrypto.codeKey(code, "Alice").getEncoded();
        assertEquals(32, key.length);
        assertArrayEquals(key, FriendCrypto.codeKey("7k2mq x9dpa 4rw3z", "alice").getEncoded(), "typed differently, same key");
        assertFalse(Arrays.equals(key, FriendCrypto.codeKey(code, "Bob").getEncoded()), "only works towards its maker");
        assertFalse(Arrays.equals(key, FriendCrypto.codeKey("7K2MQ-X9DPA-4RW3Y", "Alice").getEncoded()), "another code");
    }
}
