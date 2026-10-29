package org.kaiwasabi.privacyplus.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.locale.DeprecatedTranslationsInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.KeybindResolver;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;

class ProbeShieldTest {
    // What the game's language looks like with a mod installed: vanilla keys plus the mod's own
    private static final Map<String, String> MOD_KEYS = Map.of(
        "mymod.secret", "Secret Mod Text",
        "key.mymod.open", "Open Mod Menu",
        "mymod.greeting", "Hello %s"
    );

    @BeforeEach
    void installModdedLanguage() {
        Language vanilla = Language.DEFAULT_INSTANCE;
        Language.inject(new Language() {
            @Override
            public String getOrDefault(String key, String defaultValue) {
                return MOD_KEYS.getOrDefault(key, vanilla.getOrDefault(key, defaultValue));
            }

            @Override
            public boolean has(String key) {
                return MOD_KEYS.containsKey(key) || vanilla.has(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return vanilla.getVisualOrder(text);
            }
        });
        // What KeyMapping.createNameSupplier does in the real client for a name with no key mapping
        KeybindResolver.setKeyResolver(name -> () -> Component.translatable(name));
    }

    @AfterEach
    void restore() {
        Language.inject(Language.DEFAULT_INSTANCE);
        KeybindResolver.setKeyResolver(name -> () -> Component.literal(name));
        PrivacyPlusConfig.blockTranslationProbes = true;
        PrivacyPlusConfig.spoofLanguage = true;
        ServerPackLanguage.use(null);
    }

    // A client whose game language is German
    private static void playInGerman() {
        Map<String, String> german = Map.of("gui.done", "Fertig", "key.jump", "Springen", "chat.type.text", "<%s> %s");
        Language english = Language.DEFAULT_INSTANCE;
        Language.inject(new Language() {
            @Override
            public String getOrDefault(String key, String defaultValue) {
                return german.getOrDefault(key, english.getOrDefault(key, defaultValue));
            }

            @Override
            public boolean has(String key) {
                return german.containsKey(key) || english.has(key);
            }

            @Override
            public boolean isDefaultRightToLeft() {
                return false;
            }

            @Override
            public FormattedCharSequence getVisualOrder(FormattedText text) {
                return english.getVisualOrder(text);
            }
        });
    }

    @Test
    void spoofedLanguageAnswersProbesInEnglish() {
        playInGerman();
        assertEquals("Fertig", Component.translatable("gui.done").getString(), "sanity: the game itself is German");
        assertEquals("Done", ProbeShield.resolve(Component.translatable("gui.done")));
        assertEquals("Jump", ProbeShield.resolve(Component.keybind("key.jump")));
        assertEquals("<Steve> Done", ProbeShield.resolve(Component.translatable("chat.type.text", "Steve", Component.translatable("gui.done"))));
    }

    @Test
    void realLanguageWhenNotSpoofing() {
        playInGerman();
        PrivacyPlusConfig.spoofLanguage = false;
        assertEquals("Fertig", ProbeShield.resolve(Component.translatable("gui.done")));
        assertEquals("Springen", ProbeShield.resolve(Component.keybind("key.jump")));
        assertEquals("mymod.secret", ProbeShield.resolve(Component.translatable("mymod.secret")), "mod keys are still hidden");
    }

    @Test
    void vanillaKeysStillTranslate() {
        assertEquals("Done", ProbeShield.resolve(Component.translatable("gui.done")));
    }

    @Test
    void modKeyComesBackRaw() {
        Component probe = Component.translatable("mymod.secret");
        assertEquals("Secret Mod Text", probe.getString(), "sanity: the mod key does resolve normally");
        assertEquals("mymod.secret", ProbeShield.resolve(probe));
    }

    @Test
    void modKeyUsesFallbackLikeVanilla() {
        assertEquals("fallback", ProbeShield.resolve(Component.translatableWithFallback("mymod.secret", "fallback")));
        assertEquals("Hi Bob", ProbeShield.resolve(Component.translatableWithFallback("mymod.greeting", "Hi %s", Component.literal("Bob"))));
    }

    @Test
    void modKeyNestedInVanillaArgs() {
        Component probe = Component.translatable("chat.type.text", Component.literal("Steve"), Component.translatable("mymod.secret"));
        assertEquals("<Steve> mymod.secret", ProbeShield.resolve(probe));
    }

    @Test
    void modKeyInSiblings() {
        Component probe = Component.literal("a ").append(Component.translatable("mymod.secret")).append(Component.translatable("gui.done"));
        assertEquals("a mymod.secretDone", ProbeShield.resolve(probe));
    }

    @Test
    void modKeybindComesBackAsItsName() {
        Component probe = Component.keybind("key.mymod.open");
        assertEquals("Open Mod Menu", probe.getString(), "sanity: the mod's keybind name resolves normally");
        assertEquals("key.mymod.open", ProbeShield.resolve(probe));
    }

    @Test
    void vanillaKeybindUnchanged() {
        Component probe = Component.keybind("key.jump");
        assertEquals(probe.getString(), ProbeShield.resolve(probe));
    }

    @Test
    void disabledMeansPlainGetString() {
        PrivacyPlusConfig.blockTranslationProbes = false;
        assertEquals("Secret Mod Text", ProbeShield.resolve(Component.translatable("mymod.secret")));
    }

    // vanilla's en_us.json as shipped, before the deprecated-key fixups (which apply to the merged result)
    private static Map<String, String> vanillaEnglishFile() {
        Map<String, String> entries = new HashMap<>();
        try (InputStream stream = Language.class.getResourceAsStream("/assets/minecraft/lang/en_us.json")) {
            Language.loadFromJson(stream, entries::put);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return entries;
    }

    // The server pushed a pack with these English and German lang files. What a vanilla client merges, in
    // its order: en_us from vanilla then the server, then de_de (vanilla's own German isn't needed here).
    private static void serverPack(Map<String, String> english, Map<String, String> german) {
        DeprecatedTranslationsInfo deprecated = DeprecatedTranslationsInfo.loadFromDefaultResource();
        Map<String, String> vanilla = vanillaEnglishFile();
        ServerPackLanguage.use(new ServerPackLanguage.Translations(
            ServerPackLanguage.merge(List.of(vanilla, english), deprecated),
            ServerPackLanguage.merge(List.of(vanilla, english, german), deprecated)));
    }

    private static final Map<String, String> SERVER_ENGLISH = Map.of(
        "server.welcome", "Welcome %s",
        "server.english_only", "Only in English",
        "gui.cancel", "Server Cancel",
        "key.server.emote", "Emote"
    );
    private static final Map<String, String> SERVER_GERMAN = Map.of("server.welcome", "Willkommen %s", "server.german_only", "Nur deutsch");

    @Test
    void serverPackKeysResolveLikeVanillaInEnglish() {
        playInGerman();
        serverPack(SERVER_ENGLISH, SERVER_GERMAN);
        assertEquals("Welcome Steve", ProbeShield.resolve(Component.translatable("server.welcome", "Steve")),
            "a vanilla en_us client uses the pack's English, never its German");
        assertEquals("Server Cancel", ProbeShield.resolve(Component.translatable("gui.cancel")), "the pack overrides a vanilla key");
        assertEquals("Done", ProbeShield.resolve(Component.translatable("gui.done")));
        assertEquals("server.german_only", ProbeShield.resolve(Component.translatable("server.german_only")),
            "en_us clients don't load the pack's German file");
        assertEquals("Emote", ProbeShield.resolve(Component.keybind("key.server.emote")), "no such key mapping: shown as a translation");
        assertEquals("mymod.secret", ProbeShield.resolve(Component.translatable("mymod.secret")), "mod keys stay hidden");
    }

    @Test
    void serverPackKeysResolveLikeVanillaInGameLanguage() {
        playInGerman();
        PrivacyPlusConfig.spoofLanguage = false;
        serverPack(SERVER_ENGLISH, SERVER_GERMAN);
        assertEquals("Willkommen Steve", ProbeShield.resolve(Component.translatable("server.welcome", "Steve")));
        assertEquals("Only in English", ProbeShield.resolve(Component.translatable("server.english_only")),
            "falls back to the pack's English");
        assertEquals("Nur deutsch", ProbeShield.resolve(Component.translatable("server.german_only")));
        assertEquals("Fertig", ProbeShield.resolve(Component.translatable("gui.done")));
        assertEquals("mymod.secret", ProbeShield.resolve(Component.translatable("mymod.secret")));
    }

    @Test
    void serverPackKeysAreNotProbes() {
        serverPack(SERVER_ENGLISH, SERVER_GERMAN);
        assertTrue(ProbeShield.probeKeys(Component.translatable("server.welcome", "x")).isEmpty());
        assertTrue(ProbeShield.probeKeys(Component.translatable("server.german_only")).isEmpty());
        assertEquals(List.of("unknown key mymod.secret"), ProbeShield.probeKeys(Component.translatable("mymod.secret")));
    }

    @Test
    void leavingTheServerForgetsItsKeys() {
        serverPack(SERVER_ENGLISH, SERVER_GERMAN);
        ServerPackLanguage.forget();
        assertEquals("server.welcome", ProbeShield.resolve(Component.translatable("server.welcome")));
        assertEquals("Cancel", ProbeShield.resolve(Component.translatable("gui.cancel")));
        assertEquals(List.of("unknown key server.welcome"), ProbeShield.probeKeys(Component.translatable("server.welcome")));
    }

    @Test
    void vanillaFixesUpDeprecatedKeysAfterTheServerPack() {
        // Removed keys stay removed even when a pack defines them; renamed ones move after the merge
        String renamedFrom = "item.minecraft.dune_armor_trim_smithing_template.new";
        String renamedTo = "item.minecraft.dune_armor_trim_smithing_template";
        Map<String, String> merged = ServerPackLanguage.merge(List.of(vanillaEnglishFile(),
            Map.of("block.minecraft.grass", "Server Grass", renamedFrom, "Server Dune", renamedTo, "Overwritten")),
            DeprecatedTranslationsInfo.loadFromDefaultResource());
        assertFalse(merged.containsKey("block.minecraft.grass"));
        assertFalse(merged.containsKey(renamedFrom));
        assertEquals("Server Dune", merged.get(renamedTo));
    }
}
