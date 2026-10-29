package org.kaiwasabi.privacyplus.stealth;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.locale.DeprecatedTranslationsInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.KeybindContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// Servers fingerprint clients by sending text containing translation or keybind keys (on a sign, or as
// an anvil item name) and reading back what the client turned it into. Two things leak that way:
//  - installed mods: only keys a vanilla client has are translated (blockTranslationProbes): vanilla's own
//    en_us.json, plus whatever the server's own resource packs add (see ServerPackLanguage)
//  - the real game language: with spoofLanguage, keys come back in US English, matching the en_us
//    reported in the client information
public final class ProbeShield {
    // Unguessable, so no language file or resource pack can define it: translating it always
    // falls through to the fallback, which is what vanilla does for a key it doesn't know.
    private static final String MISSING_KEY = PrivacyPlus.MOD_ID + "." + UUID.randomUUID();
    private static final Object[] NO_ARGS = new Object[0];
    private static volatile Map<String, String> vanillaEnglish;

    private ProbeShield() {
    }

    // Drop-in replacement for component.getString() on text that will be sent back to the server.
    public static String resolve(Component component) {
        return PrivacyPlusConfig.blockTranslationProbes || PrivacyPlusConfig.spoofLanguage
            ? sanitize(component).getString()
            : component.getString();
    }

    static MutableComponent sanitize(Component component) {
        ComponentContents contents = component.getContents();
        MutableComponent copy;
        if (contents instanceof KeybindContents keybind && PrivacyPlusConfig.spoofLanguage && isVanillaKey(keybind.getName())) {
            // The bound key's name ("Space", "Leertaste") is itself translated, so translate it in English
            copy = Component.empty().append(sanitize(boundKeyName(keybind.getName())));
        } else {
            copy = MutableComponent.create(sanitizeContents(contents));
        }
        copy.setStyle(component.getStyle());
        for (Component sibling : component.getSiblings()) {
            copy.append(sanitize(sibling));
        }
        return copy;
    }

    private static ComponentContents sanitizeContents(ComponentContents contents) {
        if (contents instanceof TranslatableContents translatable) {
            Object[] args = sanitizeArgs(translatable.getArgs());
            String key = translatable.getKey();
            if (PrivacyPlusConfig.spoofLanguage) {
                String english = english(key);
                if (english != null) {
                    return new TranslatableContents(MISSING_KEY, english, args);
                }
            } else if (isVanillaKey(key)) {
                return new TranslatableContents(key, translatable.getFallback(), args);
            } else {
                String local = serverPackLocal(key);
                if (local != null) {
                    return new TranslatableContents(MISSING_KEY, local, args);
                }
            }
            if (PrivacyPlusConfig.blockTranslationProbes) {
                // Vanilla formats the fallback (or, without one, the key itself) with the args
                String template = translatable.getFallback() != null ? translatable.getFallback() : key;
                return new TranslatableContents(MISSING_KEY, template, args);
            }
            return new TranslatableContents(key, translatable.getFallback(), args);
        }
        if (contents instanceof KeybindContents keybind && PrivacyPlusConfig.blockTranslationProbes && !isVanillaKey(keybind.getName())) {
            // Vanilla has no key mapping by that name, so it shows a translation of the name: from the
            // server's packs if they define it, otherwise the name itself
            String name = keybind.getName();
            String translated = PrivacyPlusConfig.spoofLanguage ? english(name) : serverPackLocal(name);
            return new TranslatableContents(MISSING_KEY, translated != null ? translated : name, NO_ARGS);
        }
        return contents;
    }

    private static Object[] sanitizeArgs(Object[] args) {
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = args[i] instanceof Component component ? sanitize(component) : args[i];
        }
        return result;
    }

    // What in this text only makes sense as a probe: translation keys a vanilla client doesn't have (mods,
    // your own resource packs; keys from the server's packs are fine) and keybinds (they reveal key
    // bindings and mod key mappings)
    public static List<String> probeKeys(Component component) {
        List<String> found = new ArrayList<>();
        collectProbeKeys(component, found);
        return found;
    }

    private static void collectProbeKeys(Component component, List<String> found) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (!isVanillaKey(translatable.getKey()) && !isServerPackKey(translatable.getKey())) {
                found.add("unknown key " + translatable.getKey());
            }
            for (Object arg : translatable.getArgs()) {
                if (arg instanceof Component nested) {
                    collectProbeKeys(nested, found);
                }
            }
        } else if (component.getContents() instanceof KeybindContents keybind) {
            found.add("keybind " + keybind.getName());
        }
        for (Component sibling : component.getSiblings()) {
            collectProbeKeys(sibling, found);
        }
    }

    // Same lookup a keybind component does: the key it's bound to, or a translation of its name if unbound
    private static Component boundKeyName(String keyMapping) {
        try {
            return KeyMapping.createNameSupplier(keyMapping).get();
        } catch (RuntimeException | LinkageError e) {
            return Component.translatable(keyMapping);
        }
    }

    // Vanilla key mappings are named after vanilla translation keys, so this covers keybinds too.
    static boolean isVanillaKey(String key) {
        return vanillaEnglish().containsKey(key);
    }

    // What a vanilla client set to en_us shows for the key, or null if it doesn't know it
    private static @Nullable String english(String key) {
        ServerPackLanguage.Translations server = ServerPackLanguage.current();
        return (server != null ? server.english() : vanillaEnglish()).get(key);
    }

    // What a vanilla client in the game's own language shows for a key the server's packs define. Only
    // for keys vanilla doesn't have: vanilla keys already resolve that way through the game's language.
    private static @Nullable String serverPackLocal(String key) {
        ServerPackLanguage.Translations server = ServerPackLanguage.current();
        return server != null ? server.local().get(key) : null;
    }

    private static boolean isServerPackKey(String key) {
        ServerPackLanguage.Translations server = ServerPackLanguage.current();
        return server != null && (server.english().containsKey(key) || server.local().containsKey(key));
    }

    private static Map<String, String> vanillaEnglish() {
        Map<String, String> english = vanillaEnglish;
        if (english == null) {
            // Read the jar's file directly: on Forge, Language.DEFAULT_INSTANCE also gets mod keys added
            Map<String, String> translations = new HashMap<>();
            try (InputStream stream = Language.class.getResourceAsStream("/assets/minecraft/lang/en_us.json")) {
                Language.loadFromJson(stream, translations::put);
            } catch (Exception e) {
                PrivacyPlus.LOG.error("Couldn't read vanilla translations; treating every key as unknown", e);
            }
            DeprecatedTranslationsInfo.loadFromDefaultResource().applyToMap(translations);
            english = vanillaEnglish = Map.copyOf(translations);
        }
        return english;
    }
}
