package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class ChatCryptoTest {
    // Deriving is deliberately slow, so share keys across tests
    static final SecretKey KEY = ChatCrypto.deriveKey("correct horse battery staple ocean");
    private static final SecretKey OTHER_KEY = ChatCrypto.deriveKey("a different passphrase entirely");
    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
    // Made by an older release (format version 1) with KEY: sender Alice, sent at NOW
    private static final String VERSION_1_TOKEN = "GQvgJSA7l5sVIP-g1duuqwSnA-DuaQumiyGSTmrsJFza2NVrXfFxFC_0Xdn5vkxZQCHyugi-d2GMwbVTC1F8GXjzG7namxpigQ";
    // Made by the previous release (format version 2) with KEY: sender Alice, sent at NOW
    private static final String VERSION_2_TOKEN = "DDBootp0VGbU-xaOLdKAQCMNbq-bLNPoU5FClvoLhVWDXZTc5UqNAOhGZmDgVb63fubfZ770WKisqrh-koRTeRgZqRQC";
    // The /msg room for the longest names, which key exchanges and channel notes travel in
    private static final int MSG_LIMIT = 256 - "/msg ".length() - 16 - 1;

    private static ChatCrypto.Message decrypt(String token) {
        return ChatCrypto.decrypt(KEY, token, NOW).orElseThrow();
    }

    @Test
    void roundTrip() {
        List<String> tokens = ChatCrypto.encrypt(KEY, "Alice", "hello there", 256, NOW);
        assertEquals(1, tokens.size());
        String token = tokens.getFirst();
        assertTrue(ChatCrypto.TOKEN.matcher(token).matches(), "token must be found by the chat scanner");

        ChatCrypto.Message message = decrypt(token);
        assertEquals("hello there", message.text());
        assertEquals(NOW, message.sentAt());
        assertEquals(1, message.part());
        assertEquals(1, message.parts());
        assertEquals(ChatCrypto.VERSION, message.version());
        assertTrue(message.senderVerifiable());
        assertEquals(ChatCrypto.nonceOf(token), message.nonce());
    }

    @Test
    void senderCheckIdentifiesTheWriterWithoutNamingThem() {
        ChatCrypto.Message message = decrypt(ChatCrypto.encrypt(KEY, "Alice", "hi", 256, NOW).getFirst());
        assertTrue(message.writtenBy("Alice"));
        assertTrue(message.writtenBy("alice"), "Minecraft names are case-insensitive");
        assertFalse(message.writtenBy("Mallory"));
        assertEquals(null, message.sender(), "the name itself isn't in the message");
    }

    @Test
    void theSenderCheckIsTooLongToFakeWithAnotherName() {
        // A 32-bit collision found by brute force: version 2 can't tell these two apart, version 3 can
        assertEquals(ChatCrypto.senderCheck("bob"), ChatCrypto.senderCheck("q3vk10lb"));
        assertNotEquals(ChatCrypto.senderCheck64("bob"), ChatCrypto.senderCheck64("q3vk10lb"));
        ChatCrypto.Message message = decrypt(ChatCrypto.encrypt(KEY, "bob", "hi", 256, NOW).getFirst());
        assertTrue(message.writtenBy("Bob"));
        assertFalse(message.writtenBy("q3vk10lb"));
    }

    @Test
    void readsMessagesFromEarlierFormatsAsUnverified() {
        ChatCrypto.Message first = decrypt(VERSION_1_TOKEN);
        assertEquals("hello from the old format", first.text());
        assertEquals(NOW, first.sentAt());
        assertTrue(first.writtenBy("Alice"));
        assertFalse(first.senderVerifiable());

        ChatCrypto.Message second = decrypt(VERSION_2_TOKEN);
        assertEquals("hello from the second format", second.text());
        assertEquals(NOW, second.sentAt());
        assertTrue(second.writtenBy("Alice"));
        assertFalse(second.writtenBy("Mallory"));
        assertFalse(second.senderVerifiable(), "a 32-bit check can be matched by a made-up name");
    }

    @Test
    void compressedTextRoundTrips() {
        String text = "ok i found diamonds near the nether portal, bring your pickaxe and some food and torches, the cave is dark";
        List<String> tokens = ChatCrypto.encrypt(KEY, "Alice", text, 256, NOW);
        assertEquals(1, tokens.size());
        assertEquals(text, decrypt(tokens.getFirst()).text());
    }

    @Test
    void timestampSurvivesClockSkewAndAge() {
        String token = ChatCrypto.encrypt(KEY, "Alice", "hi", 256, NOW).getFirst();
        assertEquals(NOW, ChatCrypto.decrypt(KEY, token, NOW.plus(Duration.ofDays(400))).orElseThrow().sentAt(), "a year later");
        assertEquals(NOW, ChatCrypto.decrypt(KEY, token, NOW.minus(Duration.ofMinutes(5))).orElseThrow().sentAt(), "sender's clock ahead");
    }

    @Test
    void longMessagesSplitIntoPartsThatFitAndBelongTogether() {
        String text = "x".repeat(250) + " the end " + "日本語".repeat(40);
        List<String> tokens = ChatCrypto.encrypt(KEY, "Alice", text, 256, NOW);
        tokens.forEach(t -> assertTrue(t.length() <= 256, "token too long: " + t.length()));

        List<ChatCrypto.Message> parts = tokens.stream().map(ChatCryptoTest::decrypt).toList();
        assertEquals(text, parts.stream().map(ChatCrypto.Message::text).collect(Collectors.joining()));
        for (int i = 0; i < parts.size(); i++) {
            assertEquals(i + 1, parts.get(i).part());
            assertEquals(tokens.size(), parts.get(i).parts());
            assertEquals(parts.getFirst().messageId(), parts.get(i).messageId(), "every part carries the message's id");
        }
        assertEquals(tokens.size(), parts.stream().map(ChatCrypto.Message::nonce).distinct().count());
        int otherId = decrypt(ChatCrypto.encrypt(KEY, "Alice", text, 256, NOW).getFirst()).messageId();
        assertNotEquals(parts.getFirst().messageId(), otherId, "another message, another id");
    }

    @Test
    void neverSplitsInsideACharacter() {
        String text = "日本語のメッセージ🎉".repeat(20);
        List<String> tokens = ChatCrypto.encrypt(KEY, "アリス", text, 256, NOW);
        assertTrue(tokens.size() > 1);
        assertEquals(text, tokens.stream().map(t -> decrypt(t).text()).collect(Collectors.joining()));
    }

    @Test
    void respectsSmallerLimitForCommands() {
        ChatCrypto.encrypt(KEY, "Alice", "y".repeat(256), MSG_LIMIT, NOW).forEach(t -> assertTrue(t.length() <= MSG_LIMIT));
    }

    @Test
    void wrongKeyTamperingAndForeignTextAllFail() {
        String token = ChatCrypto.encrypt(KEY, "Alice", "secret", 256, NOW).getFirst();
        assertTrue(ChatCrypto.decrypt(OTHER_KEY, token).isEmpty(), "wrong key");

        char[] chars = token.toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        assertTrue(ChatCrypto.decrypt(KEY, new String(chars)).isEmpty(), "tampered");

        assertTrue(ChatCrypto.decrypt(KEY, "a".repeat(100)).isEmpty(), "random text");
        assertTrue(ChatCrypto.decrypt(KEY, "not base64 at all!").isEmpty(), "invalid base64");
    }

    @Test
    void onlyTheCanonicalSpellingOfATokenIsRead() {
        // The last character carries bits the bytes don't use; a decoder ignores them, so other spellings exist
        for (int attempt = 0; attempt < 20; attempt++) {
            String token = ChatCrypto.encrypt(KEY, "Alice", "yes", 256, NOW).getFirst();
            String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
            int last = alphabet.indexOf(token.charAt(token.length() - 1));
            int unused = switch (token.length() % 4) {
                case 2 -> 0b1111;
                case 3 -> 0b11;
                default -> 0;
            };
            if (unused == 0) {
                continue;
            }
            String respelled = token.substring(0, token.length() - 1) + alphabet.charAt(last ^ 1);
            assertTrue(ChatCrypto.decrypt(KEY, token, NOW).isPresent());
            assertTrue(ChatCrypto.decrypt(KEY, respelled, NOW).isEmpty(), "a replay can't pass as a different token");
            return;
        }
        throw new AssertionError("no token with spare bits in 20 tries");
    }

    // The length a token gets doesn't say what kind of message it is: a short chat line, a long one and a key
    // exchange all draw from the same spread
    @Test
    void lengthsDontGiveAwayWhatKindOfMessageItIs() {
        String exchange = EncryptedChat.CONTROL + "rekey 1 " + FriendMessages.newChannelId() + " 2 " + FriendCrypto.encode(new byte[32]);
        Set<Integer> shortChat = lengths("hi");
        Set<Integer> longerChat = lengths("see you at the nether hub in ten minutes, bring obsidian");
        Set<Integer> control = lengths(exchange);
        assertTrue(shortChat.size() >= 4, "short messages aren't all the same length: " + shortChat);
        assertTrue(shortChat.containsAll(control), "a key exchange has lengths chat has too: " + control + " vs " + shortChat);
        assertTrue(shortChat.containsAll(longerChat), longerChat + " vs " + shortChat);
        assertEquals(1, ChatCrypto.encrypt(KEY, "SixteenCharName_", exchange, MSG_LIMIT, NOW).size());
    }

    private static Set<Integer> lengths(String text) {
        Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            lengths.add(ChatCrypto.encrypt(KEY, "SixteenCharName_", text, MSG_LIMIT, NOW).getFirst().length());
        }
        return lengths;
    }

    @Test
    void messagesDontPrintTheirText() {
        ChatCrypto.Message message = decrypt(ChatCrypto.encrypt(KEY, "Alice", "a secret plan", 256, NOW).getFirst());
        assertFalse(message.toString().contains("secret"), message.toString());
    }
}
