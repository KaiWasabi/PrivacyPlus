package org.kaiwasabi.privacyplus.gui;

import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig.CookieGuard;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig.TransferGuard;

// Every PrivacyPlusConfig setting as the settings screen shows it: its tab, label, tooltip, and how to read
// and change it. Free of widgets, so a test can check that no setting is missing. Changes save right away.
public final class Settings {
    public enum Category {
        PRIVACY("Privacy", "What Microsoft, servers and your own computer get to know about you."),
        STEALTH("Stealth", "Keep servers from finding out that you use mods."),
        SERVERS("Servers", "What servers, and the network between you and them, get to see."),
        CHAT("Chat", "Protect what you type, click and copy in chat."),
        LOGS("Logs", "Keep your name, accounts and chat out of the files Minecraft saves.");

        public final String label;
        public final String intro;

        Category(String label, String intro) {
            this.label = label;
            this.intro = intro;
        }
    }

    // How a setting changes: on/off, one of a few values, or the private DNS service (which has its own
    // switch order and a box for other services, so the screen builds that one itself)
    public sealed interface Control permits Toggle, Choice, PrivateDnsChoice {
    }

    public record Toggle(BooleanSupplier get, Consumer<Boolean> set) implements Control {
    }

    public record Choice<T>(Supplier<List<T>> values, Function<T, String> name, Supplier<T> get, Consumer<T> set) implements Control {
    }

    public record PrivateDnsChoice() implements Control {
    }

    // key: the PrivacyPlusConfig field (and properties key) the setting changes
    public record Setting(String key, Category category, String label, String tooltip, Control control) {
    }

    private static final String NEXT_JOIN = " Applies the next time you join a server.";

    public static final List<Setting> ALL = List.of(
        toggle("blockTelemetry", Category.PRIVACY, "Block telemetry",
            "Drop the usage reports (telemetry) Minecraft sends to Microsoft instead of sending them. "
                + "The title screen counts how many were blocked.",
            () -> PrivacyPlusConfig.blockTelemetry, andSave(v -> PrivacyPlusConfig.blockTelemetry = v)),
        toggle("incognito", Category.PRIVACY, "Incognito mode",
            "Leave no local traces: chat isn't written to the log files, commands aren't saved to command_history.txt, "
                + "the direct-connect address isn't remembered and the log doesn't record which server you join. "
                + "Files written before you switch it on stay as they are.",
            () -> PrivacyPlusConfig.incognito, PrivacyPlusConfig::setIncognito),
        toggle("spoofLanguage", Category.PRIVACY, "Hide your language",
            "Tell servers your game language is US English, the most common one, so it can't single you out. "
                + "Text a server makes the game echo back (signs, anvils) is answered in English too. "
                + "Server plugin messages will be in English." + NEXT_JOIN,
            () -> PrivacyPlusConfig.spoofLanguage, andSave(v -> PrivacyPlusConfig.spoofLanguage = v)),

        toggle("spoofClientBrand", Category.STEALTH, "Hide mod loader name",
            "Tell servers this is plain vanilla Minecraft instead of \"fabric\" or \"forge\"." + NEXT_JOIN,
            () -> PrivacyPlusConfig.spoofClientBrand, andSave(v -> PrivacyPlusConfig.spoofClientBrand = v)),
        toggle("hideModChannels", Category.STEALTH, "Hide mod channels",
            "Don't tell servers about mod network channels or answer their mod handshakes, so they can't spot "
                + "Fabric API, Forge or other mods. Turn off for mods that need to talk to the server (voice chat, "
                + "minimap server features) or to join modded servers (this also hides Forge when joining). "
                + "Your own single-player worlds aren't affected. Takes effect right away.",
            () -> PrivacyPlusConfig.hideModChannels, andSave(v -> PrivacyPlusConfig.hideModChannels = v)),
        toggle("blockTranslationProbes", Category.STEALTH, "Block translation probes",
            "Servers can detect mods by making the game translate a mod's text on a sign or anvil and reading "
                + "the answer. When on, only Minecraft's own texts and the server's own resource-pack texts are "
                + "translated there, so your mods stay hidden.",
            () -> PrivacyPlusConfig.blockTranslationProbes, andSave(v -> PrivacyPlusConfig.blockTranslationProbes = v)),
        toggle("stripForgeHandshakeMarker", Category.STEALTH, "Hide Forge when joining",
            "Forge only: remove the marker Forge adds to the server address when joining, which tells servers "
                + "you use Forge. Turn off to join modded Forge servers, which need it." + NEXT_JOIN,
            () -> PrivacyPlusConfig.stripForgeHandshakeMarker, andSave(v -> PrivacyPlusConfig.stripForgeHandshakeMarker = v)),
        toggle("probeAlarm", Category.STEALTH, "Probe alarm",
            "Tell you quietly, in a corner popup, when a server tries to fingerprint or track you: sign or anvil "
                + "probes for mods and keybinds, resource packs from other sites, cookies and transfers. "
                + "Purely local: nothing is sent or saved.",
            () -> PrivacyPlusConfig.probeAlarm, andSave(v -> PrivacyPlusConfig.probeAlarm = v)),

        new Setting("privateDns", Category.SERVERS, "Private DNS",
            "Look up servers with encrypted DNS (DNS-over-HTTPS), so your router, internet provider or Wi-Fi "
                + "can't see which servers you look up. Covers joining and server-list pings; resource packs and "
                + "Mojang's services still use your normal DNS. If the service doesn't answer, the server won't "
                + "connect: your normal DNS is never used instead.",
            new PrivateDnsChoice()),
        toggle("pingServersOnlyOnRequest", Category.SERVERS, "Ping servers only on request",
            "Don't ping every server in your multiplayer list when it opens: each ping shows that server your "
                + "IP address. Servers are pinged when you select one or press Refresh.",
            () -> PrivacyPlusConfig.pingServersOnlyOnRequest, andSave(v -> PrivacyPlusConfig.pingServersOnlyOnRequest = v)),
        choice("transferGuard", Category.SERVERS, "Server transfers",
            "What happens when a server sends you to another server. Ask first: you decide each time. "
                + "Allow: go along, like vanilla. Block: stay where you are. While you decide, you arrive later "
                + "than a vanilla player would, which two servers working together could notice.",
            () -> List.of(TransferGuard.values()), Settings::transferName,
            () -> PrivacyPlusConfig.transferGuard, andSave(v -> PrivacyPlusConfig.transferGuard = v)),
        choice("cookieGuard", Category.SERVERS, "Pass cookies on transfer",
            "Cookies are small notes a server stores on your game. Vanilla hands them to the next server when "
                + "you're transferred, so servers can recognise you across each other. Same site only: pass them "
                + "only within one site (play.example.com to lobby.example.com). Never: pass none. Always: like vanilla. "
                + "Two servers working together could notice the missing cookie.",
            () -> List.of(CookieGuard.values()), Settings::cookieName,
            () -> PrivacyPlusConfig.cookieGuard, andSave(v -> PrivacyPlusConfig.cookieGuard = v)),
        toggle("hidePackIdentity", Category.SERVERS, "Hide your name from pack sites",
            "Minecraft sends your username and UUID to the site a server's resource pack is downloaded from. "
                + "Leave them out when that site isn't the server's own.",
            () -> PrivacyPlusConfig.hidePackIdentity, andSave(v -> PrivacyPlusConfig.hidePackIdentity = v)),
        toggle("askBeforeThirdPartyPacks", Category.SERVERS, "Confirm packs from other sites",
            "Always ask before downloading a server resource pack hosted on another site (which sees your IP "
                + "address), even on servers whose packs you've accepted before.",
            () -> PrivacyPlusConfig.askBeforeThirdPartyPacks, andSave(v -> PrivacyPlusConfig.askBeforeThirdPartyPacks = v)),

        toggle("commandSuggestionsOnTab", Category.CHAT, "Command suggestions on Tab",
            "Plugin commands (and some vanilla ones) send what you type to the server on every keystroke to get "
                + "suggestions, so the server sees commands and private messages you start but never send. "
                + "When on, the server is only asked when you press Tab.",
            () -> PrivacyPlusConfig.commandSuggestionsOnTab, andSave(v -> PrivacyPlusConfig.commandSuggestionsOnTab = v)),
        toggle("linkGuard", Category.CHAT, "Check links before opening",
            "Before opening a link from chat, a book or a sign, show where it really goes, and warn about IP-logger "
                + "sites, link shorteners, bare IP addresses and lookalike characters. Risky links always ask first, "
                + "even with Minecraft's own link prompt turned off.",
            () -> PrivacyPlusConfig.linkGuard, andSave(v -> PrivacyPlusConfig.linkGuard = v)),
        toggle("clipboardGuard", Category.CHAT, "Ask before clipboard changes",
            "Ask before a server copies text to your clipboard (clicking chat text can do that without telling you).",
            () -> PrivacyPlusConfig.clipboardGuard, andSave(v -> PrivacyPlusConfig.clipboardGuard = v)),

        toggle("cleanLogs", Category.LOGS, "Remove identity from logs",
            "Remove your identity from log files and crash reports: your computer's user name and home folder, "
                + "Minecraft account names and UUIDs, session tokens and IP addresses. Old log files are cleaned at startup.",
            () -> PrivacyPlusConfig.cleanLogs, andSave(v -> PrivacyPlusConfig.cleanLogs = v)),
        toggle("removeChatFromOldLogs", Category.LOGS, "Remove chat from old logs",
            "Also remove chat lines from old log files. The current session's chat stays in latest.log unless "
                + "incognito mode is on.",
            () -> PrivacyPlusConfig.removeChatFromOldLogs, andSave(v -> PrivacyPlusConfig.removeChatFromOldLogs = v)),
        choice("deleteLogsAfterDays", Category.LOGS, "Delete old logs",
            "Delete old log files after this many days. Never keeps them forever, like vanilla.",
            () -> logDayChoices(PrivacyPlusConfig.deleteLogsAfterDays), Settings::logDaysName,
            () -> PrivacyPlusConfig.deleteLogsAfterDays, andSave(v -> PrivacyPlusConfig.deleteLogsAfterDays = v)));

    private Settings() {
    }

    public static List<Setting> in(Category category) {
        return ALL.stream().filter(setting -> setting.category() == category).toList();
    }

    static String transferName(TransferGuard guard) {
        return switch (guard) {
            case ASK -> "Ask first";
            case ALLOW -> "Allow";
            case BLOCK -> "Block";
        };
    }

    static String cookieName(CookieGuard guard) {
        return switch (guard) {
            case SAME_SITE -> "Same site only";
            case NEVER -> "Never";
            case ALLOW -> "Always";
        };
    }

    // Never, a week, a month, three months, plus whatever the file says if that's something else
    static List<Integer> logDayChoices(int current) {
        TreeSet<Integer> days = new TreeSet<>(List.of(0, 7, 30, 90));
        days.add(current);
        return List.copyOf(days);
    }

    static String logDaysName(int days) {
        return days <= 0 ? "Never" : days == 1 ? "After 1 day" : "After " + days + " days";
    }

    private static Setting toggle(String key, Category category, String label, String tooltip, BooleanSupplier get, Consumer<Boolean> set) {
        return new Setting(key, category, label, tooltip, new Toggle(get, set));
    }

    private static <T> Setting choice(String key, Category category, String label, String tooltip, Supplier<List<T>> values,
                                      Function<T, String> name, Supplier<T> get, Consumer<T> set) {
        return new Setting(key, category, label, tooltip, new Choice<>(values, name, get, set));
    }

    private static <T> Consumer<T> andSave(Consumer<T> assign) {
        return value -> {
            assign.accept(value);
            PrivacyPlusConfig.save();
        };
    }
}
