package org.kaiwasabi.privacyplus.chat;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// The gear next to the lock in the chat box: opens the encrypted chat settings. Drawn from
// rectangles, like the lock, so the mod ships no textures.
public final class GearButton extends AbstractWidget {
    public static final int SIZE = 12;
    private final Runnable onClick;

    public GearButton(int x, int y, Runnable onClick) {
        super(x, y, SIZE, SIZE, Component.literal("Encrypted chat settings"));
        this.onClick = onClick;
        this.setTooltip(Tooltip.create(Component.literal("Encrypted chat settings")));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        this.onClick.run();
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        int color = this.isHovered() ? 0xFFFFFFFF : 0xFFB0B0B0;
        int x = this.getX();
        int y = this.getY();
        // Teeth, straight and diagonal, around a ring with a hole
        graphics.fill(x + 5, y + 1, x + 7, y + 11, color);
        graphics.fill(x + 1, y + 5, x + 11, y + 7, color);
        graphics.fill(x + 2, y + 2, x + 4, y + 4, color);
        graphics.fill(x + 8, y + 2, x + 10, y + 4, color);
        graphics.fill(x + 2, y + 8, x + 4, y + 10, color);
        graphics.fill(x + 8, y + 8, x + 10, y + 10, color);
        graphics.fill(x + 3, y + 3, x + 9, y + 9, color);
        graphics.fill(x + 5, y + 5, x + 7, y + 7, 0xFF202020);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
