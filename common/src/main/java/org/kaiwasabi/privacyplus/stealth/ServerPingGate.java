package org.kaiwasabi.privacyplus.stealth;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;

// Vanilla pings every saved server as soon as the multiplayer list is drawn, so each of those
// servers learns your IP whenever you open the menu. Here an entry is only pinged once the
// player selects it or presses Refresh. Only touched from the render thread.
public final class ServerPingGate {
    public static final Component NOT_PINGED = Component.literal("Not pinged. Select it or press Refresh to ping.");

    // ServerData objects are recreated every time the screen opens, so approvals don't carry over
    private static final Set<ServerData> APPROVED = Collections.newSetFromMap(new WeakHashMap<>());

    private ServerPingGate() {
    }

    public static boolean mayPing(ServerData server) {
        return !PrivacyPlusConfig.pingServersOnlyOnRequest || APPROVED.contains(server);
    }

    public static void approve(ServerData server) {
        APPROVED.add(server);
    }
}
