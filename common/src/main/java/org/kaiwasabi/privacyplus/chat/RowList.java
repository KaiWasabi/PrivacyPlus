package org.kaiwasabi.privacyplus.chat;

import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

// The scrolling lists the encrypted chat settings are made of: rows with text on the left (optionally
// after a color swatch) and buttons on the right, headings, and wrapped explanations.
final class RowList extends ContainerObjectSelectionList<RowList.Row> {
    static final int ROW_HEIGHT = 24;
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFFA0A0A0;
    static final int YELLOW = 0xFFFFFF55;
    static final int GREEN = 0xFF55FF55;
    static final int GOLD = 0xFFFFAA00;
    static final int RED = 0xFFFF6060;
    private final int rowWidth;

    RowList(Minecraft minecraft, int screenWidth, int top, int bottom, int rowWidth) {
        super(minecraft, screenWidth, bottom - top, top, ROW_HEIGHT);
        this.rowWidth = rowWidth;
        this.centerListVertically = false;
    }

    @Override
    public int getRowWidth() {
        return this.rowWidth;
    }

    // Text on the left, widgets right-aligned in the order given
    Row row(Component text, int color, AbstractWidget... widgets) {
        return this.row(text, color, null, widgets);
    }

    Row row(Component text, int color, @Nullable Integer swatch, AbstractWidget... widgets) {
        Row row = new Row(text, color, swatch, List.of(widgets), false);
        this.addEntry(row);
        return row;
    }

    void heading(String text) {
        this.addEntry(new Row(Component.literal(text), YELLOW, null, List.of(), true), 20);
    }

    // Gray text wrapped to the row width, as tall as it needs
    void text(Component text, int color) {
        List<FormattedCharSequence> lines = this.minecraft.font.split(text, this.rowWidth - 8);
        this.addEntry(new Wrapped(lines, color), lines.size() * 10 + 4);
    }

    void clear() {
        this.clearEntries();
    }

    // A row: text (clipped with "..." if the widgets leave too little room, the full text then on hover)
    // and widgets
    static class Row extends ContainerObjectSelectionList.Entry<Row> {
        private final Component text;
        private final int color;
        private final @Nullable Integer swatch;
        private final List<AbstractWidget> widgets;
        private final boolean heading;

        Row(Component text, int color, @Nullable Integer swatch, List<AbstractWidget> widgets, boolean heading) {
            this.text = text;
            this.color = color;
            this.swatch = swatch;
            this.widgets = widgets;
            this.heading = heading;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = Minecraft.getInstance().font;
            int right = this.getContentRight();
            for (int i = this.widgets.size() - 1; i >= 0; i--) {
                AbstractWidget widget = this.widgets.get(i);
                right -= widget.getWidth();
                widget.setPosition(right, this.getContentY() + (this.getContentHeight() - widget.getHeight()) / 2);
                right -= 4;
                widget.extractRenderState(graphics, mouseX, mouseY, a);
            }
            int x = this.getContentX();
            int y = this.getContentYMiddle() - 4;
            if (this.swatch != null) {
                graphics.fill(x, y - 1, x + 10, y + 9, 0xFF000000);
                graphics.fill(x + 1, y, x + 9, y + 8, 0xFF000000 | this.swatch);
                x += 14;
            }
            int room = right - x - 2;
            String plain = this.text.getString();
            if (font.width(this.text) <= room) {
                graphics.text(font, this.text, x, y, this.color);
            } else {
                graphics.text(font, font.plainSubstrByWidth(plain, Math.max(0, room - font.width("..."))) + "...", x, y, this.color);
                if (hovered && mouseX < right) {
                    graphics.setTooltipForNextFrame(font, this.text, mouseX, mouseY);
                }
            }
            if (this.heading) {
                graphics.fill(this.getContentX(), y + 10, this.getContentRight(), y + 11, 0x60FFFF55);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return this.widgets;
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return this.widgets;
        }
    }

    private static final class Wrapped extends Row {
        private final List<FormattedCharSequence> lines;
        private final int lineColor;

        Wrapped(List<FormattedCharSequence> lines, int color) {
            super(Component.empty(), color, null, List.of(), false);
            this.lines = lines;
            this.lineColor = color;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = Minecraft.getInstance().font;
            for (int i = 0; i < this.lines.size(); i++) {
                graphics.text(font, this.lines.get(i), this.getContentX(), this.getContentY() + 1 + i * 10, this.lineColor);
            }
        }
    }

    // A small square of color to pick, outlined when chosen
    static final class Swatch extends AbstractWidget {
        static final int SIZE = 14;
        private final int rgb;
        private final boolean chosen;
        private final IntConsumer onPick;

        Swatch(int rgb, boolean chosen, String name, IntConsumer onPick) {
            super(0, 0, SIZE, SIZE, Component.literal(name));
            this.rgb = rgb;
            this.chosen = chosen;
            this.onPick = onPick;
            this.setTooltip(Tooltip.create(Component.literal(name)));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            this.onPick.accept(this.rgb);
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            int x = this.getX();
            int y = this.getY();
            int border = this.chosen ? 0xFFFFFFFF : this.isHovered() ? 0xFFA0A0A0 : 0xFF000000;
            graphics.fill(x, y, x + SIZE, y + SIZE, border);
            graphics.fill(x + 2, y + 2, x + SIZE - 2, y + SIZE - 2, 0xFF000000 | this.rgb);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            this.defaultButtonNarrationText(output);
        }
    }
}
