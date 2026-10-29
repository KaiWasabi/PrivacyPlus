package org.kaiwasabi.privacyplus.chat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.chat.ChatSettings.Channel;
import org.jspecify.annotations.Nullable;

// The padlock at the right end of the chat box: left-click turns encryption on or off, shift-click moves
// on to the next channel, right-click opens the settings. Drawn from rectangles so the mod ships no
// textures, in the color of the channel in use.
public final class LockButton extends AbstractWidget {
    public static final int SIZE = 12;
    private final Runnable onToggle;
    private final Runnable onSettings;
    private @Nullable String tooltipFor;

    public LockButton(int x, int y, Runnable onToggle, Runnable onSettings) {
        super(x, y, SIZE, SIZE, Component.literal("Encrypted chat"));
        this.onToggle = onToggle;
        this.onSettings = onSettings;
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo buttonInfo) {
        return buttonInfo.button() == InputConstants.MOUSE_BUTTON_LEFT || buttonInfo.button() == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.buttonInfo().button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            this.onSettings.run();
        } else if (event.hasShiftDown()) {
            Minecraft mc = Minecraft.getInstance();
            Screen screen = mc.gui.screen();
            EncryptedChat.cycleLock(mc, screen);
            // Typing goes on in the chat box, as after a plain click
            if (screen != null && mc.gui.screen() == screen) {
                screen.children().stream().filter(EditBox.class::isInstance).findFirst().ifPresent(screen::setFocused);
            }
        } else {
            this.onToggle.run();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        Channel active = ChatSettings.active();
        boolean on = active != null;
        int color = on ? 0xFF000000 | (this.isHovered() ? brighter(active.color()) : active.color()) : (this.isHovered() ? 0xFFDDDDDD : 0xFF8A8A8A);
        int x = this.getX();
        int y = this.getY();
        // Shackle: closed when on; when off it is lifted, with the right leg out of the body
        int lift = on ? 0 : 1;
        graphics.fill(x + 3, y + 1 - lift, x + 9, y + 2 - lift, color);
        graphics.fill(x + 3, y + 1 - lift, x + 4, y + 5, color);
        graphics.fill(x + 8, y + 1 - lift, x + 9, on ? y + 5 : y + 3, color);
        graphics.fill(x + 2, y + 5, x + 10, y + 11, color);
        graphics.fill(x + 5, y + 7, x + 7, y + 9, 0xFF202020);

        String label = EncryptedChat.lockLabel();
        Channel next = on ? null : ChatSettings.lastUsed();
        String text = (label != null ? label : "Encrypted chat: off")
            + (on ? "\nClick: turn off" : next != null ? "\nClick: turn on (" + next.name() + ")" : "\nClick: add a channel")
            + "\nShift-click: next channel\nRight-click: settings";
        if (!text.equals(this.tooltipFor)) {
            this.tooltipFor = text;
            this.setTooltip(Tooltip.create(Component.literal(text)));
        }
    }

    private static int brighter(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((rgb >> 8) & 0xFF) + 40);
        int b = Math.min(255, (rgb & 0xFF) + 40);
        return r << 16 | g << 8 | b;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
