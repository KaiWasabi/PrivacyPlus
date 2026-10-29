package org.kaiwasabi.privacyplus.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.EncryptedChat.Control;
import org.kaiwasabi.privacyplus.coords.CoordsTracker;
import org.kaiwasabi.privacyplus.coords.SharedLocation;
import org.jspecify.annotations.Nullable;

// /coords: shares your block position and dimension with a channel as an encrypted "coords" control
// message in public chat, and shows locations others share as a line with [Copy] and [Track]. The
// command never leaves this computer, and the coordinates and label only travel encrypted. Feedback
// lines never repeat them either, since chat lines (unlike the decrypted ones) are written to latest.log.
// Render thread only.
public final class Coords {
    public static final String VERB = "coords";
    public static final String PIN = "📍";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    // Same as EncryptedChat's: older messages may be a recording replayed by the server
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);
    private static final List<String> WORDS = List.of("stop", "label", "help");
    // Servers kick players who chat too fast; a double-clicked 📍 shouldn't get anyone there
    private static final Duration COOLDOWN = Duration.ofSeconds(2);
    private static Instant lastShared = Instant.EPOCH;

    private Coords() {
    }

    // Called once at startup: registers /coords and its control-message handler with EncryptedChat
    public static void register() {
        EncryptedChat.registerCommand(VERB, new EncryptedChat.LocalCommand() {
            @Override
            public void run(Minecraft mc, String args) {
                Coords.run(mc, args);
            }

            @Override
            public List<String> complete(String[] words) {
                return Coords.complete(words);
            }
        });
        EncryptedChat.registerControl(VERB,
            (control, style) -> render(control, style, Minecraft.getInstance().getUser().getName(), Instant.now()));
    }

    // /coords [channel] [label...], /coords label <label...>, /coords stop, /coords help
    private static void run(Minecraft mc, String args) {
        int space = args.indexOf(' ');
        String first = space == -1 ? args : args.substring(0, space);
        String rest = space == -1 ? "" : args.substring(space + 1).trim();
        switch (first) {
            case "" -> share(mc, null, null, null);
            case "stop" -> EncryptedChat.feedback(mc, CoordsTracker.stop() ? "Stopped tracking." : "Nothing is being tracked.");
            case "help" -> help(mc);
            case "label" -> {
                if (rest.isEmpty()) {
                    EncryptedChat.feedback(mc, "Add the label after the word: /coords label <label>. Nothing was sent.");
                } else {
                    share(mc, null, rest, null);
                }
            }
            default -> {
                Channel channel = ChatSettings.get(first);
                if (channel == null) {
                    // Never guess: a mistyped channel name must not send the location somewhere else
                    EncryptedChat.feedback(mc, "No channel named \"" + first + "\"; nothing was sent. See /coords help.");
                } else {
                    share(mc, channel, rest.isEmpty() ? null : rest, null);
                }
            }
        }
    }

    static List<String> complete(String[] words) {
        if (words.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(WORDS);
        ChatSettings.all().forEach(channel -> options.add(channel.name()));
        return options;
    }

    private static void help(Minecraft mc) {
        EncryptedChat.feedback(mc, "/coords shares your location, encrypted, with the channel in use (or the one used last).");
        EncryptedChat.feedback(mc, "/coords <channel> [label] picks the channel, /coords label <label> names the spot,");
        EncryptedChat.feedback(mc, "/coords stop ends tracking.");
    }

    // Where /coords and the 📍 button share to: the channel the lock is on, otherwise the one used last
    public static @Nullable Channel defaultChannel() {
        Channel active = ChatSettings.active();
        return active != null ? active : ChatSettings.lastUsed();
    }

    // Sends your position to channel (null: defaultChannel()). parent is the screen to come back to if the
    // settings have to be opened; null when run from a command, as the chat box is about to close.
    public static void share(Minecraft mc, @Nullable Channel requested, @Nullable String label, @Nullable Screen parent) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        Channel channel = requested != null ? requested : defaultChannel();
        if (channel == null) {
            EncryptedChat.feedback(mc, "Locations are only shared with an encrypted channel, and there are none yet. Add one here.");
            if (parent != null) {
                mc.gui.setScreen(new ChatSettingsScreen(parent));
            } else {
                mc.execute(() -> mc.gui.setScreen(new ChatSettingsScreen(null)));
            }
            return;
        }
        SecretKey key = channel.readyKey();
        if (key == null) {
            // Never fall back to plain text
            EncryptedChat.feedback(mc, "The key for " + channel.name() + " is still being prepared; nothing was sent. Try again soon.");
            return;
        }
        Instant now = Instant.now();
        if (Duration.between(lastShared, now).compareTo(COOLDOWN) < 0) {
            EncryptedChat.feedback(mc, "You just shared your location; wait a moment before sharing it again.");
            return;
        }
        BlockPos pos = mc.player.blockPosition();
        SharedLocation here;
        try {
            here = new SharedLocation(pos.getX(), pos.getY(), pos.getZ(), mc.level.dimension().identifier().toString(), label);
        } catch (IllegalArgumentException e) {
            EncryptedChat.feedback(mc, "This position can't be shared (too far out, or a very long dimension name); nothing was sent.");
            return;
        }
        if (EncryptedChat.sendControl(mc, key, "", VERB, here.encode())) {
            lastShared = now;
            EncryptedChat.feedback(mc, "Shared your location with " + channel.name() + ".");
        }
    }

    // The line shown for a decrypted "coords" message: "📍 Alice shared a location: 120 64 -300 (Overworld) [Copy] [Track]".
    // me is this player's name, so echoes of our own messages read "You shared...".
    static Component render(Control control, Style style, @Nullable String me, Instant now) {
        boolean mine = control.writer() != null && me != null && control.writer().equalsIgnoreCase(me);
        String who = control.writer() == null ? "Someone" : mine ? "You" : control.writer();
        // Only the line's color carries over: a server could otherwise attach its own click or hover
        // actions (or text to insert) to what reads as our buttons
        Style base = Style.EMPTY.withColor(style.getColor());
        MutableComponent line = Component.empty();
        String warnings = (control.writer() == null ? "[sender mismatch] " : "") + (control.stale() ? "[old] " : "");
        if (!warnings.isEmpty()) {
            line.append(Component.literal(warnings).withStyle(ChatFormatting.GOLD));
        }
        line.append(Component.literal(PIN + " ").withStyle(ChatFormatting.RED));

        Optional<SharedLocation> decoded = SharedLocation.decode(control.body());
        if (decoded.isEmpty()) {
            return line.append(Component.literal(who + " shared a location that couldn't be read").withStyle(ChatFormatting.GRAY));
        }
        SharedLocation location = decoded.get();
        Style described = base.withHoverEvent(new HoverEvent.ShowText(Component.literal(details(control, location, now))));
        line.append(Component.literal(who + " shared a location: ").setStyle(described));
        if (location.label() != null) {
            line.append(Component.literal("\"" + location.label() + "\"").setStyle(described.withItalic(true)))
                .append(Component.literal(" at ").setStyle(described));
        }
        line.append(Component.literal(location.coordinates()).setStyle(described.withColor(ChatFormatting.AQUA)))
            .append(Component.literal(" (" + location.dimensionName() + ") ").setStyle(described));

        String name = location.label() != null ? location.label()
            : mine ? "Your location" : control.writer() == null ? "Shared location" : control.writer() + "'s location";
        String whose = mine ? "the location you shared"
            : control.writer() == null ? "the shared location" : "the location " + who + " shared";
        line.append(button("[Copy]", ChatFormatting.GREEN, "Copy " + location.coordinates() + " to the clipboard", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.keyboardHandler.setClipboard(location.coordinates());
            EncryptedChat.feedback(mc, "Copied the coordinates to the clipboard.");
        }));
        line.append(Component.literal(" ").setStyle(base));
        line.append(button("[Track]", ChatFormatting.YELLOW, "Show the direction and distance at the top of the screen", () -> {
            Minecraft mc = Minecraft.getInstance();
            CoordsTracker.track(location, name);
            trackingFeedback(mc, "Tracking " + whose + " at the top of the screen. ");
        }));
        return line;
    }

    // The hover text: which key opened it, when it was sent, and warnings when something is off
    private static String details(Control control, SharedLocation location, Instant now) {
        StringBuilder text = new StringBuilder()
            .append(control.channel() != null ? "Encrypted with channel " : "Encrypted with ").append(clean(control.keyLabel()))
            .append("\n").append(location.dimensionName()).append(": ").append(location.coordinates())
            .append("\nSent at ").append(TIME.format(control.sentAt()))
            .append(control.writer() != null ? " by " + control.writer() : "")
            .append(", ").append(age(Duration.between(control.sentAt(), now)));
        if (control.writer() == null) {
            text.append("\nNo name on this line matches the writer: the server may be faking the sender");
        }
        if (control.stale()) {
            text.append("\nOlder than ").append(STALE_AFTER.toMinutes())
                .append(" minutes: the server may be replaying it, and the location may be out of date");
        }
        return text.toString();
    }

    // How long the message took to arrive. Sending times are kept to the minute, so anything under two
    // minutes is just "right away".
    static String age(Duration age) {
        long minutes = age.abs().toMinutes();
        String amount;
        if (minutes < 2) {
            return "arrived right away";
        } else if (minutes < 120) {
            amount = minutes + " minutes";
        } else if (minutes < 48 * 60) {
            amount = minutes / 60 + " hours";
        } else {
            amount = minutes / (24 * 60) + " days";
        }
        return age.isNegative() ? "dated " + amount + " in the future (clocks differ?)" : "arrived " + amount + " later";
    }

    // Key labels for friends come from elsewhere; keep them as plain as our own labels
    private static String clean(String text) {
        String cleaned = SharedLocation.cleanLabel(text);
        return cleaned == null ? "?" : cleaned;
    }

    private static Component button(String text, ChatFormatting color, String tooltip, Runnable action) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(color)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip)))
            .withClickEvent(EncryptedChat.localClick(action)));
    }

    // "[PrivacyPlus] Tracking ... [Stop]", with no coordinates or label in it (it goes to latest.log)
    private static void trackingFeedback(Minecraft mc, String text) {
        Component stop = button("[Stop]", ChatFormatting.RED, "Stop tracking", () -> {
            CoordsTracker.stop();
            EncryptedChat.feedback(Minecraft.getInstance(), "Stopped tracking.");
        });
        Component line = Component.literal("[" + PrivacyPlus.MOD_NAME + "] " + text).withStyle(ChatFormatting.GRAY).append(stop);
        mc.gui.hud.getChat().addClientSystemMessage(line);
    }
}
