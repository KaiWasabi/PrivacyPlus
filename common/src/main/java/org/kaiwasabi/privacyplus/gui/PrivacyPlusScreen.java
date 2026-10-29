package org.kaiwasabi.privacyplus.gui;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.chat.ChatSettingsScreen;
import org.kaiwasabi.privacyplus.dns.PrivateDns;
import org.kaiwasabi.privacyplus.gui.Settings.Category;
import org.kaiwasabi.privacyplus.gui.SettingsList.Status;
import org.kaiwasabi.privacyplus.logs.LogCleaner;
import org.jspecify.annotations.Nullable;

// The PrivacyPlus settings: one tab per category, each a scrolling list of settings with a tooltip that
// explains them, plus the tab's actions (probe log, encrypted chat, cleaning logs). Every change applies and
// is saved at once. Opened from the title screen, the pause menu, the multiplayer screen and /pp.
public final class PrivacyPlusScreen extends Screen {
    private static final int TITLE_HEIGHT = 16;
    private static final int TAB_HEIGHT = 24;

    private final @Nullable Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, TITLE_HEIGHT + TAB_HEIGHT, 33);
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget,
        tab -> this.selected = ((CategoryTab) tab).category, tab -> { });
    private final List<CategoryTab> tabs = new ArrayList<>();
    private Category selected;
    private @Nullable TabBar tabBar;
    // Kept while the screen is rebuilt (resizing, coming back from a sub-screen)
    private String dnsDraft;
    private @Nullable Status cleanStatus;
    private boolean cleaning;

    public PrivacyPlusScreen(@Nullable Screen parent) {
        this(parent, Category.PRIVACY);
    }

    public PrivacyPlusScreen(@Nullable Screen parent, Category tab) {
        super(Component.literal(PrivacyPlus.MOD_NAME));
        this.parent = parent;
        this.selected = tab;
        PrivateDns.Provider dns = PrivateDns.current();
        this.dnsDraft = dns != null && !PrivateDns.BUILT_IN.contains(dns) ? dns.setting() : "";
    }

    public static void open(Minecraft minecraft, @Nullable Screen parent) {
        minecraft.gui.setScreen(new PrivacyPlusScreen(parent));
    }

    public Category tab() {
        return this.selected;
    }

    public void showTab(Category category) {
        if (this.tabBar != null) {
            this.tabBar.selectTab(category.ordinal(), false);
        } else {
            this.selected = category;
        }
    }

    // Vanilla rebuilds the screen on resize and when it comes back from a sub-screen, which also re-wraps the
    // text for the new width; the tab, scroll positions, DNS draft and cleaning result are kept
    @Override
    protected void init() {
        List<Double> scrolled = this.tabs.stream().map(tab -> tab.list.scrollAmount()).toList();
        this.tabs.clear();
        this.layout.removeChildren();
        for (Category category : Category.values()) {
            CategoryTab tab = new CategoryTab(category, this.buildList(category));
            tab.pendingScroll = scrolled.isEmpty() ? 0 : scrolled.get(category.ordinal());
            this.tabs.add(tab);
        }
        this.tabBar = this.addRenderableWidget(new TabBar(this.tabManager, this.width, this.tabs));
        this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
        this.layout.visitWidgets(widget -> {
            widget.setTabOrderGroup(1);
            this.addRenderableWidget(widget);
        });
        this.arrange();
        this.tabBar.selectTab(this.selected.ordinal(), false);
    }

    // Tabs under the title, the selected tab's list between them and the footer
    private void arrange() {
        if (this.tabBar == null) {
            return;
        }
        this.tabBar.arrangeElements(this.width);
        int top = this.layout.getHeaderHeight();
        this.tabManager.setTabArea(new ScreenRectangle(0, top, this.width, this.height - this.layout.getFooterHeight() - top));
        this.layout.arrangeElements();
    }

    private SettingsList buildList(Category category) {
        int top = this.layout.getHeaderHeight();
        SettingsList list = new SettingsList(this.minecraft, this.width, this.height - top - this.layout.getFooterHeight(), top);
        list.addText(category.intro, SettingsList.GRAY);
        for (Settings.Setting setting : Settings.in(category)) {
            list.addSetting(setting);
            if (setting.control() instanceof Settings.PrivateDnsChoice) {
                list.addStatus(list.addDnsAddress(this.dnsDraft, value -> this.dnsDraft = value));
            }
        }
        switch (category) {
            case STEALTH -> list.addButton(Button.builder(Component.literal("Probe log..."),
                    b -> this.minecraft.gui.setScreen(new ProbeLogScreen(this)))
                .tooltip(Tooltip.create(Component.literal("Everything the probe alarm noticed this session.")))
                .build(), () -> true);
            case CHAT -> list.addButton(Button.builder(Component.literal("Encrypted chat settings..."),
                    b -> this.minecraft.gui.setScreen(new ChatSettingsScreen(this)))
                .tooltip(Tooltip.create(Component.literal(
                    "Channels and passphrases for encrypted chat, which only friends with the same passphrase can read.")))
                .build(), () -> true);
            case LOGS -> {
                list.addButton(Button.builder(Component.literal("Clean saved logs now"), b -> this.cleanLogs())
                    .tooltip(Tooltip.create(Component.literal(
                        "Clean the log files already saved in the logs folder now, following the settings above, "
                            + "instead of waiting for the next start.")))
                    .build(), () -> !this.cleaning);
                list.addStatus(() -> this.cleanStatus);
            }
            default -> {
            }
        }
        return list;
    }

    // Reading and rewriting old (compressed) logs can take a moment, so it runs off the render thread
    private void cleanLogs() {
        this.cleaning = true;
        this.cleanStatus = new Status("Cleaning saved logs...", SettingsList.GRAY);
        CompletableFuture.supplyAsync(LogCleaner::cleanSavedLogs, Util.ioPool())
            .handle((summary, error) -> {
                if (error == null) {
                    return new Status(summary == null ? "Done." : summary, SettingsList.GREEN);
                }
                PrivacyPlus.LOG.error("Couldn't clean the saved logs", error);
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                return new Status("Couldn't clean the logs: " + cause.getMessage(), SettingsList.RED);
            })
            .thenAcceptAsync(status -> {
                this.cleanStatus = status;
                this.cleaning = false;
            }, this.minecraft);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Ctrl+Tab and Ctrl+1..5 switch tabs, as in vanilla's tabbed screens
        return this.tabBar != null && this.tabBar.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 4, SettingsList.WHITE);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.FOOTER_SEPARATOR, 0, this.height - this.layout.getFooterHeight(),
            0.0F, 0.0F, this.width, 2, 32, 2);
    }

    @Override
    protected void extractMenuBackground(GuiGraphicsExtractor graphics) {
        int header = this.layout.getHeaderHeight();
        graphics.blit(RenderPipelines.GUI_TEXTURED, CreateWorldScreen.TAB_HEADER_BACKGROUND, 0, 0, 0.0F, 0.0F, this.width, header, 16, 16);
        this.extractMenuBackground(graphics, 0, header, this.width, this.height - header);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    // One category's list. The first layout after a rebuild restores where the old list was scrolled to.
    private static final class CategoryTab extends GridLayoutTab {
        private final Category category;
        private final SettingsList list;
        private double pendingScroll = -1;

        private CategoryTab(Category category, SettingsList list) {
            super(Component.literal(category.label));
            this.category = category;
            this.list = list;
        }

        @Override
        public Component getTabExtraNarration() {
            return Component.literal(this.category.intro);
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(this.list);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            this.list.updateSizeAndPosition(area.width(), area.height(), area.left(), area.top());
            if (this.pendingScroll >= 0) {
                this.list.setScrollAmount(this.pendingScroll);
                this.pendingScroll = -1;
            }
        }
    }

    // Vanilla's menu tabs, moved down to make room for the title
    private static final class TabBar extends MenuTabBar {
        private TabBar(TabManager manager, int width, List<CategoryTab> tabs) {
            super(0, 0, width, TAB_HEIGHT, manager, buttons(manager, tabs), ImmutableList.copyOf(tabs));
        }

        private static ImmutableList<TabButton> buttons(TabManager manager, List<CategoryTab> tabs) {
            return tabs.stream()
                .map(tab -> (TabButton) new MenuTabBar.MenuTabButton(manager, tab, 0, TAB_HEIGHT))
                .collect(ImmutableList.toImmutableList());
        }

        @Override
        public void arrangeElements(int width) {
            super.arrangeElements(width);
            this.layout.setY(TITLE_HEIGHT);
        }
    }
}
