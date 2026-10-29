package org.kaiwasabi.privacyplus.guard;

import com.mojang.blaze3d.Blaze3D;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.net.URI;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// Shown instead of opening a link LinkGuard has doubts about, even when Minecraft's own link prompt is
// switched off: the whole address with its real site picked out, then what's wrong with it in plain words.
// Links without warnings never get here and behave exactly as in vanilla.
public final class LinkWarningScreen extends ConfirmScreen {
    // Longer addresses are cut on screen (Copy link still copies all of it)
    private static final int SHOWN_CHARS = 400;
    private static final int MAX_WIDTH = 400;

    private final LinkGuard.Analysis analysis;

    private LinkWarningScreen(BooleanConsumer callback, LinkGuard.Analysis analysis) {
        super(callback, Component.literal("Check this link before opening it").withStyle(ChatFormatting.BOLD), narration(analysis),
            Component.literal("Open anyway"), Component.literal("Cancel"));
        this.analysis = analysis;
    }

    // Shows the warning screen if linkGuard is on and the link has warnings; returns whether it did. Open
    // anyway, Copy link and Cancel all return to parent.
    public static boolean showIfRisky(Minecraft mc, @Nullable Screen parent, URI uri) {
        if (!PrivacyPlusConfig.linkGuard) {
            return false;
        }
        LinkGuard.Analysis analysis = LinkGuard.analyze(uri);
        if (!analysis.risky()) {
            return false;
        }
        mc.gui.setScreen(new LinkWarningScreen(open -> {
            if (open) {
                Blaze3D.openUri(uri);
            }
            mc.gui.setScreen(parent);
        }, analysis));
        return true;
    }

    public LinkGuard.Analysis analysis() {
        return this.analysis;
    }

    @Override
    protected LayoutElement addMessage() {
        int width = Math.min(this.width - 50, MAX_WIDTH);
        LinearLayout column = LinearLayout.vertical().spacing(8);
        column.defaultCellSetting().alignHorizontallyCenter();
        column.addChild(new MultiLineTextWidget(address(this.analysis), this.font).setMaxWidth(width).setMaxRows(8).setCentered(true));
        if (!this.analysis.site().isEmpty()) {
            column.addChild(new MultiLineTextWidget(Component.literal("Site: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(this.analysis.site()).withStyle(ChatFormatting.BOLD, ChatFormatting.WHITE)), this.font)
                .setMaxWidth(width).setCentered(true));
        }
        column.addChild(new MultiLineTextWidget(warnings(this.analysis), this.font).setMaxWidth(width).setMaxRows(14));
        return column;
    }

    @Override
    protected void addButtons(LinearLayout buttons) {
        this.yesButton = buttons.addChild(Button.builder(this.yesButtonComponent, b -> this.callback.accept(true)).width(100).build());
        buttons.addChild(Button.builder(Component.literal("Copy link"), b -> {
            this.minecraft.keyboardHandler.setClipboard(this.analysis.address());
            this.callback.accept(false);
        }).width(100).build());
        this.noButton = buttons.addChild(Button.builder(this.noButtonComponent, b -> this.callback.accept(false)).width(100).build());
    }

    // The address in gray with the site in bold white, so a familiar name elsewhere in it can't pass for it
    static Component address(LinkGuard.Analysis analysis) {
        String address = analysis.address();
        int cut = Math.min(address.length(), Math.max(SHOWN_CHARS, analysis.siteEnd()));
        MutableComponent text = Component.empty();
        if (analysis.siteEnd() > analysis.siteStart()) {
            text.append(Component.literal(address.substring(0, analysis.siteStart())).withStyle(ChatFormatting.GRAY));
            text.append(Component.literal(address.substring(analysis.siteStart(), analysis.siteEnd()))
                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD, ChatFormatting.UNDERLINE));
            text.append(Component.literal(address.substring(analysis.siteEnd(), cut)).withStyle(ChatFormatting.GRAY));
        } else {
            text.append(Component.literal(address.substring(0, cut)).withStyle(ChatFormatting.GRAY));
        }
        if (cut < address.length()) {
            text.append(Component.literal("… (" + (address.length() - cut) + " more characters)").withStyle(ChatFormatting.DARK_GRAY));
        }
        return text;
    }

    private static Component warnings(LinkGuard.Analysis analysis) {
        MutableComponent text = Component.empty();
        for (LinkGuard.Warning warning : analysis.warnings()) {
            if (!text.getSiblings().isEmpty()) {
                text.append("\n");
            }
            ChatFormatting color = warning.kind() == LinkGuard.Kind.IP_LOGGER || warning.kind() == LinkGuard.Kind.USER_INFO
                || warning.kind() == LinkGuard.Kind.LOOKALIKE ? ChatFormatting.RED : ChatFormatting.GOLD;
            text.append(Component.literal("• " + warning.text()).withStyle(color));
        }
        return text;
    }

    private static Component narration(LinkGuard.Analysis analysis) {
        MutableComponent text = Component.literal(analysis.address() + "\n");
        analysis.warnings().forEach(w -> text.append("\n" + w.text()));
        return text;
    }
}
