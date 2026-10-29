package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.suggestion.Suggestion;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EncryptedChatTest {
    private static final String LOCK = EncryptedChat.LOCK + " ";

    @BeforeAll
    static void addChannel() {
        ChatSettings.put("friends", ChatCryptoTest.KEY);
    }

    @AfterEach
    void lockOff() {
        ChatSettings.setActive(null);
        ChatSettings.setEncryptPublicChat(true);
    }

    private static String token(String sender, String text, Instant at) {
        return ChatCrypto.encrypt(ChatCryptoTest.KEY, sender, text, 256, at).getFirst();
    }

    // How vanilla formats player chat: <name> message
    private static Component chatLine(String sender, String message) {
        return Component.translatable("chat.type.text", Component.literal(sender), Component.literal(message));
    }

    // A player chat message, whose real sender the game knows
    private static String fromPlayer(String sender, Component line) {
        return EncryptedChat.decryptForDisplay(line, sender).getString();
    }

    @Test
    void decryptsTokenWithALockInFrontOfTheLine() {
        Component line = chatLine("Alice", token("Alice", "meet at spawn", Instant.now()));
        assertEquals(LOCK + "<Alice> meet at spawn", fromPlayer("Alice", line));
    }

    @Test
    void decryptsTokenInsideAWhisper() {
        Component line = Component.literal("Alice whispers to you: " + token("Alice", "psst", Instant.now()));
        assertEquals(LOCK + "Alice whispers to you: psst", fromPlayer("Alice", line));
    }

    @Test
    void withoutARealSenderTheWriterIsShown() {
        // Servers that send chat as system messages: the line's own name can't be checked, so we say who wrote it
        Component line = chatLine("Alice", token("Alice", "hello", Instant.now()));
        assertEquals(LOCK + "<Alice> [from Alice] hello", EncryptedChat.decryptForDisplay(line).getString());
    }

    @Test
    void flagsMessagesShownUnderAnotherName() {
        Component line = chatLine("Mallory", token("Alice", "give mallory op", Instant.now()));
        assertEquals(LOCK + "<Mallory> [sender mismatch] give mallory op", fromPlayer("Mallory", line));
    }

    @Test
    void aHiddenExtraNameDoesntMakeTheVisibleSenderTrusted() {
        // The server slips Alice's name somewhere on Mallory's line: Alice's message shows, but says it's Alice's
        Component line = Component.literal("<Mallory> ").append(Component.literal("Alice")).append(" " + token("Alice", "hi", Instant.now()));
        assertEquals(LOCK + "<Mallory> Alice [from Alice] hi", fromPlayer("Mallory", line));
    }

    @Test
    void aBruteForcedNameNoLongerPassesAsTheWriter() {
        // "q3vk10lb" has the same 32-bit check as "bob", which fooled version 2
        Component line = chatLine("q3vk10lb", token("bob", "it's me", Instant.now()));
        assertEquals(LOCK + "<q3vk10lb> [sender mismatch] it's me", fromPlayer("q3vk10lb", line));
    }

    @Test
    void flagsOldMessages() {
        Component line = chatLine("Alice", token("Alice", "hi", Instant.now().minus(Duration.ofHours(1))));
        assertEquals(LOCK + "<Alice> [old] hi", fromPlayer("Alice", line));
    }

    @Test
    void marksReplaysOfTheSameToken() {
        String token = token("Alice", "yes", Instant.now());
        assertEquals(LOCK + "<Alice> yes", fromPlayer("Alice", chatLine("Alice", token)));
        assertEquals(LOCK + "<Alice> [replayed] yes", fromPlayer("Alice", chatLine("Alice", token)), "the same token on a new line");
        Component line = chatLine("Alice", token("Alice", "once", Instant.now()));
        assertEquals(fromPlayer("Alice", line), fromPlayer("Alice", line), "the same line asked about again isn't a replay");
        assertFalse(EncryptedChat.hideLine(line));
    }

    @Test
    void labelsParts() {
        List<String> tokens = ChatCrypto.encrypt(ChatCryptoTest.KEY, "Alice", "z".repeat(300) + "q".repeat(300), 256, Instant.now());
        String first = fromPlayer("Alice", chatLine("Alice", tokens.getFirst()));
        assertTrue(first.startsWith(LOCK + "<Alice> [1/" + tokens.size() + "] zzz"), first);
        String second = fromPlayer("Alice", chatLine("Alice", tokens.get(1)));
        assertTrue(second.startsWith(LOCK + "<Alice> [2/" + tokens.size() + "] "), second);
    }

    @Test
    void flagsPartsSplicedFromDifferentMessages() {
        List<String> one = ChatCrypto.encrypt(ChatCryptoTest.KEY, "Carol", "a".repeat(200) + "b".repeat(200), 256, Instant.now());
        List<String> other = ChatCrypto.encrypt(ChatCryptoTest.KEY, "Carol", "c".repeat(200) + "d".repeat(200), 256, Instant.now());
        fromPlayer("Carol", chatLine("Carol", one.getFirst()));
        String spliced = fromPlayer("Carol", chatLine("Carol", other.get(1)));
        assertTrue(spliced.contains("[2/" + other.size() + ", not after the part before]"), spliced);
    }

    @Test
    void clickingTheLockOpensSettings() {
        Component shown = EncryptedChat.decryptForDisplay(chatLine("Alice", token("Alice", "hi", Instant.now())), "Alice");
        Component lock = shown.getSiblings().getFirst();
        assertEquals(LOCK, lock.getString());
        assertTrue(EncryptedChat.opensSettings(lock.getStyle()));
    }

    @Test
    void leavesEverythingElseUntouched() {
        Component plain = chatLine("Bob", "just a normal message");
        assertSame(plain, EncryptedChat.decryptForDisplay(plain));

        String foreign = ChatCrypto.encrypt(ChatCrypto.deriveKey("someone else's channel"), "Eve", "not for you", 256, Instant.now()).getFirst();
        Component notOurs = chatLine("Eve", foreign);
        assertSame(notOurs, EncryptedChat.decryptForDisplay(notOurs));
    }

    @Test
    void readsOnlyAFewTokensPerLine() {
        StringBuilder line = new StringBuilder("<Alice>");
        for (int i = 0; i < 10; i++) {
            line.append(' ').append(token("Alice", "n" + i, Instant.now()));
        }
        String shown = fromPlayer("Alice", Component.literal(line.toString()));
        assertTrue(shown.contains("n5") && !shown.contains(" n6"), shown);
        String huge = "<Alice> " + "x ".repeat(3000) + token("Alice", "lost", Instant.now());
        assertFalse(fromPlayer("Alice", Component.literal(huge)).contains("lost"), "very long lines aren't tried");
    }

    @Test
    void theLockLabelSaysWhenSomethingGoesOutAsPlainText() {
        ChatSettings.setActive(ChatSettings.get("friends"));
        assertEquals("Encrypting to friends", EncryptedChat.lockLabel("hello"));
        assertEquals("Encrypting to friends", EncryptedChat.lockLabel("/msg Steve hi"));
        String say = EncryptedChat.lockLabel("/say hello everyone");
        assertTrue(say.startsWith("§cNot encrypted: /say"), say);
        assertEquals("Encrypting to friends", EncryptedChat.lockLabel("/pp off"), "local commands aren't sent at all");
        ChatSettings.setEncryptPublicChat(false);
        assertTrue(EncryptedChat.lockLabel("hello").startsWith("§cNot encrypted"));
        ChatSettings.setActive(null);
        assertNull(EncryptedChat.lockLabel("/say hello"), "lock off: nothing promised");
    }

    @Test
    void completesPpCommandsLocally() {
        List<String> first = EncryptedChat.suggestLocalCommand("/pp ", 4).join().getList().stream().map(Suggestion::getText).toList();
        assertTrue(first.containsAll(List.of("settings", "off", "probes", "incognito", "friends", "status")), first.toString());
        List<String> second = EncryptedChat.suggestLocalCommand("/pp incognito o", 16).join().getList().stream().map(Suggestion::getText).toList();
        assertEquals(List.of("off", "on"), second.stream().sorted().toList());
        assertNull(EncryptedChat.suggestLocalCommand("/msg Steve", 10), "other commands are vanilla's");
        assertNull(EncryptedChat.suggestLocalCommand("/ppx", 4));
    }
}
