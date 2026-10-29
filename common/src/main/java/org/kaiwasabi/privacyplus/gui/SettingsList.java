package org.kaiwasabi.privacyplus.gui;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.dns.PrivateDns;
import org.kaiwasabi.privacyplus.gui.Settings.Choice;
import org.kaiwasabi.privacyplus.gui.Settings.PrivateDnsChoice;
import org.kaiwasabi.privacyplus.gui.Settings.Setting;
import org.kaiwasabi.privacyplus.gui.Settings.Toggle;
import org.jspecify.annotations.Nullable;

// The scrolling rows of one settings tab: an introduction, one row per setting (label on the left, control on
// the right, the explanation as a tooltip on both) and the tab's buttons. Rows place their widgets whenever
// they move, so clicks always land where the widgets are drawn. Controls re-read the config every frame, so
// they stay right when something else (the title screen's incognito switch, /pp) changes a setting.
public final class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
    public static final int CONTROL_WIDTH = 100;
    // Leaves room for the scrollbar, which sits just right of the rows
    public static final int SIDE_MARGIN = 16;
    private static final int MAX_ROW_WIDTH = 310;
    private static final int ROW_HEIGHT = 24;
    private static final int GAP = 6;
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFFA0A0A0;
    static final int RED = 0xFFFF6060;
    static final int GREEN = 0xFF55FF55;
    private static final Component ON = Component.literal("On");
    private static final Component OFF = Component.literal("Off");

    // A line of feedback under a control, such as a validation error
    record Status(String text, int color) {
    }

    SettingsList(Minecraft minecraft, int width, int height, int y) {
        super(minecraft, width, height, y, ROW_HEIGHT);
    }

    static int rowWidth(int listWidth) {
        return Math.min(MAX_ROW_WIDTH, listWidth - 2 * SIDE_MARGIN);
    }

    @Override
    public int getRowWidth() {
        return rowWidth(this.width);
    }

    // The screen draws the background and separators for the whole tab area
    @Override
    protected void extractListBackground(GuiGraphicsExtractor graphics) {
    }

    @Override
    protected void extractListSeparators(GuiGraphicsExtractor graphics) {
    }

    void addText(String text, int color) {
        List<FormattedCharSequence> lines = this.minecraft.font.split(Component.literal(text), this.getRowWidth() - 4);
        this.addEntry(new TextRow(lines, color), lines.size() * 10 + 8);
    }

    void addSetting(Setting setting) {
        this.addEntry(new SettingRow(setting));
    }

    void addButton(Button button, BooleanSupplier active) {
        this.addEntry(new ButtonRow(button, active));
    }

    void addStatus(Supplier<@Nullable Status> status) {
        this.addEntry(new StatusRow(status), 14);
    }

    // The box for another DNS-over-HTTPS service, under the Private DNS switch. Returns the line that explains
    // what to type and reports errors, to go under it.
    Supplier<@Nullable Status> addDnsAddress(String draft, Consumer<String> onEdit) {
        DnsAddressRow row = new DnsAddressRow(draft, onEdit);
        this.addEntry(row);
        return row::status;
    }

    public List<Row> rows() {
        return this.children();
    }

    // The control for a setting, and how to bring it up to date with the config
    private record Control(AbstractWidget widget, Runnable refresh) {
    }

    private static Control control(Setting setting) {
        Tooltip tooltip = Tooltip.create(Component.literal(setting.tooltip()));
        Component name = Component.literal(setting.label());
        return switch (setting.control()) {
            case Toggle toggle -> {
                CycleButton<Boolean> button = CycleButton.booleanBuilder(ON, OFF, toggle.get().getAsBoolean())
                    .displayOnlyValue()
                    .withTooltip(value -> tooltip)
                    .create(0, 0, CONTROL_WIDTH, 20, name, (b, value) -> toggle.set().accept(value));
                yield new Control(button, () -> sync(button, toggle.get().getAsBoolean()));
            }
            case Choice<?> choice -> choiceControl(choice, name, tooltip);
            case PrivateDnsChoice dns -> {
                Button button = Button.builder(Component.literal(dnsName()),
                        b -> PrivacyPlusConfig.setPrivateDns(PrivateDns.next(PrivacyPlusConfig.privateDns)))
                    .size(CONTROL_WIDTH, 20)
                    .tooltip(tooltip)
                    .createNarration(message -> name.copy().append(": ").append(message.get()))
                    .build();
                yield new Control(button, () -> button.setMessage(Component.literal(dnsName())));
            }
        };
    }

    private static <T> Control choiceControl(Choice<T> choice, Component name, Tooltip tooltip) {
        CycleButton<T> button = CycleButton.<T>builder(value -> Component.literal(choice.name().apply(value)), choice.get())
            .withValues(choice.values().get())
            .displayOnlyValue()
            .withTooltip(value -> tooltip)
            .create(0, 0, CONTROL_WIDTH, 20, name, (b, value) -> choice.set().accept(value));
        return new Control(button, () -> sync(button, choice.get().get()));
    }

    private static <T> void sync(CycleButton<T> button, T value) {
        if (!Objects.equals(button.getValue(), value)) {
            button.setValue(value);
        }
    }

    static String dnsName() {
        PrivateDns.Provider provider = PrivateDns.current();
        return provider == null ? "Off" : provider.label();
    }

    // Shortens text that doesn't fit, ending it with "..."
    static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))).stripTrailing() + "...";
    }

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        @Override
        public void setX(int x) {
            super.setX(x);
            this.layout();
        }

        @Override
        public void setY(int y) {
            super.setY(y);
            this.layout();
        }

        @Override
        public void setWidth(int width) {
            super.setWidth(width);
            this.layout();
        }

        void layout() {
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private static final class TextRow extends Row {
        private final List<FormattedCharSequence> lines;
        private final int color;

        private TextRow(List<FormattedCharSequence> lines, int color) {
            this.lines = lines;
            this.color = color;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = Minecraft.getInstance().font;
            for (int i = 0; i < this.lines.size(); i++) {
                graphics.text(font, this.lines.get(i), this.getContentX(), this.getContentY() + 2 + i * 10, this.color);
            }
        }
    }

    // One setting: its label, and the button that changes it
    public static final class SettingRow extends Row {
        private final Setting setting;
        private final Control control;
        private final Tooltip tooltip;

        private SettingRow(Setting setting) {
            this.setting = setting;
            this.control = SettingsList.control(setting);
            this.tooltip = Tooltip.create(Component.literal(setting.tooltip()));
        }

        public Setting setting() {
            return this.setting;
        }

        public AbstractWidget control() {
            return this.control.widget();
        }

        // Room for the label left of the control
        public int labelWidth() {
            return this.getContentWidth() - CONTROL_WIDTH - GAP;
        }

        public boolean labelFits() {
            return Minecraft.getInstance().font.width(this.setting.label()) <= this.labelWidth();
        }

        @Override
        void layout() {
            this.control.widget().setPosition(this.getContentRight() - CONTROL_WIDTH, this.getContentY());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Font font = Minecraft.getInstance().font;
            AbstractWidget widget = this.control.widget();
            this.control.refresh().run();
            widget.extractRenderState(graphics, mouseX, mouseY, a);
            String label = fit(font, this.setting.label(), this.labelWidth());
            graphics.text(font, label, this.getContentX(), this.getContentYMiddle() - 4, WHITE);
            if (hovered && !widget.isMouseOver(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font, this.tooltip.toCharSequence(Minecraft.getInstance()), mouseX, mouseY);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.control.widget());
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.control.widget());
        }
    }

    // A button as wide as the row, for the tab's actions
    private static final class ButtonRow extends Row {
        private final Button button;
        private final BooleanSupplier active;

        private ButtonRow(Button button, BooleanSupplier active) {
            this.button = button;
            this.active = active;
        }

        @Override
        void layout() {
            this.button.setPosition(this.getContentX(), this.getContentY());
            this.button.setWidth(this.getContentWidth());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            this.button.active = this.active.getAsBoolean();
            this.button.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.button);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.button);
        }
    }

    // One line of feedback; the full text shows as a tooltip when it doesn't fit
    private static final class StatusRow extends Row {
        private final Supplier<@Nullable Status> status;

        private StatusRow(Supplier<@Nullable Status> status) {
            this.status = status;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            Status status = this.status.get();
            if (status == null) {
                return;
            }
            Font font = Minecraft.getInstance().font;
            String shown = fit(font, status.text(), this.getContentWidth());
            graphics.text(font, shown, this.getContentX(), this.getContentY(), status.color());
            if (hovered && !shown.equals(status.text())) {
                graphics.setTooltipForNextFrame(font, font.split(Component.literal(status.text()), 200), mouseX, mouseY);
            }
        }
    }

    // A text box for the https:// address of another DNS-over-HTTPS service, and a button to switch to it
    // (Enter works too). Nothing changes while typing, so a half-typed address is never used.
    private static final class DnsAddressRow extends Row {
        private static final int USE_WIDTH = 50;
        private final EditBox box;
        private final Button use;

        private DnsAddressRow(String draft, Consumer<String> onEdit) {
            Minecraft minecraft = Minecraft.getInstance();
            this.box = new EditBox(minecraft.font, 0, 0, 100, 18, Component.literal("Other DNS-over-HTTPS service"));
            this.box.setMaxLength(256);
            this.box.setHint(Component.literal("https:// address of another service"));
            this.box.setValue(draft);
            this.box.moveCursorToStart(false);
            this.box.setTooltip(Tooltip.create(Component.literal(
                "The https:// address of another DNS-over-HTTPS service, such as https://dns.example/dns-query. "
                    + "Its own name is looked up with your normal DNS.")));
            this.use = Button.builder(Component.literal("Use"), b -> {
                    if (this.canApply()) {
                        this.apply();
                    }
                })
                .size(USE_WIDTH, 20)
                .tooltip(Tooltip.create(Component.literal("Look up servers with this service from now on.")))
                .build();
            this.use.active = this.canApply();
            this.box.setResponder(value -> {
                this.use.active = this.canApply();
                onEdit.accept(value);
            });
        }

        // Only called once canApply() has checked the address
        private void apply() {
            PrivacyPlusConfig.setPrivateDns(this.box.getValue());
            this.use.active = false;
        }

        private boolean canApply() {
            String value = this.box.getValue().trim();
            if (value.isEmpty()) {
                return false;
            }
            try {
                PrivateDns.Provider provider = PrivateDns.parse(value);
                return !(provider == null ? "off" : provider.setting()).equals(PrivacyPlusConfig.privateDns);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        // What to type, what's wrong with it, or whether it's in use
        Status status() {
            String value = this.box.getValue().trim();
            if (value.isEmpty()) {
                return new Status("Or paste the https:// address of another DNS-over-HTTPS service above.", GRAY);
            }
            PrivateDns.Provider provider;
            try {
                provider = PrivateDns.parse(value);
            } catch (IllegalArgumentException e) {
                return new Status(e.getMessage() + ".", RED);
            }
            String setting = provider == null ? "off" : provider.setting();
            if (setting.equals(PrivacyPlusConfig.privateDns)) {
                String text = provider == null ? "Private DNS is off." : "In use: servers are looked up at " + provider.label() + ".";
                return new Status(text, GREEN);
            }
            return new Status("Press Use (or Enter) to switch to " + (provider == null ? "off" : provider.label()) + ".", GRAY);
        }

        @Override
        void layout() {
            this.box.setPosition(this.getContentX() + 1, this.getContentY() + 1);
            this.box.setWidth(this.getContentWidth() - USE_WIDTH - GAP - 2);
            this.use.setPosition(this.getContentRight() - USE_WIDTH, this.getContentY());
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (this.box.isFocused() && event.isConfirmation()) {
                if (this.canApply()) {
                    this.apply();
                }
                return true;
            }
            return super.keyPressed(event);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            this.use.active = this.canApply();
            this.box.extractRenderState(graphics, mouseX, mouseY, a);
            this.use.extractRenderState(graphics, mouseX, mouseY, a);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(this.box, this.use);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(this.box, this.use);
        }
    }
}
