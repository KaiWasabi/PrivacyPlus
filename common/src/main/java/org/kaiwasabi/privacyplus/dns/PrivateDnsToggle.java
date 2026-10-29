package org.kaiwasabi.privacyplus.dns;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;

// "Private DNS: off/Cloudflare/Quad9" in the top-left corner of the multiplayer screen; click to switch.
public final class PrivateDnsToggle extends AbstractWidget {
    private static final Tooltip TOOLTIP = Tooltip.create(Component.literal(
        "Look up servers with encrypted DNS, so your router, internet provider or Wi-Fi can't see which servers "
            + "you look up. Click to switch service. If it doesn't answer, servers won't connect: your normal DNS "
            + "isn't used instead. Also: /pp dns"));
    private final Font font;

    public PrivateDnsToggle(int x, int y, Font font) {
        super(x, y, font.width(label()) + 2, 10, Component.literal("Private DNS"));
        this.font = font;
        this.setTooltip(TOOLTIP);
    }

    private static String label() {
        PrivateDns.Provider provider = PrivateDns.current();
        return "Private DNS: " + (provider == null ? "off" : provider.label());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        PrivacyPlusConfig.setPrivateDns(PrivateDns.next(PrivacyPlusConfig.privateDns));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        String label = label();
        this.setWidth(this.font.width(label) + 2);
        boolean on = PrivateDns.current() != null;
        int rgb = on ? 0x7FD4FF : 0xAAAAAA;
        if (this.isHovered()) {
            rgb = on ? 0xC2EBFF : 0xFFFFFF;
        }
        graphics.text(this.font, label, this.getX(), this.getY() + 1, 0xFF000000 | rgb);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
