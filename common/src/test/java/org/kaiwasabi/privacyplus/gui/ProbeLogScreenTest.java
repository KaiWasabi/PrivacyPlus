package org.kaiwasabi.privacyplus.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm.Event;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm.Kind;
import org.junit.jupiter.api.Test;

class ProbeLogScreenTest {
    @Test
    void newestFirstEvenWhenReportedOutOfOrder() {
        Instant t = Instant.parse("2026-10-30T12:00:00Z");
        Event first = new Event(t, "a.example", Kind.SIGN, "keybind key.jump");
        Event second = new Event(t, "a.example", Kind.SIGN, "unknown key x.y");
        Event later = new Event(t.plusSeconds(5), "b.example", Kind.TRANSFER, "c.example:25565");
        Event earliest = new Event(t.minusSeconds(5), "a.example", Kind.COOKIE_STORE, "minecraft:session");
        assertEquals(List.of(later, second, first, earliest), ProbeLogScreen.newestFirst(List.of(first, second, later, earliest)));
        assertEquals(List.of(), ProbeLogScreen.newestFirst(List.of()));
    }

    @Test
    void describesWhatTheServerTried() {
        Event event = new Event(Instant.now(), "a.example", ProbeAlarm.Kind.ANVIL, "unknown key sodium.option_impact.low");
        assertEquals("Probed with an anvil item name: unknown key sodium.option_impact.low", ProbeLogScreen.describe(event));
    }
}
