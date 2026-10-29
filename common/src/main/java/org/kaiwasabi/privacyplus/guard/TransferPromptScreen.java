package org.kaiwasabi.privacyplus.guard;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;

// transferGuard=ASK: shown after a server sends you elsewhere, before connecting there. Go connects as
// vanilla would; Don't go (or Esc) returns to the screen vanilla goes back to after a disconnect.
public final class TransferPromptScreen extends ConfirmScreen {
    // What Go hands the next server (cookies already filtered)
    private final TransferState state;

    TransferPromptScreen(BooleanConsumer callback, String from, String to, TransferGuard.Decision decision, int cookies,
                         TransferState state) {
        super(callback, Component.literal("A server is sending you to another server"), message(from, to, decision, cookies),
            Component.literal("Go"), Component.literal("Don't go"));
        this.state = state;
    }

    public TransferState transferState() {
        return this.state;
    }

    private static Component message(String from, String to, TransferGuard.Decision decision, int cookies) {
        MutableComponent text = Component.empty()
            .append(Component.literal(from).withStyle(ChatFormatting.BOLD))
            .append(" wants to send you to ")
            .append(Component.literal(to).withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW))
            .append(".\n\n");
        if (decision.sameSite()) {
            text.append("Both are part of the same site (" + decision.site() + ").");
        } else {
            text.append(Component.literal("That's a different site from the server you're on. Like any server you join, it will see "
                + "your IP address and Minecraft account.").withStyle(ChatFormatting.GOLD));
        }
        text.append("\n");
        String setting = " (cookieGuard=" + PrivacyPlusConfig.cookieGuard + ")";
        String stored = (cookies == 1 ? "The cookie" : "The " + cookies + " cookies") + " this server stored on your client ";
        if (cookies == 0) {
            text.append("This server stored no cookies on your client, so none go along.");
        } else if (decision.passCookies()) {
            text.append(stored + (cookies == 1 ? "goes" : "go") + " along" + setting + ": the other server can use "
                + (cookies == 1 ? "it" : "them") + " to recognise you.");
        } else {
            text.append(stored + (cookies == 1 ? "stays" : "stay") + " behind" + setting + ", so the other server can't use "
                + (cookies == 1 ? "it" : "them") + " to recognise you.");
        }
        return text;
    }
}
