package org.kaiwasabi.privacyplus.dns;

import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// Looks up server addresses with encrypted DNS (DNS-over-HTTPS, RFC 8484) when privateDns is set, so the
// router, internet provider or Wi-Fi can't see which servers you look up. It replaces only the two DNS
// steps of vanilla's server lookup (the address, then the _minecraft._tcp SRV record) and returns what
// vanilla would, down to the host name the join handshake sends, so servers can't tell. Nothing falls
// back to the computer's DNS: if the service can't be reached, the lookup fails.
public final class PrivateDns {
    public record Provider(String setting, String label, URI endpoint) {
    }

    // IP addresses rather than names, so reaching the service doesn't need a lookup of its own
    public static final List<Provider> BUILT_IN = List.of(
        new Provider("cloudflare", "Cloudflare", URI.create("https://1.1.1.1/dns-query")),
        new Provider("quad9", "Quad9", URI.create("https://9.9.9.9/dns-query")));
    // Names that only mean something on this computer or network; the system resolver handles them
    private static final List<String> LOCAL_SUFFIXES = List.of(".local", ".localhost", ".lan", ".home.arpa", ".internal");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long MAX_CACHE_SECONDS = 300;
    private static final long EMPTY_CACHE_SECONDS = 60;
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(8000L);

    interface Transport {
        byte[] exchange(URI endpoint, byte[] query) throws IOException;
    }

    private record Cached(List<DnsMessage.Record> records, long expiresAt) {
    }

    static Transport transport = PrivateDns::post;
    static Consumer<String> onFailure = PrivateDns::toast;
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static volatile @Nullable HttpClient client;
    private static volatile @Nullable String lastCustom;

    private PrivateDns() {
    }

    // "off", a built-in service, or the address of another DNS-over-HTTPS service; null means off.
    // Throws IllegalArgumentException with a readable message for anything else.
    public static @Nullable Provider parse(String setting) {
        String value = setting.trim();
        if (value.isEmpty() || value.equalsIgnoreCase("off")) {
            return null;
        }
        for (Provider provider : BUILT_IN) {
            if (provider.setting().equalsIgnoreCase(value)) {
                return provider;
            }
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("\"" + value + "\" isn't a web address");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("\"" + value + "\" isn't a web address");
        }
        // Plain http only to this computer (a local DNS proxy); anywhere else the lookups would travel unencrypted
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        if (!https && !("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(host))) {
            throw new IllegalArgumentException("Use an https:// address");
        }
        return new Provider(value, host, uri);
    }

    public static @Nullable Provider current() {
        return parse(PrivacyPlusConfig.privateDns);
    }

    // The settings the multiplayer-screen switch cycles through: off, the built-in services, and the
    // last custom service set this session
    public static String next(String setting) {
        Provider provider = parse(setting);
        if (provider != null && !BUILT_IN.contains(provider)) {
            lastCustom = provider.setting();
        }
        List<String> options = new ArrayList<>(List.of("off"));
        BUILT_IN.forEach(p -> options.add(p.setting()));
        if (lastCustom != null) {
            options.add(lastCustom);
        }
        String now = provider == null ? "off" : provider.setting();
        return options.get((options.indexOf(now) + 1) % options.size());
    }

    public static String describe() {
        Provider provider = current();
        return provider == null
            ? "Private DNS is off: servers are looked up with your computer's normal DNS."
            : "Servers are looked up with encrypted DNS at " + provider.label() + " (" + provider.endpoint() + ").";
    }

    // Whether a lookup of host goes here instead of to vanilla's resolver
    public static boolean handles(String host) {
        return current() != null && (InetAddresses.isInetAddress(host) || !isLocalName(host));
    }

    // What ServerAddressResolver.SYSTEM returns, looked up with private DNS
    public static Optional<ResolvedServerAddress> resolve(ServerAddress address) {
        String host = address.getHost();
        try {
            byte[] ip = InetAddresses.isInetAddress(host) ? InetAddresses.forString(host).getAddress() : lookupAddress(host);
            if (ip == null) {
                return Optional.empty();
            }
            // Named after the host as written, like InetAddress.getByName does: the join handshake sends this
            // name, and for an address without one Java would ask the system DNS for it in reverse
            InetAddress resolved = InetAddress.getByAddress(host, ip);
            return Optional.of(ResolvedServerAddress.from(new InetSocketAddress(resolved, address.getPort())));
        } catch (IllegalArgumentException e) {
            // Not a valid host name: vanilla can't look it up either
            return Optional.empty();
        } catch (IOException e) {
            fail(host, e);
            return Optional.empty();
        }
    }

    // What vanilla's SRV redirect handler returns, looked up with private DNS
    public static Optional<ServerAddress> lookupRedirect(ServerAddress address) {
        String host = address.getHost();
        if (address.getPort() != 25565 || InetAddresses.isInetAddress(host)) {
            return Optional.empty();
        }
        try {
            List<DnsMessage.Record> found = lookup("_minecraft._tcp." + IDN.toASCII(DnsMessage.stripDot(host)), DnsMessage.SRV);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            // Like vanilla: the first record, its target written with the trailing dot
            DnsMessage.Record srv = found.getFirst();
            return Optional.of(new ServerAddress(srv.target() + ".", srv.port()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        } catch (IOException e) {
            fail(host, e);
            return Optional.empty();
        }
    }

    static boolean isLocalName(String host) {
        String name = DnsMessage.stripDot(host).toLowerCase(Locale.ROOT);
        return !name.contains(".") || LOCAL_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    static void clearCache() {
        CACHE.clear();
    }

    // IPv4 first, as Java prefers by default
    private static byte @Nullable [] lookupAddress(String host) throws IOException {
        String name = IDN.toASCII(DnsMessage.stripDot(host));
        for (int type : new int[] {DnsMessage.A, DnsMessage.AAAA}) {
            List<DnsMessage.Record> found = lookup(name, type);
            if (!found.isEmpty()) {
                return found.getFirst().address();
            }
        }
        return null;
    }

    private static List<DnsMessage.Record> lookup(String name, int type) throws IOException {
        Provider provider = current();
        if (provider == null) {
            throw new IOException("Private DNS was switched off during the lookup");
        }
        String key = provider.endpoint() + " " + type + " " + name.toLowerCase(Locale.ROOT);
        long now = System.nanoTime();
        Cached cached = CACHE.get(key);
        if (cached != null && now - cached.expiresAt() < 0) {
            return cached.records();
        }
        DnsMessage.Response response = DnsMessage.parse(transport.exchange(provider.endpoint(), DnsMessage.query(name, type)), name, type);
        if (response.rcode() != DnsMessage.NOERROR && response.rcode() != DnsMessage.NXDOMAIN) {
            throw new IOException("The DNS service answered with error " + response.rcode());
        }
        List<DnsMessage.Record> found = response.find(name, type);
        long seconds = found.isEmpty() ? EMPTY_CACHE_SECONDS : Math.min(response.ttl(), MAX_CACHE_SECONDS);
        CACHE.put(key, new Cached(found, now + Duration.ofSeconds(seconds).toNanos()));
        return found;
    }

    private static byte[] post(URI endpoint, byte[] query) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/dns-message")
            .header("Accept", "application/dns-message")
            .POST(HttpRequest.BodyPublishers.ofByteArray(query))
            .build();
        try {
            HttpResponse<byte[]> response = client().send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IOException("The DNS service answered HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private static HttpClient client() {
        HttpClient http = client;
        if (http == null) {
            synchronized (PrivateDns.class) {
                http = client;
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
                    client = http;
                }
            }
        }
        return http;
    }

    private static boolean isLoopback(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (InetAddresses.isInetAddress(bare)) {
            return InetAddresses.forString(bare).isLoopbackAddress();
        }
        return bare.equalsIgnoreCase("localhost");
    }

    // The server name stays out of the log, which incognito keeps free of the servers you join
    private static void fail(String host, IOException e) {
        PrivacyPlus.LOG.warn("Private DNS lookup failed: {}", e.toString());
        Provider provider = current();
        onFailure.accept("No answer from " + (provider == null ? "the DNS service" : provider.label()) + " for " + host
            + ". Your normal DNS wasn't used instead.");
    }

    private static void toast(String message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST,
            Component.literal("PrivacyPlus: private DNS"), Component.literal(message)));
    }
}
