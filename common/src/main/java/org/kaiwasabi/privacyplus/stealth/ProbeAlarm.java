package org.kaiwasabi.privacyplus.stealth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// Notices when a server tries to fingerprint or track the player and says so quietly: one toast per
// server and kind of probe, and the full list (this session only, never written to disk) under
// /pp probes. Purely local; the server can't tell. Reports can arrive from the network thread.
public final class ProbeAlarm {
    public enum Kind {
        SIGN("probed with a sign"),
        ANVIL("probed with an anvil item name"),
        PACK("sent a resource pack hosted on another site"),
        COOKIE_STORE("stored a cookie"),
        COOKIE_REQUEST("asked for a cookie"),
        TRANSFER("sent you to another server"),
        LOGIN_QUERY("asked which mods you have");

        public final String description;

        Kind(String description) {
            this.description = description;
        }
    }

    public record Event(Instant at, String server, Kind kind, String detail) {
    }

    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);
    // A server chooses the details (cookie keys, query ids...), so it could send endless distinct ones:
    // keep a bounded list, and stop listing a server's probes after enough of them
    private static final int MAX_PER_SERVER = 200;
    private static final int MAX_EVENTS = 1000;
    private static final int MAX_DETAIL = 200;
    private static final List<Event> EVENTS = new ArrayList<>();
    private static final Set<String> SEEN = new HashSet<>();
    private static final Map<String, Integer> PER_SERVER = new HashMap<>();
    private static final Set<String> TOASTED = new HashSet<>();

    private ProbeAlarm() {
    }

    // Looks for probe content (mod translation keys, keybinds) in text a server made the client echo back
    public static void inspect(Kind kind, Component text) {
        if (PrivacyPlusConfig.probeAlarm) {
            ProbeShield.probeKeys(text).forEach(key -> report(kind, key));
        }
    }

    public static void report(Kind kind, String detail) {
        report(kind, detail, null);
    }

    // connection: the server a login or configuration packet came from. Minecraft only records the
    // current server once you're in the world, so probes during joining need it passed in.
    public static void report(Kind kind, String detail, @Nullable ServerData connection) {
        if (!PrivacyPlusConfig.probeAlarm) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String server = connection != null ? connection.ip : currentServer(mc);
        record(server, kind, detail, Instant.now()).ifPresent(toast -> mc.execute(() -> SystemToast.addOrUpdate(
            mc.gui.toastManager(), TOAST, Component.literal("PrivacyPlus: " + server), Component.literal(toast))));
    }

    // Returns the toast text when this is the first probe of its kind from this server
    static synchronized Optional<String> record(String server, Kind kind, String rawDetail, Instant at) {
        String detail = rawDetail.length() > MAX_DETAIL ? rawDetail.substring(0, MAX_DETAIL) + "..." : rawDetail;
        int listed = PER_SERVER.getOrDefault(server, 0);
        if (listed < MAX_PER_SERVER && EVENTS.size() < MAX_EVENTS) {
            if (!SEEN.add(server + '\n' + kind + '\n' + detail)) {
                return Optional.empty();
            }
            EVENTS.add(new Event(at, server, kind, detail));
            PER_SERVER.put(server, listed + 1);
        }
        // A new kind of probe still gets its toast once the list is full, so flooding can't hide it
        return TOASTED.add(server + '\n' + kind)
            ? Optional.of("The server " + kind.description + " (" + detail + "). /pp probes for details")
            : Optional.empty();
    }

    public static synchronized List<Event> events() {
        return List.copyOf(EVENTS);
    }

    static synchronized void clear() {
        EVENTS.clear();
        SEEN.clear();
        PER_SERVER.clear();
        TOASTED.clear();
    }

    private static String currentServer(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            return server.ip;
        }
        return mc.isLocalServer() ? "singleplayer" : "unknown server";
    }
}
