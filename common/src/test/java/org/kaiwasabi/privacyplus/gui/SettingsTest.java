package org.kaiwasabi.privacyplus.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig.CookieGuard;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig.TransferGuard;
import org.kaiwasabi.privacyplus.gui.Settings.Category;
import org.kaiwasabi.privacyplus.gui.Settings.Choice;
import org.kaiwasabi.privacyplus.gui.Settings.Setting;
import org.kaiwasabi.privacyplus.gui.Settings.Toggle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettingsTest {
    private final Map<Field, Object> saved = new HashMap<>();

    @BeforeEach
    void remember() throws IllegalAccessException {
        for (Field field : configFields()) {
            this.saved.put(field, field.get(null));
        }
    }

    @AfterEach
    void restore() throws IllegalAccessException {
        for (Map.Entry<Field, Object> entry : this.saved.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    // The public static fields are the settings; anything else (the file path) isn't
    private static List<Field> configFields() {
        return Arrays.stream(PrivacyPlusConfig.class.getDeclaredFields())
            .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()))
            .toList();
    }

    @Test
    void everyConfigFieldHasExactlyOneSetting() {
        Set<String> fields = configFields().stream().map(Field::getName).collect(Collectors.toSet());
        List<String> keys = Settings.ALL.stream().map(Setting::key).toList();
        assertEquals(keys.size(), new HashSet<>(keys).size(), "no setting appears twice: " + keys);
        assertEquals(fields, new HashSet<>(keys), "the screen shows every PrivacyPlusConfig field, and nothing else");
    }

    @Test
    void everyTabHasSettingsAndEverySettingIsExplained() {
        for (Category category : Category.values()) {
            assertFalse(Settings.in(category).isEmpty(), category + " has settings");
            assertTrue(category.intro.endsWith("."), category + " intro is a sentence");
        }
        Set<String> labels = new HashSet<>();
        for (Setting setting : Settings.ALL) {
            assertTrue(labels.add(setting.label()), "label used once: " + setting.label());
            assertTrue(setting.tooltip().length() > 40 && setting.tooltip().endsWith("."), "tooltip explains " + setting.key());
            assertFalse(setting.label().endsWith("."), "labels aren't sentences: " + setting.label());
        }
    }

    // At the smallest GUI (320 pixels wide) a row has this much room left of its control
    @Test
    void labelsFitTheNarrowestScreen() {
        int room = SettingsList.rowWidth(320) - 4 - SettingsList.CONTROL_WIDTH - 6;
        for (Setting setting : Settings.ALL) {
            int width = textWidth(setting.label());
            assertTrue(width <= room, setting.label() + " is " + width + " wide, room for " + room);
        }
        for (Category category : Category.values()) {
            // MenuTabBar gives each of 5 tabs (320 - 28) / 5 pixels
            assertTrue(textWidth(category.label) <= 56, category.label + " fits its tab");
        }
    }

    @Test
    void togglesChangeTheirOwnField() throws IllegalAccessException {
        for (Setting setting : Settings.ALL) {
            if (setting.control() instanceof Toggle toggle) {
                Field field = field(setting.key());
                boolean before = field.getBoolean(null);
                assertEquals(before, toggle.get().getAsBoolean(), setting.key() + " reads its field");
                toggle.set().accept(!before);
                assertEquals(!before, field.getBoolean(null), setting.key() + " writes its field");
                assertEquals(!before, toggle.get().getAsBoolean(), setting.key() + " reads the new value");
                toggle.set().accept(before);
            }
        }
    }

    @Test
    void choicesChangeTheirOwnField() throws IllegalAccessException {
        for (Setting setting : Settings.ALL) {
            if (setting.control() instanceof Choice<?> choice) {
                checkChoice(setting.key(), choice);
            }
        }
    }

    private static <T> void checkChoice(String key, Choice<T> choice) throws IllegalAccessException {
        Field field = field(key);
        List<T> values = choice.values().get();
        assertTrue(values.contains(choice.get().get()), key + " offers its current value");
        for (T value : values) {
            choice.set().accept(value);
            assertEquals(value, field.get(null), key + " writes " + value);
            assertEquals(value, choice.get().get(), key + " reads " + value);
            assertFalse(choice.name().apply(value).isBlank(), key + " names " + value);
        }
    }

    @Test
    void friendlyNames() {
        assertEquals(List.of("Ask first", "Allow", "Block"), Arrays.stream(TransferGuard.values()).map(Settings::transferName).toList());
        assertEquals(List.of("Same site only", "Never", "Always"), Arrays.stream(CookieGuard.values()).map(Settings::cookieName).toList());
        assertEquals("Never", Settings.logDaysName(0));
        assertEquals("After 1 day", Settings.logDaysName(1));
        assertEquals("After 30 days", Settings.logDaysName(30));
    }

    @Test
    void logDaysOfferWhateverTheFileSays() {
        assertEquals(List.of(0, 7, 30, 90), Settings.logDayChoices(30));
        assertEquals(List.of(0, 7, 14, 30, 90), Settings.logDayChoices(14), "a hand-edited value stays selectable");
        PrivacyPlusConfig.deleteLogsAfterDays = 365;
        Choice<?> choice = (Choice<?>) setting("deleteLogsAfterDays").control();
        assertEquals(List.of(0, 7, 30, 90, 365), choice.values().get());
    }

    @Test
    void incognitoAndDnsUseTheirSwitches() {
        Toggle incognito = (Toggle) setting("incognito").control();
        incognito.set().accept(true);
        assertTrue(PrivacyPlusConfig.incognito);
        assertTrue(setting("privateDns").control() instanceof Settings.PrivateDnsChoice);
        assertNotEquals(setting("privateDns").category(), Category.PRIVACY, "private DNS is a network setting");
    }

    private static Setting setting(String key) {
        return Settings.ALL.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    private static Field field(String key) {
        try {
            return PrivacyPlusConfig.class.getField(key);
        } catch (NoSuchFieldException e) {
            throw new AssertionError("no field " + key, e);
        }
    }

    // Roughly how wide Minecraft's default font draws text (each glyph plus its 1-pixel gap)
    static int textWidth(String text) {
        int width = 0;
        for (char c : text.toCharArray()) {
            width += switch (c) {
                case 'i', '!', ',', '.', ':', ';', '|', '\'' -> 2;
                case 'l', '`' -> 3;
                case 'I', 't', ' ', '[', ']', '(', ')', '"', '*' -> 4;
                case 'f', 'k', '<', '>', '{', '}' -> 5;
                case '@', '~' -> 7;
                default -> 6;
            };
        }
        return width;
    }
}
