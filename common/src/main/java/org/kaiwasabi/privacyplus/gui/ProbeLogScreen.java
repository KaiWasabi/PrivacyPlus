package org.kaiwasabi.privacyplus.gui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.jspecify.annotations.Nullable;

// Everything the probe alarm noticed this session, newest first: when, which server, and what it tried.
// The list lives in memory only (see ProbeAlarm), and new probes show up while the screen is open.
public final class ProbeLogScreen extends Screen {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private @Nullable ProbeList list;
    private int shown;

    public ProbeLogScreen(@Nullable Screen parent) {
        super(Component.literal("Probe log"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.layout.removeChildren();
        this.layout.addTitleHeader(this.title, this.font);
        int top = this.layout.getHeaderHeight();
        this.list = this.layout.addToContents(new ProbeList(this.minecraft, this.width, this.layout.getContentHeight(), top));
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
        this.list.updateSize(this.width, this.layout);
        this.refresh();
    }

    private void refresh() {
        List<ProbeAlarm.Event> events = newestFirst(ProbeAlarm.events());
        this.shown = events.size();
        if (this.list != null) {
            this.list.show(events);
        }
    }

    @Override
    public void tick() {
        if (ProbeAlarm.events().size() != this.shown) {
            this.refresh();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        if (this.shown == 0) {
            int width = Math.min(300, this.width - 40);
            int y = this.layout.getHeaderHeight() + 24;
            graphics.centeredText(this.font, "No probes noticed this session.", this.width / 2, y, SettingsList.WHITE);
            String more = PrivacyPlusConfig.probeAlarm
                ? "When a server tries to fingerprint or track you, it's listed here. Nothing is saved to disk."
                : "The probe alarm is off, so nothing is being recorded. Turn it on in the Stealth tab.";
            for (FormattedCharSequence line : this.font.split(Component.literal(more), width)) {
                y += 12;
                graphics.centeredText(this.font, line, this.width / 2, y, SettingsList.GRAY);
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    // Newest first; probes noticed in the same instant keep the order they were reported in, reversed
    static List<ProbeAlarm.Event> newestFirst(List<ProbeAlarm.Event> events) {
        List<ProbeAlarm.Event> sorted = new ArrayList<>(events.reversed());
        sorted.sort(Comparator.comparing(ProbeAlarm.Event::at).reversed());
        return sorted;
    }

    static String time(Instant at) {
        return TIME.format(at);
    }

    // "Probed with a sign: unknown key sodium.option_impact.low"
    static String describe(ProbeAlarm.Event event) {
        String what = event.kind().description;
        return what.substring(0, 1).toUpperCase(Locale.ROOT) + what.substring(1) + ": " + event.detail();
    }

    private static final class ProbeList extends ObjectSelectionList<ProbeList.Entry> {
        private ProbeList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, 24);
        }

        private void show(List<ProbeAlarm.Event> events) {
            double scroll = this.scrollAmount();
            this.replaceEntries(events.stream().map(Entry::new).toList());
            this.setScrollAmount(scroll);
        }

        @Override
        public int getRowWidth() {
            return Math.min(340, this.width - 2 * SettingsList.SIDE_MARGIN);
        }

        // Time and server on the first line, what the server tried on the second
        private static final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final ProbeAlarm.Event event;
            private final String time;
            private final String description;

            private Entry(ProbeAlarm.Event event) {
                this.event = event;
                this.time = time(event.at());
                this.description = describe(event);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
                Font font = Minecraft.getInstance().font;
                int x = this.getContentX() + 2;
                int width = this.getContentWidth() - 4;
                int timeWidth = font.width(this.time) + 6;
                String server = SettingsList.fit(font, this.event.server(), width - timeWidth);
                String description = SettingsList.fit(font, this.description, width);
                graphics.text(font, this.time, x, this.getContentY() + 1, SettingsList.GRAY);
                graphics.text(font, server, x + timeWidth, this.getContentY() + 1, SettingsList.WHITE);
                graphics.text(font, description, x, this.getContentY() + 11, 0xFFD0D0D0);
                if (hovered && (!server.equals(this.event.server()) || !description.equals(this.description))) {
                    graphics.setTooltipForNextFrame(font, font.split(Component.literal(this.event.server() + "\n" + this.description), 240),
                        mouseX, mouseY);
                }
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.time + ", " + this.event.server() + ": " + this.description);
            }
        }
    }
}
