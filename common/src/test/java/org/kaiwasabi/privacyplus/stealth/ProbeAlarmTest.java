package org.kaiwasabi.privacyplus.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProbeAlarmTest {
    @BeforeEach
    void reset() {
        ProbeAlarm.clear();
    }

    @Test
    void vanillaTextIsNotAProbe() {
        assertTrue(ProbeShield.probeKeys(Component.literal("Enter an amount")).isEmpty(), "plugin input signs use plain text");
        assertTrue(ProbeShield.probeKeys(Component.translatable("gui.done")).isEmpty(), "vanilla translations are normal");
    }

    @Test
    void findsUnknownKeysAndKeybindsAnywhere() {
        Component probe = Component.translatable("chat.type.text", Component.literal("x"), Component.translatable("sodium.option_impact.low"))
            .append(Component.keybind("key.meteor-client.open-gui"))
            .append(Component.keybind("key.jump"));
        assertEquals(List.of("unknown key sodium.option_impact.low", "keybind key.meteor-client.open-gui", "keybind key.jump"),
            ProbeShield.probeKeys(probe));
    }

    @Test
    void oneToastPerServerAndKindButEveryProbeListed() {
        Instant now = Instant.now();
        assertTrue(ProbeAlarm.record("a.example", ProbeAlarm.Kind.SIGN, "keybind key.jump", now).isPresent(), "first sign probe: toast");
        assertTrue(ProbeAlarm.record("a.example", ProbeAlarm.Kind.SIGN, "keybind key.jump", now).isEmpty(), "exact repeat: ignored");
        assertTrue(ProbeAlarm.record("a.example", ProbeAlarm.Kind.SIGN, "unknown key x.y", now).isEmpty(), "another sign probe: listed only");
        assertTrue(ProbeAlarm.record("a.example", ProbeAlarm.Kind.TRANSFER, "b.example:25565", now).isPresent(), "new kind: toast");
        assertTrue(ProbeAlarm.record("b.example", ProbeAlarm.Kind.SIGN, "keybind key.jump", now).isPresent(), "new server: toast");
        assertEquals(4, ProbeAlarm.events().size());
        assertEquals("The server probed with a sign (keybind key.jump). /pp probes for details",
            ProbeAlarm.record("c.example", ProbeAlarm.Kind.SIGN, "keybind key.jump", now).orElseThrow());
    }
}
