package org.kaiwasabi.privacyplus.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.chat.ChatSettingsScreen;
import org.kaiwasabi.privacyplus.dns.PrivateDnsToggle;
import org.kaiwasabi.privacyplus.gui.PrivacyPlusScreen;
import org.kaiwasabi.privacyplus.gui.ProbeLogScreen;
import org.kaiwasabi.privacyplus.gui.Settings;
import org.kaiwasabi.privacyplus.gui.Settings.Category;
import org.kaiwasabi.privacyplus.gui.SettingsLink;
import org.kaiwasabi.privacyplus.gui.SettingsList;
import org.kaiwasabi.privacyplus.incognito.IncognitoToggle;
import org.kaiwasabi.privacyplus.stealth.ProbeAlarm;
import org.jspecify.annotations.Nullable;

// In-game checks for the PrivacyPlus settings screen; run near the end of SelfTest
final class GuiChecks {
    private GuiChecks() {
    }

    static void run(SelfTest test, Minecraft mc) throws Exception {
        entryPoints(test, mc);
        titleLinkFadesIn(test, mc);
        PrivacyPlusScreen screen = new PrivacyPlusScreen(null);
        SelfTest.onRenderThread(mc, () -> mc.gui.setScreen(screen));
        tabs(test, mc, screen);
        toggleSaves(test, mc, screen);
        privateDns(test, mc, screen);
        subScreens(test, mc, screen);
        cleanLogs(test, mc, screen);
        smallScreens(test, mc, screen);
        SelfTest.onRenderThread(mc, () -> {
            screen.onClose();
            test.check("closing the settings opened without a parent returns to the game", !(mc.gui.screen() instanceof PrivacyPlusScreen));
            // Closing can bring back a chat box the game kept open; leave no screen at all
            mc.gui.setScreen(null);
            mc.gui.setScreen(null);
        });
    }

    // The links on the pause menu, the title screen and the multiplayer screen open the settings, which return there
    private static void entryPoints(SelfTest test, Minecraft mc) {
        SelfTest.onRenderThread(mc, () -> {
            PauseScreen pause = new PauseScreen(true);
            mc.gui.setScreen(pause);
            linkOpensSettings(test, mc, pause, "pause menu");
            PauseScreen bare = new PauseScreen(false);
            mc.gui.setScreen(bare);
            test.check("no link on the pause screen without buttons (F3+Esc)", find(bare, SettingsLink.class) == null);

            TitleScreen title = new TitleScreen();
            mc.gui.setScreen(title);
            SettingsLink titleLink = find(title, SettingsLink.class);
            test.check("title screen keeps the incognito switch under the link", find(title, IncognitoToggle.class) != null
                && titleLink != null && find(title, IncognitoToggle.class).getY() >= titleLink.getBottom());
            test.check("title screen link carries the telemetry counter", titleLink != null
                && titleLink.getWidth() > mc.font.width("PrivacyPlus") + 20 == PrivacyPlusConfig.blockTelemetry);
            linkOpensSettings(test, mc, title, "title screen");

            JoinMultiplayerScreen multiplayer = new JoinMultiplayerScreen(new TitleScreen());
            mc.gui.setScreen(multiplayer);
            PrivateDnsToggle dns = find(multiplayer, PrivateDnsToggle.class);
            SettingsLink link = find(multiplayer, SettingsLink.class);
            test.check("multiplayer screen has the link and the private DNS switch on one line", dns != null && link != null
                && dns.getY() == link.getY() && dns.getX() >= link.getRight());
            linkOpensSettings(test, mc, multiplayer, "multiplayer screen");

            // The smallest GUI there is: 320x240 (the largest GUI scale)
            for (Screen small : List.of(new PauseScreen(true), new TitleScreen(), new JoinMultiplayerScreen(new TitleScreen()))) {
                mc.gui.setScreen(small);
                small.resize(320, 240);
                List<AbstractWidget> ours = small.children().stream()
                    .filter(c -> c instanceof SettingsLink || c instanceof IncognitoToggle || c instanceof PrivateDnsToggle)
                    .map(AbstractWidget.class::cast)
                    .toList();
                String overlap = ours.stream().map(widget -> overlapWithOthers(small, widget)).filter(Objects::nonNull).findFirst()
                    .orElse(find(small, SettingsLink.class) == null ? "no link" : null);
                test.check("at 320x240 the corner of the " + small.getClass().getSimpleName() + " overlaps nothing (" + overlap + ")",
                    overlap == null);
            }
            mc.gui.setScreen(null);
        });
    }

    private static void linkOpensSettings(SelfTest test, Minecraft mc, Screen screen, String where) {
        SettingsLink link = find(screen, SettingsLink.class);
        String overlap = link == null ? "no link" : overlapWithOthers(screen, link);
        test.check(where + " has a PrivacyPlus link clear of everything else (" + overlap + ")", overlap == null);
        if (link == null) {
            return;
        }
        SelfTest.click(link, InputConstants.MOUSE_BUTTON_LEFT);
        test.check("clicking the " + where + " link opens the settings", mc.gui.screen() instanceof PrivacyPlusScreen);
        if (mc.gui.screen() instanceof PrivacyPlusScreen settings) {
            settings.onClose();
            test.check("closing the settings returns to the " + where, mc.gui.screen() == screen);
        }
    }

    private static void titleLinkFadesIn(SelfTest test, Minecraft mc) throws InterruptedException {
        SettingsLink[] link = new SettingsLink[1];
        SelfTest.onRenderThread(mc, () -> {
            TitleScreen title = new TitleScreen(true);
            mc.gui.setScreen(title);
            link[0] = find(title, SettingsLink.class);
        });
        Thread.sleep(300);
        float early = link[0] == null ? 1 : link[0].getAlpha();
        Thread.sleep(2500);
        float late = link[0] == null ? 0 : link[0].getAlpha();
        test.check("title screen link fades in with the screen (alpha " + early + " then " + late + ")", early < 1 && late == 1);
        SelfTest.onRenderThread(mc, () -> mc.gui.setScreen(null));
    }

    // Every tab is reachable by clicking it, has rows, and together they show every PrivacyPlusConfig field
    private static void tabs(SelfTest test, Minecraft mc, PrivacyPlusScreen screen) throws InterruptedException {
        Set<String> shown = new HashSet<>();
        for (Category category : Category.values()) {
            SelfTest.onRenderThread(mc, () -> {
                clickThrough(screen, (AbstractWidget) tabBar(screen).children().get(category.ordinal()));
                test.check("clicking the " + category.label + " tab shows it", screen.tab() == category);
                List<SettingsList.SettingRow> rows = settingRows(list(screen));
                List<String> keys = rows.stream().map(row -> row.setting().key()).toList();
                test.check(category.label + " tab shows its settings " + keys,
                    keys.equals(Settings.in(category).stream().map(Settings.Setting::key).toList()));
                rows.forEach(row -> test.check(category.label + ": \"" + row.setting().label() + "\" has a control and its label fits",
                    row.control().visible && row.labelFits()));
                shown.addAll(keys);
            });
            Thread.sleep(700);
            screenshot(mc, "selftest-gui-" + category.name().toLowerCase(Locale.ROOT) + ".png");
        }
        Set<String> fields = Arrays.stream(PrivacyPlusConfig.class.getDeclaredFields())
            .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()))
            .map(Field::getName)
            .collect(Collectors.toSet());
        Set<String> missing = new HashSet<>(fields);
        missing.removeAll(shown);
        test.check("every PrivacyPlusConfig field has a control in the settings screen (missing: " + missing + ")", missing.isEmpty());
    }

    // Clicking a switch changes the field and rewrites config/privacyplus.properties straight away
    private static void toggleSaves(SelfTest test, Minecraft mc, PrivacyPlusScreen screen) {
        Path file = mc.gameDirectory.toPath().resolve("config/privacyplus.properties");
        SelfTest.onRenderThread(mc, () -> {
            screen.showTab(Category.LOGS);
            SettingsList list = list(screen);
            SettingsList.SettingRow row = settingRow(list, "removeChatFromOldLogs");
            boolean before = PrivacyPlusConfig.removeChatFromOldLogs;
            AbstractWidget control = row.control();
            boolean visible = control.getY() >= list.getY() && control.getBottom() <= list.getBottom();
            if (visible) {
                clickThrough(screen, control);
            } else {
                SelfTest.click(control, InputConstants.MOUSE_BUTTON_LEFT);
            }
            test.check("clicking \"Remove chat from old logs\" switches it (through the list: " + visible + ")",
                PrivacyPlusConfig.removeChatFromOldLogs == !before);
            test.check("the change is saved to privacyplus.properties at once", saved(file, "removeChatFromOldLogs=" + !before));
            SelfTest.click(control, InputConstants.MOUSE_BUTTON_LEFT);
            test.check("clicking again switches it back and saves that", PrivacyPlusConfig.removeChatFromOldLogs == before
                && saved(file, "removeChatFromOldLogs=" + before));
        });
    }

    // The DNS button cycles services; the box refuses unencrypted addresses and switches with Use or Enter
    private static void privateDns(SelfTest test, Minecraft mc, PrivacyPlusScreen screen) throws InterruptedException {
        String original = PrivacyPlusConfig.privateDns;
        EditBox[] box = new EditBox[1];
        Button[] use = new Button[1];
        SelfTest.onRenderThread(mc, () -> {
            PrivacyPlusConfig.setPrivateDns("off");
            screen.showTab(Category.SERVERS);
            SettingsList list = list(screen);
            AbstractWidget cycle = settingRow(list, "privateDns").control();
            SelfTest.click(cycle, InputConstants.MOUSE_BUTTON_LEFT);
            test.check("the Private DNS button switches from off to Cloudflare", PrivacyPlusConfig.privateDns.equals("cloudflare"));
            SelfTest.click(cycle, InputConstants.MOUSE_BUTTON_LEFT);
            test.check("then to Quad9", PrivacyPlusConfig.privateDns.equals("quad9"));
            box[0] = widgets(list, EditBox.class).stream().findFirst().orElse(null);
            use[0] = button(list, "Use");
            test.check("the Servers tab has a box and a Use button for another DNS service", box[0] != null && use[0] != null);
            box[0].setValue("http://dns.example/dns-query");
        });
        Thread.sleep(300);
        SelfTest.onRenderThread(mc, () -> {
            test.check("Use is off for an unencrypted address", !use[0].active);
            box[0].setValue("https://dns.example/dns-query");
        });
        Thread.sleep(300);
        SelfTest.onRenderThread(mc, () -> {
            test.check("Use is on for an https:// address", use[0].active);
            test.check("typing doesn't change the setting", PrivacyPlusConfig.privateDns.equals("quad9"));
            boolean visible = box[0].getY() >= list(screen).getY() && box[0].getBottom() <= list(screen).getBottom();
            if (visible) {
                clickThrough(screen, box[0]);
                screen.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                test.check("Enter in the box switches to that service",
                    PrivacyPlusConfig.privateDns.equals("https://dns.example/dns-query"));
            } else {
                SelfTest.click(use[0], InputConstants.MOUSE_BUTTON_LEFT);
                test.check("Use switches to that service", PrivacyPlusConfig.privateDns.equals("https://dns.example/dns-query"));
            }
            PrivacyPlusConfig.setPrivateDns(original);
        });
    }

    // Probe log and encrypted chat settings open from their tabs and come back to the same tab
    private static void subScreens(SelfTest test, Minecraft mc, PrivacyPlusScreen screen) throws InterruptedException {
        SelfTest.onRenderThread(mc, () -> {
            screen.showTab(Category.STEALTH);
            SelfTest.click(button(list(screen), "Probe log..."), InputConstants.MOUSE_BUTTON_LEFT);
            test.check("Probe log... opens the probe log", mc.gui.screen() instanceof ProbeLogScreen);
            AbstractSelectionList<?> entries = find(mc.gui.screen(), AbstractSelectionList.class);
            test.check("the probe log lists every probe noticed (" + ProbeAlarm.events().size() + ")",
                entries != null && entries.children().size() == ProbeAlarm.events().size());
        });
        Thread.sleep(700);
        screenshot(mc, "selftest-gui-probe-log.png");
        SelfTest.onRenderThread(mc, () -> {
            mc.gui.screen().onClose();
            test.check("closing the probe log returns to the Stealth tab", mc.gui.screen() == screen && screen.tab() == Category.STEALTH);
            screen.showTab(Category.CHAT);
            SelfTest.click(button(list(screen), "Encrypted chat settings..."), InputConstants.MOUSE_BUTTON_LEFT);
            test.check("Encrypted chat settings... opens the chat settings", mc.gui.screen() instanceof ChatSettingsScreen);
            mc.gui.screen().onClose();
            test.check("closing them returns to the Chat tab", mc.gui.screen() == screen && screen.tab() == Category.CHAT);
        });
    }

    private static void cleanLogs(SelfTest test, Minecraft mc, PrivacyPlusScreen screen) throws Exception {
        SelfTest.onRenderThread(mc, () -> {
            screen.showTab(Category.LOGS);
            SelfTest.click(button(list(screen), "Clean saved logs now"), InputConstants.MOUSE_BUTTON_LEFT);
        });
        Field cleaning = SelfTest.field(PrivacyPlusScreen.class, "cleaning");
        long deadline = System.currentTimeMillis() + 30_000;
        while (cleaning.getBoolean(screen) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        Object status = SelfTest.field(PrivacyPlusScreen.class, "cleanStatus").get(screen);
        test.check("Clean saved logs now finishes and shows its result (" + status + ")", !cleaning.getBoolean(screen) && status != null);
    }

    // At 320x240 (and a few other sizes) nothing overlaps, everything stays on screen and every label fits
    private static void smallScreens(SelfTest test, Minecraft mc, PrivacyPlusScreen screen) throws InterruptedException {
        int[][] sizes = {{320, 240}, {427, 240}, {960, 540}};
        for (int[] size : sizes) {
            for (Category category : Category.values()) {
                SelfTest.onRenderThread(mc, () -> {
                    screen.resize(size[0], size[1]);
                    screen.showTab(category);
                    List<String> problems = layoutProblems(screen);
                    test.check("at " + size[0] + "x" + size[1] + " the " + category.label + " tab lays out cleanly " + problems,
                        problems.isEmpty());
                });
                if (size[0] == 320) {
                    Thread.sleep(500);
                    screenshot(mc, "selftest-gui-small-" + category.name().toLowerCase(Locale.ROOT) + ".png");
                }
            }
        }
        SelfTest.onRenderThread(mc, () -> screen.resize(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()));
    }

    private static List<String> layoutProblems(PrivacyPlusScreen screen) {
        List<String> problems = new ArrayList<>();
        SettingsList list = list(screen);
        List<AbstractWidget> widgets = new ArrayList<>();
        tabBar(screen).children().forEach(tab -> widgets.add((AbstractWidget) tab));
        screen.children().stream().filter(Button.class::isInstance).forEach(b -> widgets.add((Button) b));
        for (SettingsList.Row row : list.rows()) {
            boolean shown = row.getY() >= list.getY() && row.getY() + row.getHeight() <= list.getBottom();
            if (shown) {
                row.children().forEach(child -> widgets.add((AbstractWidget) child));
            }
            if (shown && row instanceof SettingsList.SettingRow setting && !setting.labelFits()) {
                problems.add("label cut: " + setting.setting().label());
            }
        }
        for (AbstractWidget widget : widgets) {
            if (widget.getX() < 0 || widget.getY() < 0 || widget.getRight() > screen.width || widget.getBottom() > screen.height) {
                problems.add("off screen: " + widget.getMessage().getString());
            }
        }
        for (int i = 0; i < widgets.size(); i++) {
            for (int j = i + 1; j < widgets.size(); j++) {
                if (overlap(widgets.get(i), widgets.get(j))) {
                    problems.add("overlap: " + widgets.get(i).getMessage().getString() + " / " + widgets.get(j).getMessage().getString());
                }
            }
        }
        // The scrollbar sits 8 pixels right of the rows and is 6 wide; the title ends at y=13
        if (list.getRowRight() + 14 > screen.width || list.getRowLeft() < 0) {
            problems.add("rows or scrollbar off screen");
        }
        if (tabBar(screen).getRectangle().top() < 14) {
            problems.add("tabs cover the title");
        }
        return problems;
    }

    private static @Nullable String overlapWithOthers(Screen screen, AbstractWidget widget) {
        for (GuiEventListener child : screen.children()) {
            if (child != widget && child instanceof AbstractWidget other && other.visible && overlap(widget, other)) {
                return "overlaps " + other.getClass().getSimpleName() + " \"" + other.getMessage().getString() + "\"";
            }
        }
        return widget.getX() < 0 || widget.getRight() > screen.width ? "off screen" : null;
    }

    private static boolean overlap(AbstractWidget a, AbstractWidget b) {
        return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
    }

    // A real click at the widget's centre, routed through the screen as the mouse would be
    private static void clickThrough(Screen screen, AbstractWidget widget) {
        MouseButtonEvent event = new MouseButtonEvent(widget.getX() + widget.getWidth() / 2.0, widget.getY() + widget.getHeight() / 2.0,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    private static boolean saved(Path file, String line) {
        try {
            return Files.readAllLines(file).contains(line);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void screenshot(Minecraft mc, String name) {
        SelfTest.onRenderThread(mc, () -> Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1, message -> { }));
    }

    private static <T> @Nullable T find(@Nullable Screen screen, Class<T> type) {
        return screen == null ? null : screen.children().stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static TabNavigationBar tabBar(Screen screen) {
        return find(screen, TabNavigationBar.class);
    }

    private static SettingsList list(Screen screen) {
        return find(screen, SettingsList.class);
    }

    private static List<SettingsList.SettingRow> settingRows(SettingsList list) {
        return list.rows().stream().filter(SettingsList.SettingRow.class::isInstance).map(SettingsList.SettingRow.class::cast).toList();
    }

    private static SettingsList.SettingRow settingRow(SettingsList list, String key) {
        return settingRows(list).stream().filter(row -> row.setting().key().equals(key)).findFirst()
            .orElseThrow(() -> new AssertionError("no row for " + key));
    }

    private static <T> List<T> widgets(SettingsList list, Class<T> type) {
        return list.rows().stream().flatMap(row -> row.children().stream()).filter(type::isInstance).map(type::cast).toList();
    }

    private static Button button(SettingsList list, String label) {
        return widgets(list, Button.class).stream().filter(b -> b.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError("no " + label + " button"));
    }
}
