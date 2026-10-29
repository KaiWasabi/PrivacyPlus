package org.kaiwasabi.privacyplus.coords;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.kaiwasabi.privacyplus.chat.Coords;
import org.jspecify.annotations.Nullable;

// The map pin next to the gear and lock in the chat box: left-click shares your location (encrypted)
// with the channel in use or the one used last, right-click picks a channel. Drawn from rectangles,
// like the lock, so the mod ships no textures.
public final class CoordsButton extends AbstractWidget {
    public static final int SIZE = 12;
    private final Runnable onShare;
    private final Runnable onPick;
    private @Nullable String tooltipFor;

    public CoordsButton(int x, int y, Runnable onShare, Runnable onPick) {
        super(x, y, SIZE, SIZE, Component.literal("Share my location"));
        this.onShare = onShare;
        this.onPick = onPick;
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT || buttonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.buttonInfo().button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            this.onPick.run();
        } else {
            this.onShare.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Channel channel = Coords.defaultChannel();
        int color = channel != null ? (this.isHovered() ? 0xFFFF7070 : 0xFFE04040) : (this.isHovered() ? 0xFFDDDDDD : 0xFF8A8A8A);
        int x = this.getX();
        int y = this.getY();
        // Round head with a hole, narrowing to a point
        graphics.fill(x + 4, y + 1, x + 8, y + 2, color);
        graphics.fill(x + 3, y + 2, x + 9, y + 3, color);
        graphics.fill(x + 2, y + 3, x + 10, y + 6, color);
        graphics.fill(x + 3, y + 6, x + 9, y + 7, color);
        graphics.fill(x + 4, y + 7, x + 8, y + 9, color);
        graphics.fill(x + 5, y + 9, x + 7, y + 11, color);
        graphics.fill(x + 5, y + 3, x + 7, y + 5, 0xFF202020);

        String text = channel != null
            ? "Share my location with " + channel.name() + " (encrypted)\nLeft-click to share, right-click to pick a channel"
            : "Share my location (encrypted)\nAdd a channel first: click to open the settings";
        if (!text.equals(this.tooltipFor)) {
            this.tooltipFor = text;
            this.setTooltip(Tooltip.create(Component.literal(text)));
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
