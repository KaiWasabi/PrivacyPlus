package org.kaiwasabi.privacyplus.stealth;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// A server can point its resource pack at any web address, and Minecraft sends that site your
// username and UUID along with the request. For packs hosted by the server itself nothing changes
// (it already knows who you are, and stripping would only make PrivacyPlus noticeable); for packs on
// another site the identity headers are dropped and the player is asked first.
public final class PackHostGuard {
    private static final Set<String> IDENTITY_HEADERS = Set.of("X-Minecraft-Username", "X-Minecraft-UUID");
    // Second-level labels under country codes that behave like a TLD (example.co.uk, example.com.au)
    private static final Set<String> COUNTRY_SLDS = Set.of("co", "com", "net", "org", "ac", "gov", "edu", "or", "ne", "go");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    // Written on the render thread when a pack is pushed, read on the download thread
    private static final Set<String> THIRD_PARTY_URLS = ConcurrentHashMap.newKeySet();
    // Hosts to mention on the currently open pack prompt (render thread only)
    private static final Set<String> PROMPT_HOSTS = new LinkedHashSet<>();

    private PackHostGuard() {
    }

    // Called for every pack a server pushes. Returns whether it is hosted on another site.
    public static boolean noteServerPack(String serverAddress, URL url) {
        boolean thirdParty = !sameSite(ServerAddress.parseString(serverAddress).getHost(), url.getHost());
        if (thirdParty) {
            THIRD_PARTY_URLS.add(url.toString());
        }
        return thirdParty;
    }

    public static boolean isThirdParty(URL url) {
        return THIRD_PARTY_URLS.contains(url.toString());
    }

    public static Map<String, String> headersFor(URL url, Map<String, String> headers) {
        if (!PrivacyPlusConfig.hidePackIdentity || !isThirdParty(url)) {
            return headers;
        }
        Map<String, String> filtered = new LinkedHashMap<>(headers);
        IDENTITY_HEADERS.forEach(filtered::remove);
        return filtered;
    }

    // The vanilla prompt collects several packs into one screen; track which hosts it should name
    public static Set<String> promptHosts(boolean newPrompt, URL url) {
        if (newPrompt) {
            PROMPT_HOSTS.clear();
        }
        if (isThirdParty(url)) {
            PROMPT_HOSTS.add(url.getHost());
        }
        return PROMPT_HOSTS;
    }

    public static Component promptNote(Set<String> hosts) {
        String note = "\n\nDownloaded from " + String.join(", ", hosts) + ", not from this server. That site will see your IP address"
            + (PrivacyPlusConfig.hidePackIdentity ? "; PrivacyPlus won't send it your username." : " and your username.");
        return Component.literal(note).withStyle(ChatFormatting.YELLOW);
    }

    public static boolean sameSite(@Nullable String serverHost, @Nullable String packHost) {
        if (serverHost == null || packHost == null) {
            return false;
        }
        String a = normalize(serverHost);
        String b = normalize(packHost);
        if (a.equals(b)) {
            return true;
        }
        if (isIpLiteral(a) || isIpLiteral(b)) {
            return false;
        }
        return site(a).equals(site(b));
    }

    // Shared hosts, tunnels and dynamic-DNS names where every subdomain belongs to a different person, so
    // "same site" must look one label deeper (a.aternos.me and b.aternos.me are unrelated servers)
    private static final List<String> SHARED_HOSTS = List.of(
        "aternos.me", "minehut.gg", "exaroton.me", "falixsrv.me", "falix.gg", "feathermc.gg", "e4mc.link", "joinmc.link",
        "ply.gg", "playit.gg", "ngrok.io", "ngrok.app", "ngrok-free.app", "trycloudflare.com", "serveminecraft.net",
        "duckdns.org", "ddns.net", "no-ip.org", "no-ip.biz", "hopto.org", "zapto.org", "sytes.net", "servegame.com", "myddns.me",
        "dynu.net", "freeddns.org", "github.io", "pages.dev", "netlify.app", "vercel.app", "web.app", "firebaseapp.com");

    // Approximates the registrable domain: play.example.com -> example.com, mc.example.co.uk -> example.co.uk
    public static String site(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : SHARED_HOSTS) {
            if (lower.endsWith("." + suffix)) {
                // Each customer's subdomain is its own site, run by someone else
                String[] labels = host.split("\\.");
                int keep = suffix.split("\\.").length + 1;
                return String.join(".", Arrays.copyOfRange(labels, Math.max(0, labels.length - keep), labels.length));
            }
        }
        String[] labels = host.split("\\.");
        int keep = 2;
        if (labels.length >= 3 && labels[labels.length - 1].length() == 2 && COUNTRY_SLDS.contains(labels[labels.length - 2])) {
            keep = 3;
        }
        if (labels.length <= keep) {
            return host;
        }
        return String.join(".", Arrays.copyOfRange(labels, labels.length - keep, labels.length));
    }

    private static String normalize(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1);
        }
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || IPV4.matcher(host).matches();
    }
}
