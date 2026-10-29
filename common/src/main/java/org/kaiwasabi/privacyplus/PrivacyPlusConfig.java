package org.kaiwasabi.privacyplus;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import org.kaiwasabi.privacyplus.dns.PrivateDns;
import org.jspecify.annotations.Nullable;

// config/privacyplus.properties. Every privacy feature defaults to on, except incognito (which changes
// conveniences) and private DNS (which hands your lookups to a service you choose); the file is
// rewritten on load so options added in newer versions show up with their comments.
public final class PrivacyPlusConfig {
    public static volatile boolean blockTelemetry = true;
    public static volatile boolean spoofClientBrand = true;
    public static volatile boolean stripForgeHandshakeMarker = true;
    public static volatile boolean blockTranslationProbes = true;
    public static volatile boolean pingServersOnlyOnRequest = true;
    public static volatile boolean hidePackIdentity = true;
    public static volatile boolean askBeforeThirdPartyPacks = true;
    public static volatile boolean commandSuggestionsOnTab = true;
    public static volatile boolean spoofLanguage = true;
    public static volatile boolean probeAlarm = true;
    public static volatile boolean incognito = false;
    // "off", "cloudflare", "quad9" or a DNS-over-HTTPS address; see PrivateDns
    public static volatile String privateDns = "off";
    public static volatile boolean linkGuard = true;
    public static volatile boolean clipboardGuard = true;
    public static volatile TransferGuard transferGuard = TransferGuard.ASK;
    public static volatile CookieGuard cookieGuard = CookieGuard.SAME_SITE;
    public static volatile boolean cleanLogs = true;
    public static volatile boolean removeChatFromOldLogs = false;
    // 0 keeps them forever, like vanilla
    public static volatile int deleteLogsAfterDays = 0;
    public static volatile boolean hideModChannels = true;

    // What happens when a server sends you to another server
    public enum TransferGuard { ASK, ALLOW, BLOCK }

    // Which cookies (small notes a server stores on your client) go along when a server sends you elsewhere
    public enum CookieGuard { SAME_SITE, NEVER, ALLOW }

    private static @Nullable Path file;

    private PrivacyPlusConfig() {
    }

    static void load(Path configDir) {
        file = configDir.resolve(PrivacyPlus.MOD_ID + ".properties");
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                PrivacyPlus.LOG.error("Couldn't read {}, using defaults", file, e);
            }
        }

        blockTelemetry = read(props, "blockTelemetry", blockTelemetry);
        spoofClientBrand = read(props, "spoofClientBrand", spoofClientBrand);
        stripForgeHandshakeMarker = read(props, "stripForgeHandshakeMarker", stripForgeHandshakeMarker);
        blockTranslationProbes = read(props, "blockTranslationProbes", blockTranslationProbes);
        pingServersOnlyOnRequest = read(props, "pingServersOnlyOnRequest", pingServersOnlyOnRequest);
        hidePackIdentity = read(props, "hidePackIdentity", hidePackIdentity);
        askBeforeThirdPartyPacks = read(props, "askBeforeThirdPartyPacks", askBeforeThirdPartyPacks);
        commandSuggestionsOnTab = read(props, "commandSuggestionsOnTab", commandSuggestionsOnTab);
        spoofLanguage = read(props, "spoofLanguage", spoofLanguage);
        probeAlarm = read(props, "probeAlarm", probeAlarm);
        incognito = read(props, "incognito", incognito);
        linkGuard = read(props, "linkGuard", linkGuard);
        clipboardGuard = read(props, "clipboardGuard", clipboardGuard);
        transferGuard = readEnum(props, "transferGuard", transferGuard);
        cookieGuard = readEnum(props, "cookieGuard", cookieGuard);
        cleanLogs = read(props, "cleanLogs", cleanLogs);
        removeChatFromOldLogs = read(props, "removeChatFromOldLogs", removeChatFromOldLogs);
        deleteLogsAfterDays = readInt(props, "deleteLogsAfterDays", deleteLogsAfterDays, 0, 3650);
        hideModChannels = read(props, "hideModChannels", hideModChannels);
        try {
            PrivateDns.Provider provider = PrivateDns.parse(props.getProperty("privateDns", privateDns));
            privateDns = provider == null ? "off" : provider.setting();
        } catch (IllegalArgumentException e) {
            PrivacyPlus.LOG.error("privateDns in {}: {}. Using off", file, e.getMessage());
            privateDns = "off";
        }
        save();
    }

    // Incognito can be switched in game (title screen, /pp incognito), so it is saved right away
    public static void setIncognito(boolean value) {
        incognito = value;
        save();
    }

    // Also switched in game (multiplayer screen, /pp dns). Throws IllegalArgumentException for a
    // setting PrivateDns can't use.
    public static void setPrivateDns(String value) {
        PrivateDns.Provider provider = PrivateDns.parse(value);
        privateDns = provider == null ? "off" : provider.setting();
        save();
    }

    // Writes every setting; call after changing fields (the settings screen does)
    public static void save() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write("""
                    # PrivacyPlus settings. Restart the game after editing (incognito and privateDns also switch in game).

                    # Drop all telemetry events instead of sending them to Microsoft.
                    blockTelemetry=%s

                    # Tell servers this is a vanilla client instead of "fabric" or "forge".
                    spoofClientBrand=%s

                    # Forge only: remove the FORGE marker Forge adds to the server address when connecting.
                    # Set to false to join modded Forge servers, which need the marker to recognise Forge clients.
                    stripForgeHandshakeMarker=%s

                    # Only translate text a server asks the client to echo back (sign editor, anvil) using
                    # vanilla's own language keys (and the server's own resource-pack texts, as a vanilla client would), so
                    # servers can't detect mods by probing for their keys.
                    blockTranslationProbes=%s

                    # Don't ping every server in the multiplayer list when it opens (each ping shows the
                    # server your IP). Servers are pinged when selected or when you press Refresh.
                    pingServersOnlyOnRequest=%s

                    # Minecraft sends your username and UUID to whatever site a server's resource pack is
                    # downloaded from. Leave them out when that site isn't the server's own.
                    hidePackIdentity=%s

                    # Always ask before downloading a server resource pack hosted on another site (which
                    # sees your IP address), even on servers whose packs you've accepted before.
                    askBeforeThirdPartyPacks=%s

                    # Plugin commands (and some vanilla ones) send what you type to the server on every
                    # keystroke to get suggestions, so the server sees commands and private messages you
                    # start but never send. Only ask the server when you press Tab.
                    commandSuggestionsOnTab=%s

                    # Tell servers your game language is en_us (US English), the most common one. Text a server
                    # makes the client echo back (signs, anvils) is answered in English too, so the real
                    # language doesn't leak that way either. Plugin messages will then be in English.
                    spoofLanguage=%s

                    # Tell you (quietly, in a corner popup) when a server tries to fingerprint or track you: sign or
                    # anvil probes for mods and keybinds, resource packs from other sites, cookies, transfers.
                    # /pp probes lists everything noticed this session. Nothing is sent or saved.
                    probeAlarm=%s

                    # Leave no local traces: chat isn't written to the log files, commands aren't saved to
                    # command_history.txt, the direct-connect address isn't remembered, and the log doesn't
                    # record which server you connect to. Files written before switching it on stay as they are.
                    incognito=%s

                    # Look up servers with encrypted DNS (DNS-over-HTTPS) instead of your computer's normal DNS, so
                    # your router, internet provider or Wi-Fi can't see which servers you look up. off, cloudflare,
                    # quad9, or the https:// address of another DNS-over-HTTPS service (its own name is looked up
                    # with your normal DNS). Covers joining and server-list pings; resource-pack downloads and
                    # Mojang's services still use your normal DNS. If the service doesn't answer, the server doesn't
                    # connect: your normal DNS isn't used instead. Also switches on the multiplayer screen.
                    privateDns=%s

                    # Before opening a link from chat, a book or a sign, show where it really goes, and warn about
                    # IP-logger sites, link shorteners, bare IP addresses and lookalike characters. Risky links always
                    # ask first, even with Minecraft's own link prompt turned off.
                    linkGuard=%s

                    # Ask before a server copies text to your clipboard (clicking chat text can do that silently).
                    clipboardGuard=%s

                    # When a server sends you to another server: ASK first, ALLOW (vanilla) or BLOCK. With ASK you arrive
                    # later than a vanilla player would, which two servers working together could notice.
                    transferGuard=%s

                    # Cookies are notes a server stores on your client; vanilla hands them to the next server when you're
                    # transferred, so servers can recognise you across each other. SAME_SITE only passes them within the
                    # same site (play.example.com -> lobby.example.com), NEVER passes none, ALLOW is vanilla. Two servers
                    # working together could notice the missing cookie.
                    cookieGuard=%s

                    # Remove your identity from log files and crash reports: your computer's user name and home folder,
                    # Minecraft account names and UUIDs, session tokens and IP addresses. Old log files are cleaned at startup.
                    cleanLogs=%s

                    # Also remove chat lines from old log files (the current session's chat stays in latest.log unless
                    # incognito is on).
                    removeChatFromOldLogs=%s

                    # Delete old log files after this many days. 0 keeps them forever, like vanilla.
                    deleteLogsAfterDays=%s

                    # Don't tell servers about mod network channels or answer their mod handshakes, so servers can't detect
                    # Fabric API, Forge or other mods. Turn off for mods that need to talk to the server (voice chat,
                    # minimap server features) or to join modded servers (while on, Forge's join marker is removed too).
                    # Your own single-player worlds aren't affected.
                    hideModChannels=%s
                    """.formatted(blockTelemetry, spoofClientBrand, stripForgeHandshakeMarker,
                        blockTranslationProbes, pingServersOnlyOnRequest, hidePackIdentity, askBeforeThirdPartyPacks,
                        commandSuggestionsOnTab, spoofLanguage, probeAlarm, incognito, privateDns, linkGuard, clipboardGuard,
                        transferGuard, cookieGuard, cleanLogs, removeChatFromOldLogs, deleteLogsAfterDays, hideModChannels));
            }
        } catch (IOException e) {
            PrivacyPlus.LOG.error("Couldn't write {}", file, e);
        }
    }

    private static boolean read(Properties props, String key, boolean defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private static <E extends Enum<E>> E readEnum(Properties props, String key, E defaultValue) {
        String value = props.getProperty(key);
        try {
            return value == null ? defaultValue : Enum.valueOf(defaultValue.getDeclaringClass(), value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    private static int readInt(Properties props, String key, int defaultValue, int min, int max) {
        String value = props.getProperty(key);
        try {
            return value == null ? defaultValue : Math.clamp(Integer.parseInt(value.trim()), min, max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
