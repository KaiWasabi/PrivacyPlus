package org.kaiwasabi.privacyplus.logs;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

// Replaces what identifies the player in log text: the home folder becomes ~, the computer's user name
// <user>, the signed-in accounts' names and UUIDs <player> and <uuid>, session tokens <token> and public
// IP addresses <ip>. Other names and UUIDs (other players, entities, chat test strings) are left alone.
// Private, loopback and link-local addresses are kept: thousands of homes share 192.168.1.x, so they
// identify nobody, and they are what you need when debugging a LAN world or a local server.
// Immutable and thread-safe; build a new one when the identity changes.
final class LogScrubber {
    static final String HOME = "~";
    static final String USER = "<user>";
    static final String PLAYER = "<player>";
    static final String UUID_MARK = "<uuid>";
    static final String TOKEN = "<token>";
    static final String IP = "<ip>";

    // Words too common in logs to remove wherever they appear. An OS user name like this is kept; an
    // account name like this is only removed with its exact capitalisation.
    private static final Set<String> COMMON_WORDS = Set.of(
        "admin", "administrator", "alex", "all", "and", "app", "build", "client", "data", "debug", "default",
        "dev", "error", "file", "game", "guest", "home", "info", "java", "local", "log", "main", "max",
        "min", "minecraft", "mod", "name", "new", "none", "null", "old", "owner", "player", "public",
        "render", "root", "server", "shared", "steve", "system", "temp", "test", "the", "token", "true",
        "false", "ubuntu", "user", "users", "uuid", "warn", "work");

    // Each rule replaces its group "s" (part of the match, or all of it) when its function gives a
    // placeholder; null means this match is fine as it is. The regex only runs when the cheap check
    // passes: most log lines contain none of these, and a regex that starts with a lookbehind is
    // tried at every position of the line.
    private record Rule(Predicate<Text> worthTrying, Pattern pattern, Function<MatchResult, @Nullable String> placeholder) {
    }

    private static final Rule SESSION_ID = new Rule(t -> t.value.contains("token:"), Pattern.compile(
        "(?<s>\\btoken:(?:[^\\s:]+:[0-9a-fA-F]{32}(?![0-9a-fA-F])|[A-Za-z0-9._\\-]{16,}))"), m -> TOKEN);
    // JSON web tokens (Microsoft and Minecraft access tokens) and other base64 JSON, such as the skin
    // property that carries the profile name and UUID
    private static final Rule JWT = new Rule(t -> t.value.contains("eyJ"), Pattern.compile(
        "(?<![A-Za-z0-9_\\-+/])(?<s>eyJ[A-Za-z0-9_\\-+/]{10,}={0,2}(?:\\.[A-Za-z0-9_\\-+/=]+){0,2})"), m -> TOKEN);
    private static final Rule XBOX_TOKEN = new Rule(t -> t.value.contains("XBL3.0"),
        Pattern.compile("XBL3\\.0 x=(?<s>[^;\\s]+;[^\\s\"',]+)"), m -> TOKEN);
    // --accessToken <x>, accessToken=<x>, "access_token": "<x>", [--xuid, <x>, ...], Bearer <x>
    private static final Rule KEYED_SECRET = new Rule(
        t -> Stream.of("token", "client", "xuid", "bearer").anyMatch(t.folded()::contains),
        Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(?:--?)?(?:(?:access|refresh|id|client|auth|session)[_-]?token|client[_-]?id|xuid|bearer)"
                + "(?![A-Za-z0-9])[\"']?(?:\\s*[:=,]\\s*|\\s+)[\"']?(?<s>[^\\s,;\"'\\])}]{6,})"), m -> TOKEN);
    private static final String OCTET = "(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Rule IPV4 = new Rule(t -> hasDigitDotDigit(t.value), Pattern.compile(
        "(?<![\\w.\\-])(?<s>" + OCTET + "(?:\\." + OCTET + "){3})(?![\\w\\-]|\\.\\d)"),
        m -> isPublic(m.group("s")) ? IP : null);
    // An IPv6 address has "::" or all seven colons
    private static final Rule IPV6 = new Rule(t -> t.value.contains("::") || t.value.chars().filter(c -> c == ':').count() >= 7,
        Pattern.compile("(?<![\\w:.])(?<s>[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?![\\w:])"),
        m -> isPublic(m.group("s")) ? IP : null);

    private final List<Rule> rules = new ArrayList<>();

    LogScrubber(Identity identity) {
        rules.add(SESSION_ID);
        rules.add(JWT);
        rules.add(XBOX_TOKEN);
        rules.add(KEYED_SECRET);
        literals(identity.secrets(), Pattern::quote, false, "(?<![A-Za-z0-9_\\-.])", "(?![A-Za-z0-9_\\-.])", TOKEN);
        literals(identity.homes(), s -> "(?iu:" + Pattern.quote(s) + ")", true, "", "(?![\\p{L}\\p{N}_\\-])", HOME);
        List<String> ids = identity.ids().stream().flatMap(id -> Stream.of(id.toString(), undashed(id))).toList();
        literals(ids, s -> "(?i:" + Pattern.quote(s) + ")", true, "(?<![0-9A-Za-z])", "(?![0-9A-Za-z])", UUID_MARK);
        literals(identity.names(), LogScrubber::namePattern, true, "(?<![A-Za-z0-9_])", "(?![A-Za-z0-9_])", PLAYER);
        String user = identity.userName();
        if (user != null && user.strip().length() >= 3 && !COMMON_WORDS.contains(user.strip().toLowerCase(Locale.ROOT))
            && identity.names().stream().noneMatch(user.strip()::equalsIgnoreCase)) {
            literals(Set.of(user.strip()), Pattern::quote, false, "(?<![\\p{L}\\p{N}_])", "(?![\\p{L}\\p{N}_])", USER);
        }
        rules.add(IPV4);
        rules.add(IPV6);
    }

    // Placeholders in place of everything identifying
    String clean(String text) {
        return apply(text, null);
    }

    // Stars in place of everything identifying, as many bytes (in this charset) as they replace, for
    // rewriting a file in place without moving anything after it
    String mask(String text, Charset charset) {
        return apply(text, charset);
    }

    private String apply(String text, @Nullable Charset maskCharset) {
        Text current = new Text(text);
        for (Rule rule : rules) {
            if (rule.worthTrying().test(current)) {
                current.set(replace(current.value, rule, maskCharset));
            }
        }
        return current.value;
    }

    private static String replace(String text, Rule rule, @Nullable Charset maskCharset) {
        Matcher m = rule.pattern().matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String placeholder = rule.placeholder().apply(m);
            if (placeholder == null) {
                continue;
            }
            int start = m.start("s");
            int end = m.end("s");
            if (out == null) {
                out = new StringBuilder(text.length());
            }
            out.append(text, last, start);
            if (maskCharset == null) {
                out.append(placeholder);
            } else {
                out.repeat('*', text.substring(start, end).getBytes(maskCharset).length);
            }
            last = end;
        }
        return out == null ? text : out.append(text, last, text.length()).toString();
    }

    // One rule matching any of the values, longest first so "Steve2" wins over "Steve". anyCase: some
    // values match in any capitalisation, so the cheap check compares case-folded text.
    private void literals(Collection<String> values, Function<String, String> regex, boolean anyCase, String before, String after,
        String placeholder) {
        if (values.isEmpty()) {
            return;
        }
        List<String> sorted = values.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        String alternatives = sorted.stream().map(regex).collect(Collectors.joining("|"));
        List<String> needles = anyCase ? sorted.stream().map(LogScrubber::fold).toList() : sorted;
        Predicate<Text> worthTrying = anyCase
            ? t -> needles.stream().anyMatch(t.folded()::contains)
            : t -> needles.stream().anyMatch(t.value::contains);
        rules.add(new Rule(worthTrying, Pattern.compile(before + "(?<s>" + alternatives + ")" + after), m -> placeholder));
    }

    // The text being cleaned, with a case-folded copy made only once a rule asks for it
    private static final class Text {
        String value;
        private @Nullable String folded;

        Text(String value) {
            this.value = value;
        }

        String folded() {
            if (folded == null) {
                folded = fold(value);
            }
            return folded;
        }

        void set(String changed) {
            if (!changed.equals(value)) {
                value = changed;
                folded = null;
            }
        }
    }

    // Folds case the way the regex flags (?i) and (?iu) compare characters, code point by code point,
    // so a folded contains() never misses what the regex would match
    static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> out.appendCodePoint(Character.toLowerCase(Character.toUpperCase(c))));
        return out.toString();
    }

    private static boolean hasDigitDotDigit(String text) {
        for (int i = 1; i < text.length() - 1; i++) {
            if (text.charAt(i) == '.' && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    // Account names are matched in any capitalisation ("hey wsb_player"), unless they are a common word
    private static String namePattern(String name) {
        boolean exactCase = name.length() < 3 || COMMON_WORDS.contains(name.toLowerCase(Locale.ROOT));
        return exactCase ? Pattern.quote(name) : "(?i:" + Pattern.quote(name) + ")";
    }

    static String undashed(UUID id) {
        return id.toString().replace("-", "");
    }

    // Public (internet) addresses only. Kept: private, loopback, link-local, carrier-grade NAT,
    // multicast, reserved and documentation ranges, and x.y.z.0, which is usually a version number.
    static boolean isPublic(String literal) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int a0 = b[0] & 0xFF;
            int a1 = b[1] & 0xFF;
            int a2 = b[2] & 0xFF;
            int a3 = b[3] & 0xFF;
            return !(a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224 || a3 == 0
                || a0 == 100 && (a1 & 0xC0) == 64
                || a0 == 169 && a1 == 254
                || a0 == 172 && (a1 & 0xF0) == 16
                || a0 == 192 && a1 == 168
                || a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2)
                || a0 == 198 && (a1 == 18 || a1 == 19)
                || a0 == 198 && a1 == 51 && a2 == 100
                || a0 == 203 && a1 == 0 && a2 == 113);
        }
        if (address instanceof Inet6Address) {
            // Global unicast is 2000::/3; 2001:db8::/32 is for documentation
            boolean documentation = (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8;
            return (b[0] & 0xE0) == 0x20 && !documentation;
        }
        return false;
    }
}
