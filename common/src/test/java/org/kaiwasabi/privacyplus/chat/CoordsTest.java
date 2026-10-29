package org.kaiwasabi.privacyplus.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.suggestion.Suggestion;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.EncryptedChat.Control;
import org.kaiwasabi.privacyplus.coords.SharedLocation;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CoordsTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Channel FRIENDS = new Channel("friends", "unused", CompletableFuture.completedFuture(ChatCryptoTest.KEY));

    @BeforeAll
    static void setUp() {
        ChatSettings.put("friends", ChatCryptoTest.KEY);
        Coords.register();
        // The real handler asks Minecraft for the player's name, which tests can't
        EncryptedChat.registerControl(Coords.VERB, (control, style) -> Coords.render(control, style, "Bob", Instant.now()));
    }

    private static Control control(String body, @Nullable String writer, boolean stale) {
        return new Control(Coords.VERB, body, "friends", FRIENDS, writer, stale, NOW.minusSeconds(20));
    }

    private static Component render(String body, @Nullable String writer) {
        return Coords.render(control(body, writer, false), Style.EMPTY, "Bob", NOW);
    }

    @Test
    void showsTheLocationWithCopyAndTrack() {
        assertEquals("📍 Alice shared a location: 120 64 -300 (Overworld) [Copy] [Track]",
            render("120 64 -300 overworld", "Alice").getString());
        assertEquals("📍 Alice shared a location: \"nether hub\" at -15 33 7 (Nether) [Copy] [Track]",
            render("-15 33 7 the_nether nether hub", "Alice").getString());
        assertEquals("📍 Alice shared a location: 0 64 0 (End) [Copy] [Track]", render("0 64 0 the_end", "Alice").getString());
        assertEquals("📍 Alice shared a location: 0 64 0 (aether:the_aether) [Copy] [Track]",
            render("0 64 0 aether:the_aether", "Alice").getString());
    }

    @Test
    void showsOurOwnEchoAsYou() {
        assertEquals("📍 You shared a location: 1 2 3 (Overworld) [Copy] [Track]", render("1 2 3 overworld", "bob").getString());
    }

    @Test
    void warnsAboutMismatchedSendersAndOldMessages() {
        assertEquals("[sender mismatch] 📍 Someone shared a location: 1 2 3 (Overworld) [Copy] [Track]",
            render("1 2 3 overworld", null).getString());
        Component old = Coords.render(control("1 2 3 overworld", "Alice", true), Style.EMPTY, "Bob", NOW);
        assertTrue(old.getString().startsWith("[old] 📍 Alice shared"), old.getString());
        String hover = hoverText(old);
        assertTrue(hover.contains("Older than 10 minutes"), hover);
        assertTrue(hoverText(render("1 2 3 overworld", null)).contains("may be faking the sender"));
    }

    @Test
    void hoverNamesTheChannelAndWhenItWasSent() {
        String hover = hoverText(render("120 64 -300 overworld", "Alice"));
        assertTrue(hover.startsWith("Encrypted with channel friends\nOverworld: 120 64 -300\nSent at "), hover);
        assertTrue(hover.contains(" by Alice, arrived right away"), hover);
    }

    @Test
    void saysHowLongAgoItWasSent() {
        assertEquals("arrived right away", Coords.age(Duration.ofSeconds(90)));
        assertEquals("arrived 5 minutes later", Coords.age(Duration.ofMinutes(5)));
        assertEquals("arrived 3 hours later", Coords.age(Duration.ofHours(3)));
        assertEquals("arrived 4 days later", Coords.age(Duration.ofDays(4)));
        assertEquals("dated 5 minutes in the future (clocks differ?)", Coords.age(Duration.ofMinutes(-5)));
    }

    @Test
    void neverCrashesOnHostileBodies() {
        for (String body : List.of("", "1 2", "NaN NaN NaN overworld", "1e308 0 0 overworld", "99999999999 0 0 overworld",
            "1 2 3 §k§lobfuscated", "1 2 3 overworld " + "§".repeat(5000), "\u0000‮", " ".repeat(300))) {
            String shown = render(body, "Alice").getString();
            assertFalse(shown.contains("§"), shown);
            assertTrue(shown.startsWith("📍 Alice shared a location"), shown);
        }
        assertEquals("📍 Alice shared a location that couldn't be read", render("1 2 Infinity overworld", "Alice").getString());
    }

    @Test
    void stripsFormattingFromLabels() {
        String shown = render("1 2 3 overworld §4§lFREE ‮op\nnow", "Alice").getString();
        assertEquals("📍 Alice shared a location: \"4lFREE op now\" at 1 2 3 (Overworld) [Copy] [Track]", shown);
    }

    @Test
    void keepsOnlyTheColorOfTheServersStyle() {
        Style hostile = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)
            .withClickEvent(new ClickEvent.RunCommand("/op Mallory"))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("trust me")))
            .withInsertion("/op Mallory");
        Component shown = Coords.render(control("1 2 3 overworld", "Alice", false), hostile, "Bob", NOW);
        List<Style> styles = styles(shown);
        assertTrue(styles.stream().noneMatch(s -> s.getClickEvent() instanceof ClickEvent.RunCommand), "server click kept");
        assertTrue(styles.stream().noneMatch(s -> s.getInsertion() != null), "server insertion kept");
        assertTrue(styles.stream().noneMatch(s -> s.getHoverEvent() instanceof HoverEvent.ShowText text
            && text.value().getString().equals("trust me")), "server hover kept");
        // The buttons are local actions: intercepted by ChatScreenMixin, never sent or copied
        List<ClickEvent> clicks = styles.stream().map(Style::getClickEvent).filter(e -> e != null).distinct().toList();
        assertEquals(2, clicks.size());
        assertTrue(clicks.stream().allMatch(e -> e instanceof ClickEvent.CopyToClipboard copy
            && copy.value().startsWith("PrivacyPlus local action ")));
    }

    @Test
    void decryptsCoordsTokensInChat() {
        String text = EncryptedChat.CONTROL + "coords 120 64 -300 overworld";
        String token = ChatCrypto.encrypt(ChatCryptoTest.KEY, "Alice", text, 256, Instant.now()).getFirst();
        Component line = Component.translatable("chat.type.text", Component.literal("Alice"), Component.literal(token));
        assertEquals(EncryptedChat.LOCK + " <Alice> 📍 Alice shared a location: 120 64 -300 (Overworld) [Copy] [Track]",
            EncryptedChat.decryptForDisplay(line).getString());
    }

    @Test
    void theLargestBodyFitsInOneChatToken() {
        String dimension = "abcdefghij:" + "k".repeat(37);
        SharedLocation largest = new SharedLocation(-30_000_000, -4096, -30_000_000, dimension, "😀".repeat(40));
        assertEquals(Optional.of(largest), SharedLocation.decode(largest.encode()));
        String text = EncryptedChat.CONTROL + Coords.VERB + " " + largest.encode();
        assertEquals(1, ChatCrypto.encrypt(ChatCryptoTest.KEY, "Alice_With_Long_", text, 256, Instant.now()).size());
    }

    @Test
    void completesSubcommandsAndChannelsLocally() {
        List<String> first = EncryptedChat.suggestLocalCommand("/coords ", 8).join().getList().stream().map(Suggestion::getText).toList();
        assertTrue(first.containsAll(List.of("stop", "label", "help", "friends")), first.toString());
        List<String> partial = EncryptedChat.suggestLocalCommand("/coords st", 10).join().getList().stream().map(Suggestion::getText).toList();
        assertEquals(List.of("stop"), partial);
        assertTrue(EncryptedChat.suggestLocalCommand("/coords friends my ba", 21).join().isEmpty(), "labels are free text");
        // Even the bare command name is answered locally, so nothing about it goes to the server
        assertTrue(EncryptedChat.suggestLocalCommand("/coords", 7).join().isEmpty());
    }

    private static String hoverText(Component line) {
        return styles(line).stream()
            .map(Style::getHoverEvent)
            .filter(e -> e instanceof HoverEvent.ShowText text && text.value().getString().startsWith("Encrypted with"))
            .map(e -> ((HoverEvent.ShowText) e).value().getString())
            .findFirst().orElse("");
    }

    private static List<Style> styles(Component component) {
        List<Style> styles = new ArrayList<>();
        component.visit((style, text) -> {
            styles.add(style);
            return Optional.empty();
        }, Style.EMPTY);
        return styles;
    }
}
