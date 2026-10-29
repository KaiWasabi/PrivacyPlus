package org.kaiwasabi.privacyplus.telemetry;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.telemetry.TelemetryEventSender;
import org.kaiwasabi.privacyplus.PrivacyPlus;

// Replaces every telemetry sender the game creates, so events are dropped before
// an authlib TelemetrySession is ever opened.
public final class TelemetryBlocker {
    private static final AtomicInteger BLOCKED = new AtomicInteger();

    public static final TelemetryEventSender SENDER = (type, buildFunction) -> {
        // Only count what vanilla would actually have sent: required events, plus optional
        // ones if the player opted in. Opted-out optional events were never going out anyway.
        if (type.isOptIn() && !Minecraft.getInstance().telemetryOptInExtra()) {
            return;
        }
        int total = BLOCKED.incrementAndGet();
        PrivacyPlus.LOG.info("Blocked telemetry event '{}' ({} this session)", type.id(), total);
    };

    private TelemetryBlocker() {
    }

    public static int blockedCount() {
        return BLOCKED.get();
    }
}
