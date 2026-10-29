package org.kaiwasabi.privacyplus.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.kaiwasabi.privacyplus.guard.ClipboardGuard;
import org.kaiwasabi.privacyplus.guard.ClipboardPromptScreen;
import org.kaiwasabi.privacyplus.guard.LinkGuard;
import org.kaiwasabi.privacyplus.guard.LinkWarningScreen;

// In-game checks for link, clipboard, transfer and cookie guards; run near the end of SelfTest. Clicks go
// through vanilla's own handlers (the chat screen's and a book's), as a click on server text would. No
// browser is ever opened: risky links are cancelled or copied, the ordinary one is declined at
// vanilla's prompt, and the one tried with that prompt off goes nowhere even if opened. The transfer and
// cookie guards are checked in SelfTest.packHosts, against the fake server.
final class GuardChecks {
    private static final String GRABIFY = "https://grabify.link/SELFTEST";
    // Risky (plain http, bare IP address, odd port) but harmless if a bug ever let it open: nothing listens there
    private static final String LOCAL_HTTP = "http://127.0.0.1:9/privacyplus-selftest";
    private static final String COPIED = "/say PrivacyPlus self-test\nsecond line";

    private GuardChecks() {
    }

    static void run(SelfTest test, Minecraft mc) throws Exception {
        boolean links = mc.options.chatLinks().get();
        boolean linkPrompt = mc.options.chatLinksPrompt().get();
        String[] clipboard = {""};
        SelfTest.onRenderThread(mc, () -> clipboard[0] = mc.keyboardHandler.getClipboard());
        try {
            SelfTest.onRenderThread(mc, () -> {
                mc.options.chatLinks().set(true);
                mc.options.chatLinksPrompt().set(true);
                ChatScreen chat = new ChatScreen("", false);
                mc.gui.setScreen(chat);
                links(test, mc, chat);
                copying(test, mc, chat);
                book(test, mc);
            });
        } finally {
            SelfTest.onRenderThread(mc, () -> {
                mc.options.chatLinks().set(links);
                mc.options.chatLinksPrompt().set(linkPrompt);
                // An empty read may mean the clipboard held something other than text: leave that alone
                if (!clipboard[0].isEmpty()) {
                    mc.keyboardHandler.setClipboard(clipboard[0]);
                }
                mc.gui.setScreen(null);
            });
        }
    }

    private static void links(SelfTest test, Minecraft mc, ChatScreen chat) {
        clickChat(chat, new ClickEvent.OpenUrl(URI.create(GRABIFY)));
        test.check("a Grabify link clicked in chat shows the link warning, not the browser",
            mc.gui.screen() instanceof LinkWarningScreen warning && warning.analysis().has(LinkGuard.Kind.IP_LOGGER)
                && warning.analysis().site().equals("grabify.link"));
        test.check("the link warning offers Open anyway, Copy link and Cancel",
            hasButton(mc, "Open anyway") && hasButton(mc, "Copy link") && hasButton(mc, "Cancel"));
        test.check("Cancel on the link warning returns to chat", press(mc, "Cancel") && mc.gui.screen() == chat);

        // Risky links ask even with Minecraft's own link prompt switched off
        mc.options.chatLinksPrompt().set(false);
        clickChat(chat, new ClickEvent.OpenUrl(URI.create(LOCAL_HTTP)));
        test.check("with the vanilla link prompt off, an http link to a bare IP address still shows the warning",
            mc.gui.screen() instanceof LinkWarningScreen warning && warning.analysis().has(LinkGuard.Kind.IP_ADDRESS)
                && warning.analysis().has(LinkGuard.Kind.NOT_HTTPS) && warning.analysis().has(LinkGuard.Kind.PORT));
        test.check("Copy link copies the whole address and returns to chat",
            press(mc, "Copy link") && mc.keyboardHandler.getClipboard().equals(LOCAL_HTTP) && mc.gui.screen() == chat);
        mc.options.chatLinksPrompt().set(true);

        // An ordinary link is left to vanilla: its own prompt (declined here, so no browser opens)
        clickChat(chat, new ClickEvent.OpenUrl(URI.create("https://minecraft.wiki/w/Creeper")));
        test.check("an ordinary https link gets vanilla's own link prompt", mc.gui.screen() != null
            && mc.gui.screen().getClass() == ConfirmLinkScreen.class);
        if (mc.gui.screen() instanceof ConfirmScreen vanilla) {
            callback(vanilla).accept(false);
        }
        test.check("declining vanilla's prompt returns to chat", mc.gui.screen() == chat);
    }

    private static void copying(SelfTest test, Minecraft mc, ChatScreen chat) {
        String before = mc.keyboardHandler.getClipboard();
        clickChat(chat, new ClickEvent.CopyToClipboard(COPIED));
        test.check("a copy-to-clipboard click asks first, flagging the command and the line break",
            mc.gui.screen() instanceof ClipboardPromptScreen prompt && prompt.value().equals(COPIED)
                && prompt.flags().stream().map(ClipboardGuard.Flag::kind).toList()
                    .containsAll(List.of(ClipboardGuard.Kind.COMMAND, ClipboardGuard.Kind.LINE_BREAKS)));
        test.check("nothing is copied while the prompt is open", mc.keyboardHandler.getClipboard().equals(before));
        test.check("Cancel copies nothing and returns to chat",
            press(mc, "Cancel") && mc.keyboardHandler.getClipboard().equals(before) && mc.gui.screen() == chat);

        clickChat(chat, new ClickEvent.CopyToClipboard(COPIED));
        test.check("Copy puts exactly the text on the clipboard and returns to chat",
            press(mc, "Copy") && mc.keyboardHandler.getClipboard().replace("\r\n", "\n").equals(COPIED) && mc.gui.screen() == chat);

        // PrivacyPlus's own chat buttons are copy events too: one that reaches vanilla's handler still just runs
        boolean[] ran = {false};
        ClickEvent local = EncryptedChat.localClick(() -> ran[0] = true);
        SelfTest.invoke(Screen.class, "defaultHandleClickEvent", null, local, mc, chat);
        test.check("PrivacyPlus's own chat buttons run without a clipboard prompt", ran[0] && mc.gui.screen() == chat);
    }

    // Books handle clicks themselves before falling back to vanilla's shared handler
    private static void book(SelfTest test, Minecraft mc) {
        BookViewScreen book = new BookViewScreen(new BookViewScreen.BookAccess(List.of(Component.literal("PrivacyPlus self-test"))));
        mc.gui.setScreen(book);
        SelfTest.invoke(BookViewScreen.class, "handleClickEvent", book, new ClickEvent.OpenUrl(URI.create(GRABIFY)));
        test.check("a Grabify link in a book shows the link warning", mc.gui.screen() instanceof LinkWarningScreen);
        test.check("Cancel returns to the book", press(mc, "Cancel") && mc.gui.screen() == book);
        mc.gui.setScreen(null);
    }

    private static void clickChat(ChatScreen chat, ClickEvent event) {
        SelfTest.invoke(ChatScreen.class, "handleComponentClicked", chat, Style.EMPTY.withClickEvent(event), false);
    }

    private static boolean hasButton(Minecraft mc, String label) {
        return mc.gui.screen() != null && mc.gui.screen().children().stream()
            .anyMatch(c -> c instanceof Button b && b.getMessage().getString().equals(label));
    }

    // Clicks the button with this label on the open screen; false (and nothing clicked) if there is none
    private static boolean press(Minecraft mc, String label) {
        if (mc.gui.screen() == null) {
            return false;
        }
        return mc.gui.screen().children().stream()
            .filter(c -> c instanceof Button b && b.getMessage().getString().equals(label))
            .map(Button.class::cast).findFirst()
            .map(b -> {
                SelfTest.click(b, InputConstants.MOUSE_BUTTON_LEFT);
                return true;
            })
            .orElse(false);
    }

    private static BooleanConsumer callback(ConfirmScreen screen) {
        try {
            return (BooleanConsumer) SelfTest.field(ConfirmScreen.class, "callback").get(screen);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
