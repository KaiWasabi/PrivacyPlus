package org.kaiwasabi.privacyplus.coords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharedLocationTest {
    @Test
    void roundTripsWithShortDimensionIds() {
        SharedLocation home = new SharedLocation(120, 64, -300, SharedLocation.OVERWORLD, null);
        assertEquals("120 64 -300 overworld", home.encode());
        assertEquals(Optional.of(home), SharedLocation.decode(home.encode()));

        SharedLocation fortress = new SharedLocation(-15, 33, 7, "the_nether", "fortress");
        assertEquals("-15 33 7 the_nether fortress", fortress.encode());
        assertEquals(SharedLocation.NETHER, fortress.dimension());
        assertEquals(Optional.of(fortress), SharedLocation.decode(fortress.encode()));

        SharedLocation modded = new SharedLocation(0, -60, 0, "aether:the_aether", "sky island");
        assertEquals("0 -60 0 aether:the_aether sky island", modded.encode());
        assertEquals(Optional.of(modded), SharedLocation.decode(modded.encode()));
    }

    @Test
    void acceptsFullVanillaIdsAndExtraSpaces() {
        SharedLocation decoded = SharedLocation.decode("  1   2  3   minecraft:the_end    my    base  ").orElseThrow();
        assertEquals(new SharedLocation(1, 2, 3, SharedLocation.END, "my base"), decoded);
        assertEquals("my base", decoded.label());
    }

    @Test
    void acceptsTheWorldBorderAndBuildLimits() {
        assertTrue(SharedLocation.decode("29999984 2031 -29999984 overworld").isPresent());
        assertTrue(SharedLocation.decode("-30000000 -4096 30000000 overworld").isPresent());
        assertTrue(SharedLocation.decode("-0 0 0 overworld").isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "1 2 3",
        "1 2 overworld",
        "a b c overworld",
        "1.5 2 3 overworld",
        "NaN 2 3 overworld",
        "Infinity 2 3 overworld",
        "1e9 2 3 overworld",
        "+1 2 3 overworld",
        "0x10 2 3 overworld",
        "١٢٣ 2 3 overworld",
        "99999999999999999999 2 3 overworld",
        "2147483648 2 3 overworld",
        "-2147483648 2 3 overworld",
        "30000001 0 0 overworld",
        "0 0 -30000001 overworld",
        "0 4097 0 overworld",
        "0 -5000 0 overworld",
        "1\t2 3 overworld",
        "1 2 3 Overworld",
        "1 2 3 minecraft:",
        "1 2 3 :",
        "1 2 3 a:b:c",
        "1 2 3 §coverworld",
        "1 2 3 ../../etc/passwd!",
    })
    void rejectsMalformedBodies(String body) {
        assertEquals(Optional.empty(), SharedLocation.decode(body), body);
    }

    @Test
    void rejectsOverlongDimensionsAndBodies() {
        assertEquals(Optional.empty(), SharedLocation.decode("1 2 3 " + "a".repeat(200)));
        assertEquals(Optional.empty(), SharedLocation.decode("1 2 3 overworld " + "x".repeat(10_000)));
        assertThrows(IllegalArgumentException.class, () -> new SharedLocation(1, 2, 3, "mod:" + "a".repeat(60), null));
    }

    @Test
    void stripsFormattingAndControlCharactersFromLabels() {
        assertEquals("4lRed text", label("§4§lRed text"));
        assertEquals("evil", label("‮evil"));
        assertEquals("a b", label("a\nb"));
        assertEquals("a b", label("a\r\n\t b"));
        assertEquals("x", label("x"));
        assertEquals("x", label("​x‍"));
        assertEquals("x", label("\uD800x"));
        assertEquals("x", label("\u0000\u0007\u001Bx"));
        assertNull(SharedLocation.decode("1 2 3 overworld §§‮").orElseThrow().label());
    }

    @Test
    void keepsOrdinaryUnicodeLabels() {
        assertEquals("Café ☕ base", label("Café ☕ base"));
        assertEquals("日本の家", label("日本の家"));
    }

    @Test
    void limitsLabelLength() {
        assertEquals("x".repeat(SharedLocation.MAX_LABEL_CHARS), label("x".repeat(400)));
        String emoji = label("😀".repeat(40));
        assertTrue(emoji.getBytes(StandardCharsets.UTF_8).length <= SharedLocation.MAX_LABEL_BYTES, emoji);
        assertEquals(SharedLocation.MAX_LABEL_BYTES / 4, emoji.codePointCount(0, emoji.length()));
        // Cut between words, never mid-character
        assertFalse(label("ab " + "é".repeat(40)).endsWith(" "));
    }

    @Test
    void namesDimensions() {
        assertEquals("Overworld", SharedLocation.dimensionName(SharedLocation.OVERWORLD));
        assertEquals("Nether", SharedLocation.dimensionName(SharedLocation.NETHER));
        assertEquals("End", SharedLocation.dimensionName(SharedLocation.END));
        assertEquals("aether:the_aether", SharedLocation.dimensionName("aether:the_aether"));
        assertEquals("custom", SharedLocation.dimensionName("minecraft:custom"));
        assertEquals("Nether", SharedLocation.decode("0 0 0 the_nether").orElseThrow().dimensionName());
    }

    @Test
    void copiesPlainCoordinates() {
        assertEquals("120 64 -300", new SharedLocation(120, 64, -300, SharedLocation.OVERWORLD, "base").coordinates());
    }

    private static String label(String raw) {
        return SharedLocation.decode("0 0 0 overworld " + raw).orElseThrow().label();
    }
}
