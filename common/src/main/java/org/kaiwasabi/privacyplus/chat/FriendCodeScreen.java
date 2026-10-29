package org.kaiwasabi.privacyplus.chat;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

// Friend codes, for two players who share no channel: one makes a code and sends it outside Minecraft, the
// other enters it here with the maker's name. The key exchange then travels under a key only the code gives,
// so the server can neither read nor recognize it. Shows a code just made, or asks for one.
final class FriendCodeScreen extends Screen {
    private final ChatSettingsScreen parent;
    // Set when showing a code just made; null when asking for one
    private final @Nullable String code;
    private String draftName = "";
    private String draftCode = "";
    private @Nullable String status;
    private int statusColor = RowList.GRAY;
    private boolean sending;

    // Asks for a code someone gave us
    FriendCodeScreen(ChatSettingsScreen parent) {
        this(parent, null);
    }

    // Shows a code we just made
    FriendCodeScreen(ChatSettingsScreen parent, @Nullable String code) {
        super(Component.literal(code == null ? "Use a friend code" : "Your friend code"));
        this.parent = parent;
        this.code = code;
    }

    private int contentWidth() {
        return Math.min(300, this.width - 40);
    }

    @Override
    protected void init() {
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        int buttonsY = this.height - 28;
        int half = (width - 4) / 2;
        if (this.code != null) {
            this.addRenderableWidget(Button.builder(Component.literal("Copy"), b -> {
                this.minecraft.keyboardHandler.setClipboard(this.code);
                this.say("Copied.", RowList.GREEN);
            }).bounds(left, buttonsY, half, 20).build());
            this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())
                .bounds(left + half + 4, buttonsY, half, 20).build());
            return;
        }
        EditBox name = new EditBox(this.font, left + 80, 84, width - 80, 20, Component.literal("Their name")) {
            @Override
            public boolean keyPressed(KeyEvent event) {
                if (this.isFocused() && event.input() == InputConstants.KEY_TAB) {
                    String completion = FriendCodeScreen.this.completion(this.getValue());
                    if (completion != null) {
                        this.setValue(completion);
                        return true;
                    }
                }
                return super.keyPressed(event);
            }
        };
        name.setMaxLength(16);
        name.setHint(Component.literal("who gave you the code"));
        name.setValue(this.draftName);
        name.setResponder(value -> {
            this.draftName = value;
            String completion = this.completion(value);
            name.setSuggestion(completion == null || value.isEmpty() ? null : completion.substring(value.length()));
        });
        this.addRenderableWidget(name);
        EditBox codeBox = this.addRenderableWidget(new EditBox(this.font, left + 80, 110, width - 80, 20, Component.literal("Friend code")));
        codeBox.setMaxLength(32);
        codeBox.setHint(Component.literal("like 7K2MQ-X9DPA-4RW3Z"));
        codeBox.setValue(this.draftCode);
        codeBox.setResponder(value -> this.draftCode = value);
        Button send = Button.builder(Component.literal("Send request"), b -> this.send()).bounds(left, buttonsY, half, 20).build();
        send.active = !this.sending;
        this.addRenderableWidget(send);
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose())
            .bounds(left + half + 4, buttonsY, half, 20).build());
    }

    private void send() {
        EncryptedChat.setSelfName(this.minecraft.getUser().getName());
        String name = this.draftName.trim();
        this.sending = true;
        this.say("Preparing the code's key...", RowList.GRAY);
        this.rebuildWidgets();
        Friends.requestWithCode(name, this.draftCode).whenComplete((outcome, error) -> this.minecraft.execute(() -> {
            this.sending = false;
            if (outcome != null && outcome.ok()) {
                this.parent.say(outcome.message(), RowList.GREEN);
                if (this.minecraft.gui.screen() == this) {
                    this.minecraft.gui.setScreen(this.parent);
                }
                return;
            }
            this.say(outcome != null ? outcome.message() : "That didn't work: " + error, RowList.RED);
            if (this.minecraft.gui.screen() == this) {
                this.rebuildWidgets();
            }
        }));
    }

    // An online player whose name starts with what's typed
    private @Nullable String completion(String typed) {
        ClientPacketListener connection = this.minecraft.getConnection();
        if (typed.isEmpty() || connection == null) {
            return null;
        }
        return connection.getOnlinePlayers().stream().map(p -> p.getProfile().name())
            .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed.toLowerCase(Locale.ROOT))).findFirst().orElse(null);
    }

    private void say(String text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        int width = this.contentWidth();
        int left = (this.width - width) / 2;
        graphics.centeredText(this.font, this.title.getString(), this.width / 2, 14, RowList.WHITE);
        if (this.code != null) {
            int boxWidth = this.font.width(this.code) * 2 + 16;
            graphics.fill(this.width / 2 - boxWidth / 2, 40, this.width / 2 + boxWidth / 2, 62, 0xFF202020);
            graphics.centeredText(this.font, Component.literal(this.code), this.width / 2, 47, 0xFF55FFFF);
            graphics.textWithWordWrap(this.font, Component.literal("Send this code to the friend you want to add, outside Minecraft (Discord, Signal,"
                + " in person), never in game chat. They choose \"I have a friend code\" and enter it with your name. It works once, for 24"
                + " hours; whoever uses it exchanges keys with you without you clicking anything. Afterwards, compare safety codes to be sure"
                + " it was them."), left, 72, width, RowList.GRAY);
        } else {
            graphics.text(this.font, "Their name", left, 90, RowList.WHITE);
            graphics.text(this.font, "Friend code", left, 116, RowList.WHITE);
            graphics.textWithWordWrap(this.font, Component.literal("Someone sent you a friend code outside Minecraft. Enter their name and the"
                + " code: your client asks them to exchange keys, encrypted with the code, and theirs answers when they're online."),
                left, 30, width, RowList.GRAY);
        }
        if (this.status != null) {
            graphics.centeredText(this.font, this.status, this.width / 2, this.height - 42, this.statusColor);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }
}
