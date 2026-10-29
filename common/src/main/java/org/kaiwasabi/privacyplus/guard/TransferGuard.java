package org.kaiwasabi.privacyplus.guard;

import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig.CookieGuard;
import org.kaiwasabi.privacyplus.stealth.PackHostGuard;
import org.jspecify.annotations.Nullable;

// A server can send you to any other server (a transfer). Vanilla goes straight away and hands the new
// server every cookie the old one stored on your client, so cooperating servers can recognise you. Here
// transferGuard decides whether to ask first, go or refuse, and cookieGuard which cookies go along.
// Refusing looks to both servers like an ordinary client that never arrived: the old server was already
// left (vanilla disconnects before connecting on), and the new one never hears from you.
public final class TransferGuard {
    // Where the last transfer took the player. ServerData keeps naming the server first joined through
    // every transfer, so it alone can't say which server is sending you on. Set on the render thread when
    // a transfer goes ahead; cleared on the connecting thread by every ordinary (non-transfer) login.
    private static volatile @Nullable String transferredTo;

    // site: the target's site, as named on the prompt
    public record Decision(boolean sameSite, boolean passCookies, String site) {
    }

    private TransferGuard() {
    }

    // Whether the two hosts belong to the same site, and so whether cookies go along under mode
    public static Decision decide(CookieGuard mode, String fromHost, String toHost) {
        boolean sameSite = PackHostGuard.sameSite(fromHost, toHost);
        boolean pass = switch (mode) {
            case ALLOW -> true;
            case NEVER -> false;
            case SAME_SITE -> sameSite;
        };
        return new Decision(sameSite, pass, siteOf(toHost));
    }

    // play.example.com -> example.com; IP addresses and single names stay whole
    static String siteOf(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.contains(":") || LinkGuard.ipv4(lower) != null ? lower : PackHostGuard.site(lower);
    }

    // The state handed to the next server: identical to vanilla's, minus the cookies when they stay behind
    public static TransferState withCookies(TransferState state, boolean pass) {
        return pass ? state : new TransferState(Map.of(), state.seenPlayers(), state.seenInsecureChatWarning());
    }

    // The address of the server you're on: where the last transfer took you if you arrived by one, else
    // the address you joined (ServerData.ip)
    public static String currentServer(String joined) {
        String hop = transferredTo;
        return hop != null ? hop : joined;
    }

    // Every login (normal join, Realms, transfer) builds a handshake listener; only transfers keep the hop
    public static void loginStarted(boolean transferred) {
        if (!transferred) {
            transferredTo = null;
        }
    }

    // Called by handleTransfer in place of vanilla's ConnectScreen.startConnecting, after the old server
    // was disconnected. parent is the screen vanilla would return to.
    public static void onTransfer(Screen parent, Minecraft mc, ServerAddress target, ServerData data, boolean quickPlay,
                                  TransferState state) {
        String from = currentServer(data.ip);
        String to = target.toString();
        Decision decision = decide(PrivacyPlusConfig.cookieGuard, ServerAddress.parseString(from).getHost(), target.getHost());
        TransferState sent = withCookies(state, decision.passCookies());
        switch (PrivacyPlusConfig.transferGuard) {
            case ALLOW -> go(parent, mc, target, data, quickPlay, sent);
            case BLOCK -> {
                stay(mc);
                mc.gui.setScreen(new AlertScreen(() -> mc.gui.setScreen(parent), Component.literal("Transfer blocked"),
                    Component.literal(from + " tried to send you to " + to + ". PrivacyPlus blocked it (transferGuard=BLOCK), so "
                        + "you didn't connect there."),
                    Component.literal("Back"), false));
            }
            case ASK -> mc.gui.setScreen(new TransferPromptScreen(accepted -> {
                if (accepted) {
                    go(parent, mc, target, data, quickPlay, sent);
                } else {
                    stay(mc);
                    mc.gui.setScreen(parent);
                }
            }, from, to, decision, state.cookies().size(), sent));
        }
    }

    // Exactly what vanilla does, with the (possibly filtered) state
    private static void go(Screen parent, Minecraft mc, ServerAddress target, ServerData data, boolean quickPlay, TransferState sent) {
        noteTransfer(target.toString());
        ConnectScreen.startConnecting(parent, mc, target, data, quickPlay, sent);
    }

    // Package-private for tests
    static void noteTransfer(String target) {
        transferredTo = target;
    }

    // Vanilla kept the server's resource packs loaded for the next server; not going there, unload them
    // as an ordinary disconnect would
    private static void stay(Minecraft mc) {
        mc.clearDownloadedResourcePacks();
    }
}
