package org.kaiwasabi.privacyplus.incognito;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;

// "Incognito: on/off" under the telemetry counter on the title screen; click to switch.
public final class IncognitoToggle extends AbstractWidget {
    private static final Tooltip TOOLTIP = Tooltip.create(Component.literal(
        "When on, chat isn't written to the log files, commands aren't saved to command_history.txt, "
            + "the direct-connect address isn't remembered and the log doesn't record which server you join. "
            + "Click to switch. Also: /pp incognito"));
    private final Font font;

    public IncognitoToggle(int x, int y, Font font) {
        super(x, y, font.width(label()) + 2, 10, Component.literal("Incognito"));
        this.font = font;
        this.setTooltip(TOOLTIP);
    }

    private static String label() {
        return "Incognito: " + (PrivacyPlusConfig.incognito ? "on" : "off");
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        PrivacyPlusConfig.setIncognito(!PrivacyPlusConfig.incognito);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        String label = label();
        this.setWidth(this.font.width(label) + 2);
        int rgb = PrivacyPlusConfig.incognito ? 0xD58CFF : 0xAAAAAA;
        if (this.isHovered()) {
            rgb = PrivacyPlusConfig.incognito ? 0xE8C2FF : 0xFFFFFF;
        }
        // Fades in with the rest of the title screen
        graphics.text(this.font, label, this.getX(), this.getY() + 1, (int) (this.alpha * 255) << 24 | rgb);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
