package org.kaiwasabi.privacyplus.coords;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.coords.CoordsTracker.Update;
import org.kaiwasabi.privacyplus.coords.Navigation.Reading;
import org.kaiwasabi.privacyplus.coords.Navigation.Scale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CoordsTrackerTest {
    private static final Instant START = Instant.parse("2026-10-30T12:00:00Z");
    private static final SharedLocation BASE = new SharedLocation(100, 64, 100, SharedLocation.OVERWORLD, "base");

    @BeforeEach
    void reset() {
        CoordsTracker.clear();
    }

    private static Reading reading(Scale scale, double distance) {
        return new Reading(scale, distance, 0, "↑");
    }

    @Test
    void tracksOneLocationAtATime() {
        CoordsTracker.track(BASE, "base", START);
        SharedLocation other = new SharedLocation(0, 0, 0, SharedLocation.NETHER, null);
        CoordsTracker.track(other, "Alice's location", START);
        assertEquals(other, CoordsTracker.target().location());
        assertTrue(CoordsTracker.stop());
        assertNull(CoordsTracker.target());
        assertFalse(CoordsTracker.stop());
    }

    @Test
    void stopsOnArrival() {
        CoordsTracker.track(BASE, "base", START);
        assertEquals(Update.TRACKING, CoordsTracker.update(START.plusSeconds(5), reading(Scale.SAME, 4.5)));
        assertNotNull(CoordsTracker.target());
        assertEquals(Update.ARRIVED, CoordsTracker.update(START.plusSeconds(10), reading(Scale.SAME, 3.9)));
        assertNull(CoordsTracker.target());
        assertEquals(Update.NONE, CoordsTracker.update(START.plusSeconds(11), reading(Scale.SAME, 0)));
    }

    @Test
    void reachingTheMatchingSpotInAnotherDimensionIsNotArriving() {
        CoordsTracker.track(BASE, "base", START);
        assertEquals(Update.TRACKING, CoordsTracker.update(START.plusSeconds(5), reading(Scale.OVERWORLD_IN_NETHER, 0)));
        assertEquals(Update.TRACKING, CoordsTracker.update(START.plusSeconds(5), new Reading(Scale.NONE, Double.NaN, Double.NaN, "")));
    }

    @Test
    void stopsAfterThirtyMinutes() {
        CoordsTracker.track(BASE, "base", START);
        assertEquals(Update.TRACKING, CoordsTracker.update(START.plus(Duration.ofMinutes(29)), reading(Scale.SAME, 100)));
        assertEquals(Update.EXPIRED, CoordsTracker.update(START.plus(Duration.ofMinutes(30)), reading(Scale.SAME, 100)));
        assertNull(CoordsTracker.target());
    }

    @Test
    void leavingTheWorldClearsEverything() {
        CoordsTracker.track(BASE, "base", START);
        CoordsTracker.clear();
        assertNull(CoordsTracker.target());
    }

    @Test
    void showsNameArrowDistanceAndHeight() {
        CoordsTracker.Target target = new CoordsTracker.Target(BASE, "Alice's base", START);
        List<String> lines = strings(CoordsTracker.hudLines(target, new Reading(Scale.SAME, 120.4, -14.2, "↗")));
        assertEquals(List.of("📍 Alice's base  ↗ 120 blocks, 14 below"), lines);
        assertEquals(List.of("📍 Alice's base  ↑ 1 block"), strings(CoordsTracker.hudLines(target, new Reading(Scale.SAME, 1.2, 1, "↑"))));
        assertEquals(List.of("📍 Alice's base  ← 30 blocks, 5 above"),
            strings(CoordsTracker.hudLines(target, new Reading(Scale.SAME, 30, 5, "←"))));
    }

    @Test
    void saysWhenTheLocationIsInAnotherDimension() {
        CoordsTracker.Target target = new CoordsTracker.Target(BASE, "base", START);
        List<String> nether = strings(CoordsTracker.hudLines(target, new Reading(Scale.OVERWORLD_IN_NETHER, 50, 0, "→")));
        assertEquals("📍 base  → 50 blocks", nether.getFirst());
        String hint = nether.get(1);
        assertTrue(hint.startsWith("In the Overworld") && hint.contains("Nether") && hint.contains("1:8"), hint);

        CoordsTracker.Target fortress = new CoordsTracker.Target(new SharedLocation(1, 2, 3, SharedLocation.NETHER, null), "fortress", START);
        List<String> overworld = strings(CoordsTracker.hudLines(fortress, new Reading(Scale.NETHER_IN_OVERWORLD, 400, 0, "↓")));
        assertTrue(overworld.get(1).startsWith("In the Nether") && overworld.get(1).contains("8:1"), overworld.get(1));

        CoordsTracker.Target end = new CoordsTracker.Target(new SharedLocation(1, 2, 3, SharedLocation.END, null), "portal", START);
        assertEquals(List.of("📍 portal", "In the End: go there to see the way"),
            strings(CoordsTracker.hudLines(end, new Reading(Scale.NONE, Double.NaN, Double.NaN, ""))));

        CoordsTracker.Target modded = new CoordsTracker.Target(new SharedLocation(1, 2, 3, "aether:the_aether", null), "island", START);
        assertEquals("In aether:the_aether: go there to see the way",
            strings(CoordsTracker.hudLines(modded, new Reading(Scale.NONE, Double.NaN, Double.NaN, ""))).get(1));
    }

    @Test
    void sitsBelowTheBossBars() {
        assertEquals(4, CoordsTracker.topBelowBossBars(0, 240));
        assertEquals(24, CoordsTracker.topBelowBossBars(1, 240));
        assertEquals(62, CoordsTracker.topBelowBossBars(3, 240));
        // BossHealthOverlay stops drawing a third of the way down the screen
        assertEquals(43, CoordsTracker.topBelowBossBars(10, 120));
    }

    @Test
    void announcesArrival() {
        assertEquals("📍 Arrived at base", CoordsTracker.arrivedLine("base").getString());
    }

    private static List<String> strings(List<Component> lines) {
        return lines.stream().map(Component::getString).toList();
    }
}
