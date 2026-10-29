package org.kaiwasabi.privacyplus.gui;

import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.telemetry.TelemetryBlocker;

// "PrivacyPlus" as an underlined link in a corner of the title screen, the pause menu and the multiplayer
// screen; click (or focus and press Enter) for the settings, which return to that screen. Optional gray text
// can follow the link, such as the title screen's telemetry counter. Fades in with the screen's widgets.
public final class SettingsLink extends AbstractButton {
    private static final Tooltip TOOLTIP = Tooltip.create(Component.literal("Open the PrivacyPlus settings"));
    private static final Component LINK = Component.literal(PrivacyPlus.MOD_NAME).withStyle(ChatFormatting.UNDERLINE);
    private static final int COLOR = 0x8CE6A0;
    private static final int HOVER_COLOR = 0xD4FFDD;
    private static final int SUFFIX_COLOR = 0xAAAAAA;
    private final Font font;
    private final Screen screen;
    private final Supplier<String> suffix;

    public SettingsLink(int x, int y, Font font, Screen screen) {
        this(x, y, font, screen, () -> "");
    }

    public SettingsLink(int x, int y, Font font, Screen screen, Supplier<String> suffix) {
        super(x, y, font.width(LINK) + font.width(suffix.get()) + 2, 10, Component.literal(PrivacyPlus.MOD_NAME));
        this.font = font;
        this.screen = screen;
        this.suffix = suffix;
        this.setTooltip(TOOLTIP);
    }

    // The title screen's link: "PrivacyPlus: blocked 12 telemetry events" while telemetry is blocked
    public static SettingsLink withTelemetryCount(int x, int y, Font font, Screen screen) {
        return new SettingsLink(x, y, font, screen, () -> {
            if (!PrivacyPlusConfig.blockTelemetry) {
                return "";
            }
            int blocked = TelemetryBlocker.blockedCount();
            return ": blocked " + blocked + " telemetry event" + (blocked == 1 ? "" : "s");
        });
    }

    @Override
    public void onPress(InputWithModifiers input) {
        PrivacyPlusScreen.open(Minecraft.getInstance(), this.screen);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        String suffix = this.suffix.get();
        int linkWidth = this.font.width(LINK);
        this.setWidth(linkWidth + this.font.width(suffix) + 2);
        int alpha = (int) (this.alpha * 255) << 24;
        graphics.text(this.font, LINK, this.getX(), this.getY() + 1, alpha | (this.isHoveredOrFocused() ? HOVER_COLOR : COLOR));
        if (!suffix.isEmpty()) {
            graphics.text(this.font, suffix, this.getX() + linkWidth, this.getY() + 1, alpha | SUFFIX_COLOR);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
