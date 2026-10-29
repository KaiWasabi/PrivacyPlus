package org.kaiwasabi.privacyplus.coords;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.jspecify.annotations.Nullable;

// The location picked with [Track], shown as a line at the top of the HUD: its name, an arrow relative
// to where you face and the distance. One at a time; it ends when you get there, after 30 minutes, with
// /coords stop or [Stop], and when you leave the world. Drawn only on this screen and never sent.
// Render thread only.
public final class CoordsTracker {
    static final double ARRIVAL_DISTANCE = 4;
    static final Duration MAX_DURATION = Duration.ofMinutes(30);
    static final Duration ARRIVED_SHOWN_FOR = Duration.ofSeconds(3);
    private static final int LINE_HEIGHT = 12;
    // Height differences smaller than this are left out, so standing on a slope doesn't add noise
    private static final int SHOW_HEIGHT_FROM = 3;

    // name is what the HUD calls it: the label, or whose location it is
    public record Target(SharedLocation location, String name, Instant startedAt) {
    }

    enum Update {
        NONE,
        TRACKING,
        ARRIVED,
        EXPIRED
    }

    private static volatile @Nullable Target target;
    private static volatile @Nullable String arrivedAt;
    private static volatile Instant arrivedUntil = Instant.EPOCH;

    private CoordsTracker() {
    }

    public static @Nullable Target target() {
        return target;
    }

    // Replaces whatever was tracked before
    public static void track(SharedLocation location, String name) {
        track(location, name, Instant.now());
    }

    static void track(SharedLocation location, String name, Instant now) {
        target = new Target(location, name, now);
        arrivedAt = null;
    }

    // False if nothing was being tracked
    public static boolean stop() {
        boolean wasTracking = target != null;
        target = null;
        return wasTracking;
    }

    // Leaving the world: drop everything, quietly
    public static void clear() {
        target = null;
        arrivedAt = null;
    }

    // Ends tracking on arrival or after MAX_DURATION. reading is where the target is from you right now.
    static Update update(Instant now, Navigation.Reading reading) {
        Target current = target;
        if (current == null) {
            return Update.NONE;
        }
        if (Duration.between(current.startedAt(), now).compareTo(MAX_DURATION) >= 0) {
            target = null;
            return Update.EXPIRED;
        }
        if (reading.scale() == Navigation.Scale.SAME && reading.distance() <= ARRIVAL_DISTANCE) {
            target = null;
            arrivedAt = current.name();
            arrivedUntil = now.plus(ARRIVED_SHOWN_FOR);
            return Update.ARRIVED;
        }
        return Update.TRACKING;
    }

    // Every client tick
    public static void tick(Minecraft mc) {
        Target current = target;
        Navigation.Reading reading = current == null ? null : readingFor(mc, current);
        if (reading != null && update(Instant.now(), reading) == Update.EXPIRED) {
            EncryptedChat.feedback(mc, "Stopped tracking: 30 minutes are up. Click [Track] again to keep going.");
        }
    }

    private static Navigation.@Nullable Reading readingFor(Minecraft mc, Target current) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return null;
        }
        String dimension = mc.level.dimension().identifier().toString();
        return Navigation.read(current.location(), dimension, player.getX(), player.getY(), player.getZ(), player.getYRot());
    }

    // Draws the tracker centered at the top of the HUD, starting at y = top. Called only while the HUD
    // is shown (not with F1); also hidden while the debug screen (F3) fills the top.
    public static void extract(GuiGraphicsExtractor graphics, Minecraft mc, int top) {
        if (mc.debugEntries.isOverlayVisible()) {
            return;
        }
        List<Component> lines;
        Target current = target;
        Navigation.Reading reading = current == null ? null : readingFor(mc, current);
        String arrived = arrivedAt;
        if (current != null && reading != null) {
            lines = hudLines(current, reading);
        } else if (arrived != null && Instant.now().isBefore(arrivedUntil)) {
            lines = List.of(arrivedLine(arrived));
        } else {
            return;
        }
        Font font = mc.font;
        // A light backdrop so it reads over a bright sky; the accessibility text background setting wins
        int backdrop = mc.options.getBackgroundColor(0x60000000);
        int y = top;
        for (Component line : lines) {
            int width = font.width(line);
            int x = (graphics.guiWidth() - width) / 2;
            graphics.fill(x - 3, y - 2, x + width + 3, y + font.lineHeight + 1, backdrop);
            graphics.text(font, line, x, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
    }

    // Where the tracker goes: under the boss bars that BossHealthOverlay draws (19 pixels apart from
    // y = 12, stopping a third of the way down), or at the very top when there are none
    public static int topBelowBossBars(int bossBars, int guiHeight) {
        int shown = 0;
        int yOffset = 12;
        while (shown < bossBars) {
            shown++;
            yOffset += 19;
            if (yOffset >= guiHeight / 3) {
                break;
            }
        }
        return shown == 0 ? 4 : yOffset - 9 + 2;
    }

    // "📍 Alice's base  ↗ 120 blocks, 14 below", plus a second line when the location is in another dimension
    static List<Component> hudLines(Target target, Navigation.Reading reading) {
        List<Component> lines = new ArrayList<>();
        MutableComponent main = Component.empty()
            .append(Component.literal("📍 ").withStyle(ChatFormatting.RED))
            .append(Component.literal(target.name()).withStyle(ChatFormatting.WHITE));
        if (reading.scale() != Navigation.Scale.NONE) {
            main.append(Component.literal("  " + reading.arrow() + " ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(blocks(reading.distance())).withStyle(ChatFormatting.WHITE));
            int dy = (int) Math.round(reading.dy());
            if (reading.scale() == Navigation.Scale.SAME && Math.abs(dy) >= SHOW_HEIGHT_FROM) {
                main.append(Component.literal(", " + Math.abs(dy) + (dy > 0 ? " above" : " below")).withStyle(ChatFormatting.GRAY));
            }
        }
        lines.add(main);
        String hint = hint(target.location(), reading.scale());
        if (hint != null) {
            lines.add(Component.literal(hint).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    static @Nullable String hint(SharedLocation location, Navigation.Scale scale) {
        return switch (scale) {
            case SAME -> null;
            case OVERWORLD_IN_NETHER -> "In the Overworld: pointing at the matching Nether spot (1:8 scale)";
            case NETHER_IN_OVERWORLD -> "In the Nether: pointing at the matching Overworld spot (8:1 scale)";
            case NONE -> "In " + placeName(location.dimension()) + ": go there to see the way";
        };
    }

    // "the End", "the Nether", or a modded dimension's id
    private static String placeName(String dimension) {
        return switch (dimension) {
            case SharedLocation.OVERWORLD, SharedLocation.NETHER, SharedLocation.END -> "the " + SharedLocation.dimensionName(dimension);
            default -> SharedLocation.dimensionName(dimension);
        };
    }

    static Component arrivedLine(String name) {
        return Component.empty()
            .append(Component.literal("📍 ").withStyle(ChatFormatting.RED))
            .append(Component.literal("Arrived at " + name).withStyle(ChatFormatting.GREEN));
    }

    static String blocks(double distance) {
        long rounded = Math.round(distance);
        return rounded == 1 ? "1 block" : rounded + " blocks";
    }
}
