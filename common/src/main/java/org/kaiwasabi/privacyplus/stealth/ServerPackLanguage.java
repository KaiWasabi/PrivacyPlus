package org.kaiwasabi.privacyplus.stealth;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.locale.DeprecatedTranslationsInfo;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.jspecify.annotations.Nullable;

// A server can send a resource pack with its own translations and then probe with one of its keys: a
// vanilla client translates it from the pack, so ProbeShield must too. This works out the translations a
// vanilla client would have with the same server packs: vanilla's own plus the server's, merged in vanilla's
// order, and nothing from mods or your own resource packs (which a vanilla client wouldn't have).
public final class ServerPackLanguage {
    private static final String ENGLISH = "en_us";

    // english: what a vanilla client set to en_us (the spoofed language) has; local: what one set to the
    // game's own language has (the same map when that is en_us)
    record Translations(Map<String, String> english, Map<String, String> local) {
    }

    // Only set while the loaded resource packs include server-sent ones with translations
    private static volatile @Nullable Translations current;

    private ServerPackLanguage() {
    }

    // After every resource reload, which includes the reloads that apply and remove server packs.
    // languageStack: the languages vanilla loads for the game language, en_us first.
    public static void reload(ResourceManager resources, List<String> languageStack) {
        try {
            current = load(resources, languageStack);
        } catch (RuntimeException e) {
            PrivacyPlus.LOG.error("Couldn't read the server resource packs' translations", e);
            current = null;
        }
    }

    // Leaving a server: its packs are about to be unloaded
    public static void forget() {
        current = null;
    }

    static @Nullable Translations current() {
        return current;
    }

    // For tests
    static void use(@Nullable Translations translations) {
        current = translations;
    }

    private static @Nullable Translations load(ResourceManager resources, List<String> languageStack) {
        List<PackResources> packs = resources.listPacks().filter(pack -> isVanilla(pack) || isFromServer(pack)).toList();
        if (packs.stream().noneMatch(ServerPackLanguage::isFromServer)) {
            return null;
        }
        if (packs.stream().noneMatch(ServerPackLanguage::isVanilla)) {
            // Without vanilla's own translations underneath, vanilla keys would look unknown
            PrivacyPlus.LOG.warn("Vanilla resource pack not found; ignoring the server's translations");
            return null;
        }
        // Vanilla walks the namespaces in the order of a HashMap filled from its packs, bottom to top. Mods
        // add namespaces, which can change that order, so rebuild it from vanilla's and the server's packs.
        Map<String, Boolean> byNamespace = new HashMap<>();
        packs.forEach(pack -> pack.getNamespaces(PackType.CLIENT_RESOURCES).forEach(ns -> byNamespace.putIfAbsent(ns, true)));
        Set<String> namespaces = byNamespace.keySet();
        Set<PackResources> allowed = Set.copyOf(packs);
        if (!hasServerTranslations(resources, namespaces, languageStack)) {
            return null;
        }

        DeprecatedTranslationsInfo deprecated = DeprecatedTranslationsInfo.loadFromDefaultResource();
        // A lang file that isn't valid JSON makes vanilla abandon the whole reload and keep the translations
        // it had before. Mirror that per model: a vanilla en_us client only reads the English files.
        Translations previous = current;
        Set<PackResources> vanillaOnly = Set.copyOf(packs.stream().filter(ServerPackLanguage::isVanilla).toList());
        List<Map<String, String>> englishLayers;
        Map<String, String> english;
        try {
            englishLayers = layers(resources, namespaces, List.of(ENGLISH), allowed);
            english = merge(englishLayers, deprecated);
        } catch (BrokenLangFile e) {
            englishLayers = layers(resources, namespaces, List.of(ENGLISH), vanillaOnly);
            english = previous != null ? previous.english() : merge(englishLayers, deprecated);
        }
        if (languageStack.size() == 1) {
            return new Translations(english, english);
        }
        // The game language's files come after all of English's, so they just go on top
        Map<String, String> local;
        try {
            List<Map<String, String>> localLayers = new ArrayList<>(layers(resources, namespaces, List.of(ENGLISH), allowed));
            localLayers.addAll(layers(resources, namespaces, languageStack.subList(1, languageStack.size()), allowed));
            local = merge(localLayers, deprecated);
        } catch (BrokenLangFile e) {
            if (previous != null) {
                local = previous.local();
            } else {
                List<Map<String, String>> localLayers = new ArrayList<>(layers(resources, namespaces, List.of(ENGLISH), vanillaOnly));
                localLayers.addAll(layers(resources, namespaces, languageStack.subList(1, languageStack.size()), vanillaOnly));
                local = merge(localLayers, deprecated);
            }
        }
        return new Translations(english, local);
    }

    // A server pack's lang file that isn't valid JSON (vanilla only skips files it can't read at all)
    private static final class BrokenLangFile extends RuntimeException {
        BrokenLangFile(Throwable cause) {
            super(cause);
        }
    }

    private static boolean hasServerTranslations(ResourceManager resources, Set<String> namespaces, List<String> languageStack) {
        for (String code : languageStack) {
            for (String namespace : namespaces) {
                if (resources.getResourceStack(langFile(namespace, code)).stream().anyMatch(r -> isFromServer(r.source()))) {
                    return true;
                }
            }
        }
        return false;
    }

    // The lang files ClientLanguage.loadFrom reads, in its order (language, namespace, pack bottom to top),
    // limited to the allowed packs. The resource stack already leaves out files hidden by a pack's filter.
    private static List<Map<String, String>> layers(ResourceManager resources, Set<String> namespaces, List<String> languageStack,
                                                    Set<PackResources> allowed) {
        List<Map<String, String>> layers = new ArrayList<>();
        for (String code : languageStack) {
            for (String namespace : namespaces) {
                for (Resource resource : resources.getResourceStack(langFile(namespace, code))) {
                    if (allowed.contains(resource.source())) {
                        layers.add(read(resource));
                    }
                }
            }
        }
        return layers;
    }

    private static Map<String, String> read(Resource resource) {
        Map<String, String> entries = new HashMap<>();
        try (InputStream stream = resource.open()) {
            Language.loadFromJson(stream, entries::put);
        } catch (IOException e) {
            // Vanilla skips a file it can't read (ClientLanguage.appendFrom catches only IOException)
            PrivacyPlus.LOG.warn("Couldn't read translations from pack {}", resource.sourcePackId(), e);
        } catch (RuntimeException e) {
            throw new BrokenLangFile(e);
        }
        return entries;
    }

    // Later layers win, then vanilla's removed and renamed keys are applied to the whole result, like ClientLanguage
    static Map<String, String> merge(List<Map<String, String>> layers, DeprecatedTranslationsInfo deprecated) {
        Map<String, String> merged = new HashMap<>();
        layers.forEach(merged::putAll);
        deprecated.applyToMap(merged);
        return Map.copyOf(merged);
    }

    private static Identifier langFile(String namespace, String code) {
        return Identifier.fromNamespaceAndPath(namespace, String.format(Locale.ROOT, "lang/%s.json", code));
    }

    private static boolean isVanilla(PackResources pack) {
        PackLocationInfo location = pack.location();
        return location.source() == PackSource.BUILT_IN && location.id().equals("vanilla");
    }

    // Packs a server pushed (or a single-player world's bundled resources.zip, which loads the same way)
    private static boolean isFromServer(PackResources pack) {
        PackSource source = pack.location().source();
        return source == PackSource.SERVER || source == PackSource.WORLD;
    }
}
