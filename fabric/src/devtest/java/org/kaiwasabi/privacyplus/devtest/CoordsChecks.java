package org.kaiwasabi.privacyplus.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.ChatSettings;
import org.kaiwasabi.privacyplus.chat.Coords;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.kaiwasabi.privacyplus.chat.GearButton;
import org.kaiwasabi.privacyplus.coords.ChannelPickerScreen;
import org.kaiwasabi.privacyplus.coords.CoordsButton;
import org.kaiwasabi.privacyplus.coords.CoordsTracker;
import org.kaiwasabi.privacyplus.coords.SharedLocation;
import org.jspecify.annotations.Nullable;

// In-game checks for /coords and the HUD tracker; run near the end of SelfTest. Shares the player's own
// position through the integrated server, which echoes it back, then tracks it and a spot further away.
final class CoordsChecks {
    private static final String SHARED = Coords.PIN + " You shared a location: ";

    private CoordsChecks() {
    }

    static void run(SelfTest test, Minecraft mc) throws Exception {
        int[] requestsBefore = new int[1];
        SelfTest.onRenderThread(mc, () -> requestsBefore[0] = suggestionRequests(mc));
        try {
            shareAndTrack(test, mc);
            chatBoxButton(test, mc);
        } finally {
            SelfTest.onRenderThread(mc, () -> {
                CoordsTracker.stop();
                mc.gui.setScreen(null);
            });
        }
        SelfTest.onRenderThread(mc, () ->
            test.check("nothing about /coords was sent as a command-suggestion request", suggestionRequests(mc) == requestsBefore[0]));
    }

    private static void shareAndTrack(SelfTest test, Minecraft mc) throws Exception {
        // Unique, so finding it on disk can only mean a leak
        String label = "spot " + UUID.randomUUID().toString().substring(0, 8);
        BlockPos[] here = new BlockPos[1];
        SelfTest.onRenderThread(mc, () -> {
            here[0] = mc.player.blockPosition();
            ChatScreen chat = new ChatScreen("", false);
            mc.gui.setScreen(chat);
            // Typed like a player would, with Tab: completions must be worked out locally
            EditBox input = SelfTest.chatInput(chat);
            for (String typed : List.of("/coords", "/coords ", "/coords se", "/coords selftest", "/coords selftest " + label)) {
                input.setValue(typed);
            }
            chat.keyPressed(new KeyEvent(InputConstants.KEY_TAB, 0, 0));
            input.setValue("");
            chat.handleChatInput("/coords selftest " + label, true);
            // Chat history is newest first
            test.check("/coords confirms locally, without repeating the location",
                SelfTest.chatLines(mc).getFirst().endsWith("Shared your location with selftest."));
            mc.gui.setScreen(null);
        });
        String coordinates = here[0].getX() + " " + here[0].getY() + " " + here[0].getZ();
        Optional<Component> echo = awaitLine(mc, l -> l.startsWith(EncryptedChat.LOCK + " ")
            && l.contains(SHARED + "\"" + label + "\" at " + coordinates + " (Overworld) [Copy] [Track]"));
        test.check("/coords echo is shown decrypted, with the position, label and Overworld", echo.isPresent());
        test.check("[Copy] and [Track] are local clicks that don't carry the coordinates", echo.isPresent()
            && clickOn(echo.get(), "[Copy]") instanceof ClickEvent.CopyToClipboard copy && copy.value().startsWith("PrivacyPlus local action ")
            && clickOn(echo.get(), "[Track]") instanceof ClickEvent.CopyToClipboard track && !track.value().contains(coordinates));

        String log = Files.readString(mc.gameDirectory.toPath().resolve("logs/latest.log"));
        test.check("the label and the plain coordinates never reached latest.log",
            !log.contains(label) && !log.contains(coordinates + " overworld") && !log.contains("coords " + coordinates));
        test.check("sanity: latest.log has the confirmation", log.contains("Shared your location with selftest."));
        if (echo.isEmpty()) {
            return;
        }

        // Tracking where we stand: arrives on the next tick
        SelfTest.onRenderThread(mc, () -> {
            mc.gui.setScreen(new ChatScreen("", false));
            click(mc, echo.get(), "[Track]");
            CoordsTracker.Target target = CoordsTracker.target();
            test.check("[Track] starts the tracker on the shared spot", target != null
                && target.location().equals(new SharedLocation(here[0].getX(), here[0].getY(), here[0].getZ(), SharedLocation.OVERWORLD, label)));
            String said = SelfTest.chatLines(mc).getFirst();
            test.check("[Track] says so in chat, with [Stop], but not where",
                said.contains("Tracking the location you shared") && said.endsWith("[Stop]") && !said.contains(label));
            mc.gui.setScreen(null);
        });
        Thread.sleep(500);
        SelfTest.onRenderThread(mc, () -> test.check("standing on the spot counts as arriving", CoordsTracker.target() == null));

        // A spot about 130 blocks away, sent the way a friend's /coords would be
        SharedLocation far = new SharedLocation(here[0].getX() + 120, here[0].getY() + 10, here[0].getZ() - 50,
            SharedLocation.OVERWORLD, "far " + label);
        SelfTest.onRenderThread(mc, () ->
            EncryptedChat.sendControl(mc, ChatSettings.get("selftest").readyKey(), "", Coords.VERB, far.encode()));
        Optional<Component> farEcho = awaitLine(mc, l -> l.contains(SHARED + "\"far " + label + "\" at " + far.coordinates()));
        test.check("a far-away spot arrives too", farEcho.isPresent());
        if (farEcho.isEmpty()) {
            return;
        }
        SelfTest.onRenderThread(mc, () -> {
            mc.gui.setScreen(new ChatScreen("", false));
            click(mc, farEcho.get(), "[Track]");
            mc.gui.setScreen(null);
        });
        Thread.sleep(1500);
        SelfTest.onRenderThread(mc, () -> {
            CoordsTracker.Target target = CoordsTracker.target();
            test.check("the far spot is still tracked a moment later", target != null && target.location().equals(far));
            Screenshot.grab(mc.gameDirectory, "selftest-coords-hud.png", mc.gameRenderer.mainRenderTarget(), 1, message -> { });
        });
        Thread.sleep(500);
        SelfTest.onRenderThread(mc, () -> {
            ChatScreen chat = new ChatScreen("", false);
            mc.gui.setScreen(chat);
            chat.handleChatInput("/coords stop", true);
            test.check("/coords stop ends tracking",
                CoordsTracker.target() == null && SelfTest.chatLines(mc).getFirst().endsWith("Stopped tracking."));
            mc.gui.setScreen(null);
        });
    }

    // The 📍 in the chat box, left of the gear and lock
    private static void chatBoxButton(SelfTest test, Minecraft mc) throws Exception {
        String[] channel = new String[1];
        SelfTest.onRenderThread(mc, () -> {
            ChatScreen chat = new ChatScreen("", false);
            mc.gui.setScreen(chat);
            CoordsButton pin = widget(chat, CoordsButton.class);
            GearButton gear = widget(chat, GearButton.class);
            EditBox input = SelfTest.chatInput(chat);
            test.check("chat box has the 📍 button left of the gear, clear of the text field", pin != null && gear != null
                && pin.getX() + pin.getWidth() < gear.getX() && input.getX() + input.getWidth() < pin.getX() && pin.getY() == gear.getY());
            if (pin == null) {
                return;
            }
            channel[0] = Coords.defaultChannel().name();
            SelfTest.click(pin, InputConstants.MOUSE_BUTTON_LEFT);
            test.check("left-clicking 📍 shares the location",
                SelfTest.chatLines(mc).getFirst().endsWith("Shared your location with " + channel[0] + "."));
            test.check("the chat box stays open after sharing", mc.gui.screen() == chat);
            SelfTest.click(pin, InputConstants.MOUSE_BUTTON_RIGHT);
            test.check("right-clicking 📍 opens the channel picker, listing selftest", mc.gui.screen() instanceof ChannelPickerScreen
                && mc.gui.screen().children().stream().anyMatch(c -> c instanceof Button b && b.getMessage().getString().equals("selftest")));
        });
        if (channel[0] == null) {
            return;
        }
        Thread.sleep(500);
        SelfTest.onRenderThread(mc, () -> {
            Screenshot.grab(mc.gameDirectory, "selftest-coords-picker.png", mc.gameRenderer.mainRenderTarget(), 1, message -> { });
            mc.gui.screen().onClose();
            test.check("closing the picker returns to the chat box", mc.gui.screen() instanceof ChatScreen);
            mc.gui.setScreen(null);
        });
        // Let the button's echo arrive before the next checks start
        test.check("the 📍 button's location arrives", awaitLine(mc, l -> l.startsWith(EncryptedChat.LOCK + " ")
            && l.contains(Coords.PIN + " You shared a location: ") && !l.contains("\"")).isPresent());
    }

    // Polls the chat history (on the render thread) for up to 10 seconds; empty if the line never came
    private static Optional<Component> awaitLine(Minecraft mc, Predicate<String> matches) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            Component[] found = {null};
            SelfTest.onRenderThread(mc, () -> found[0] = SelfTest.chatComponents(mc).stream()
                .filter(c -> matches.test(c.getString())).findFirst().orElse(null));
            if (found[0] != null) {
                return Optional.of(found[0]);
            }
            Thread.sleep(250);
        }
        return Optional.empty();
    }

    // Clicks the part of a chat line reading text, through the chat screen's own click handling
    private static void click(Minecraft mc, Component line, String text) {
        Style style = styleOf(line, text);
        if (style == null) {
            throw new AssertionError("no " + text + " on " + line.getString());
        }
        SelfTest.invoke(ChatScreen.class, "handleComponentClicked", mc.gui.screen(), style, false);
    }

    private static @Nullable ClickEvent clickOn(Component line, String text) {
        Style style = styleOf(line, text);
        return style == null ? null : style.getClickEvent();
    }

    private static @Nullable Style styleOf(Component line, String text) {
        Style[] found = {null};
        line.visit((style, part) -> {
            if (part.equals(text)) {
                found[0] = style;
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    private static <T> @Nullable T widget(ChatScreen chat, Class<T> type) {
        return chat.children().stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static int suggestionRequests(Minecraft mc) {
        try {
            Object provider = mc.player.connection.getSuggestionsProvider();
            return (int) SelfTest.field(ClientSuggestionProvider.class, "pendingSuggestionsId").get(provider);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
