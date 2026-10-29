package org.kaiwasabi.privacyplus.guard;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.EncryptedChat;
import org.jspecify.annotations.Nullable;

// clipboardGuard: clicking text that would copy something asks first, showing exactly what would be
// copied and what about it looks risky. Copy and Cancel both return to the screen the click came from.
public final class ClipboardPromptScreen extends ConfirmScreen {
    private static final int MAX_WIDTH = 400;

    private final String value;
    private final List<ClipboardGuard.Flag> flags;

    private ClipboardPromptScreen(BooleanConsumer callback, String value, List<ClipboardGuard.Flag> flags) {
        super(callback, Component.literal("Copy this to your clipboard?").withStyle(ChatFormatting.BOLD),
            Component.literal(ClipboardGuard.preview(value).text()), Component.literal("Copy"), Component.literal("Cancel"));
        this.value = value;
        this.flags = flags;
    }

    // Called for every copy-to-clipboard click before vanilla copies. True when the click was taken care of
    // here (the prompt opened, or it was one of PrivacyPlus's own chat buttons), so vanilla must not copy.
    public static boolean intercept(Minecraft mc, @Nullable Screen activeScreen, ClickEvent.CopyToClipboard event) {
        if (!PrivacyPlusConfig.clipboardGuard) {
            return false;
        }
        // PrivacyPlus's own chat buttons are copy events too. ChatScreenMixin normally handles them first;
        // should one ever get here, it still does what the button says rather than prompting.
        Style style = Style.EMPTY.withClickEvent(event);
        if (EncryptedChat.opensSettings(style)) {
            return false;
        }
        if (EncryptedChat.runLocalClick(mc, style)) {
            return true;
        }
        String value = event.value();
        mc.gui.setScreen(new ClipboardPromptScreen(copy -> {
            if (copy) {
                mc.keyboardHandler.setClipboard(value);
            }
            mc.gui.setScreen(activeScreen);
        }, value, ClipboardGuard.inspect(value)));
        return true;
    }

    public String value() {
        return this.value;
    }

    public List<ClipboardGuard.Flag> flags() {
        return this.flags;
    }

    @Override
    protected LayoutElement addMessage() {
        int width = Math.min(this.width - 50, MAX_WIDTH);
        ClipboardGuard.Preview preview = ClipboardGuard.preview(this.value);
        LinearLayout column = LinearLayout.vertical().spacing(8);
        column.defaultCellSetting().alignHorizontallyCenter();
        column.addChild(new MultiLineTextWidget(Component.literal("Clicking it copies this text (" + ClipboardGuard.length(this.value)
            + (preview.truncated() ? ", only the start is shown" : "") + "):").withStyle(ChatFormatting.GRAY), this.font)
            .setMaxWidth(width).setCentered(true));
        MutableComponent shown = Component.literal(preview.text().isEmpty() ? "(nothing)" : preview.text()).withStyle(ChatFormatting.WHITE);
        if (preview.truncated()) {
            shown.append(Component.literal(" …").withStyle(ChatFormatting.DARK_GRAY));
        }
        column.addChild(new MultiLineTextWidget(shown, this.font).setMaxWidth(width).setMaxRows(12));
        if (!this.flags.isEmpty()) {
            MutableComponent warnings = Component.empty();
            for (ClipboardGuard.Flag flag : this.flags) {
                if (!warnings.getSiblings().isEmpty()) {
                    warnings.append("\n");
                }
                warnings.append(Component.literal("• " + flag.text())
                    .withStyle(flag.kind() == ClipboardGuard.Kind.COMMAND ? ChatFormatting.RED : ChatFormatting.GOLD));
            }
            column.addChild(new MultiLineTextWidget(warnings, this.font).setMaxWidth(width).setMaxRows(10));
        }
        return column;
    }
}
