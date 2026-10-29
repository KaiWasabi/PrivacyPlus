package org.kaiwasabi.privacyplus.guard;

import java.io.ByteArrayOutputStream;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.kaiwasabi.privacyplus.stealth.PackHostGuard;
import org.jspecify.annotations.Nullable;

// A server can put any address behind chat text, a book page or a dialog button, and the text shown can
// say anything. Before a link opens, this works out where it really goes and what about it is risky: IP
// loggers (opening one hands your IP address, and so your rough location, to whoever made the link),
// shorteners that hide the destination, bare IP addresses, unencrypted http, lookalike letters, decoy
// names before an @ and unusual ports. Pure: no Minecraft state, so it is unit-tested.
public final class LinkGuard {
    public enum Kind { IP_LOGGER, SHORTENER, IP_ADDRESS, NOT_HTTPS, LOOKALIKE, USER_INFO, PORT, UNREADABLE }

    public record Warning(Kind kind, String text) {
    }

    // address: the link exactly as it will be opened. siteStart/siteEnd: where the site name (the part that
    // says who runs it) sits in address, for emphasis; both 0 when it can't be pointed at. host is the
    // name the browser will actually contact (decoded, lower case, letters as they will be shown).
    public record Analysis(String address, String host, String site, int siteStart, int siteEnd, List<Warning> warnings) {
        public boolean risky() {
            return !this.warnings.isEmpty();
        }

        public boolean has(Kind kind) {
            return this.warnings.stream().anyMatch(w -> w.kind() == kind);
        }
    }

    // Sites whose links exist to record who opens them. Grabify and IPLogger let their users pick from many
    // innocent-looking (and lookalike) domains; request catchers and canary tokens log visitors the same way.
    private static final Set<String> IP_LOGGERS = Set.of(
        // Grabify and its alternate domains
        "grabify.link", "grabify.org", "grabify.icu", "urlto.me", "snifferip.com", "bmwforum.co", "leancoding.co", "spottyfly.com",
        "stopify.co", "yoütu.be", "rëddït.com", "spötify.com", "yoütübe.co", "yoütübe.com", "shört.co", "discörd.com",
        "disçordapp.com",
        "minecräft.com", "särahah.eu", "särahah.pl", "starbucksiswrong.com", "starbucksisbadforyou.com", "bucks.as",
        "freegiftcards.co", "xda-developers.us", "xda-developers.io", "quickmessage.us", "quickmessage.io", "fortnight.space",
        "fortnitechat.site", "youshouldclick.us", "joinmy.site", "crabrave.pw", "catsnthing.com", "catsnthings.fun",
        "lovebird.guru", "trulove.guru", "dateing.club", "otherhalf.life", "shrekis.life", "datasig.io", "datauth.io",
        "headshot.monster", "gaming-at-my.best", "progaming.monster", "yourmy.monster", "screenshare.host", "imageshare.best",
        "screenshot.best", "gamingfun.me", "curiouscat.club", "mymassive.yachts", "stonks.boats", "stonks.fun", "toes.beauty",
        "barefoot.pics", "shareit.pics", "gamer.tattoo", "shipment.website", "imagevault.cloud", "sugma.mom", "yum.mom",
        "plz.life", "massive.mom", "massive.boats", "mymassive.store", "mymassive.top", "gamer.hair", "photovault.pics",
        "bathtub.pics", "foot.wiki", "thisdomainislong.lol", "gamergirl.pro", "picshost.pics", "pichost.pics", "imghost.pics",
        "imagehost.pics", "toldyouso.lol", "toldyouso.pics", "screenshare.pics", "myprivate.pics", "noodshare.pics",
        "cheapcinema.club", "watches-my.stream", "stock-images.0o.si",
        // IPLogger and its alternate domains
        "iplogger.org", "iplogger.com", "iplogger.ru", "iplogger.co", "iplogger.info", "iplogger.cn", "iplog.co", "2no.co",
        "yip.su", "iplis.ru", "iplo.ru", "ezstat.ru", "02ip.ru", "ipgrabber.ru", "ipgraber.ru", "maper.info", "mapper.info",
        "wl.gl", "ed.tc", "bc.ax",
        // Other IP loggers
        "blasze.com", "blasze.tk", "ps3cfw.com", "ikwyd.com", "ip-tracker.org", "ip-puller.com", "ip-grabber.com",
        "ipsnatcher.com", "myiptest.com", "ip-trap.com", "ythingy.com", "linkify.me", "bitly-bot.com", "adfly-bot.online",
        "skypegrab.net", "fortnite-stats.site", "minecraft-skins.xyz", "discordcrypt.xyz", "gyazo.nl", "yȯutube.com",
        "fuglekos.com",
        // Request catchers and canary tokens: they record every visitor
        "canarytokens.com", "webhook.site", "requestbin.net", "requestcatcher.com", "pipedream.net", "hookbin.com",
        "beeceptor.com", "interact.sh", "oast.fun", "oast.live", "oast.me", "oast.online", "oast.pro", "oast.site",
        "burpcollaborator.net", "oastify.com"
    );

    // Link shorteners: the address says nothing about where it ends up. First-party short domains that can
    // only lead to their own site (youtu.be, aka.ms, discord.gg, redd.it, amzn.to) are deliberately absent.
    private static final Set<String> SHORTENERS = Set.of(
        "bit.ly", "bitly.com", "bit.do", "j.mp", "tinyurl.com", "tiny.one", "rotf.lol", "tiny.cc", "t.co", "is.gd", "v.gd",
        "cutt.ly", "cutt.us", "rb.gy", "ow.ly", "buff.ly", "goo.gl", "shorturl.at", "t.ly", "rebrand.ly", "bl.ink", "short.gy",
        "s.id", "qrco.de", "lnkd.in", "adf.ly", "adfoc.us", "sh.st", "shorte.st", "ouo.io", "ouo.press", "bc.vc", "clck.ru",
        "gg.gg", "dub.sh", "kutt.it", "snip.ly", "surl.li", "urlz.fr", "x.gd", "soo.gd", "cur.lv", "tr.im", "u.to", "ift.tt",
        "dlvr.it", "trib.al", "shrtco.de", "9qr.de", "dsc.gg", "spoo.me", "tny.im", "chilp.it", "shor.by", "goo.su", "vk.cc",
        "u.nu", "t2m.io", "y2u.be", "linkd.in", "shorturl.gg", "tinu.be", "lstu.fr", "biturl.io", "tldr.ly", "wzurl.me",
        "zzb.bz", "slsh.us", "7.ly", "5.gp"
    );

    // Browsers read these as dots in a host name
    private static final String DOT_LOOKALIKES = "\u3002\uFF0E\uFF61";

    private LinkGuard() {
    }

    public static Analysis analyze(URI uri) {
        String address = uri.toString();
        List<Warning> warnings = new ArrayList<>();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http")) {
            warnings.add(new Warning(Kind.NOT_HTTPS, "Not encrypted (http, not https): anyone on the network in between can see "
                + "the page and change it."));
        } else if (!scheme.equals("https")) {
            warnings.add(new Warning(Kind.NOT_HTTPS, "Not a normal web link (" + (scheme.isEmpty() ? "no" : scheme + ":") + " address)."));
        }

        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty()) {
            warnings.add(new Warning(Kind.UNREADABLE, "The address has no clear site name, so the browser has to guess where it goes."));
            return new Analysis(address, "", "", 0, 0, warnings);
        }
        int authorityStart = address.indexOf(authority, scheme.length());

        int at = authority.lastIndexOf('@');
        int hostStart = at + 1;
        int hostEnd = authority.length();
        @Nullable String port = null;
        if (authority.startsWith("[", hostStart)) {
            int close = authority.indexOf(']', hostStart);
            hostEnd = close < 0 ? authority.length() : close + 1;
            if (hostEnd < authority.length() && authority.charAt(hostEnd) == ':') {
                port = authority.substring(hostEnd + 1);
            }
        } else {
            int colon = authority.indexOf(':', hostStart);
            if (colon >= 0) {
                hostEnd = colon;
                port = authority.substring(colon + 1);
            }
        }
        String rawHost = authority.substring(hostStart, hostEnd);
        String host = normalizeHost(rawHost);
        boolean bracketed = rawHost.startsWith("[");
        @Nullable String ipv4 = bracketed ? null : ipv4(host);
        boolean ip = bracketed || ipv4 != null;
        String site = ip || host.isEmpty() ? host : PackHostGuard.site(host);

        if (host.isEmpty()) {
            warnings.add(new Warning(Kind.UNREADABLE, "The address has no site name, so the browser has to guess where it goes."));
        }
        if (at >= 0) {
            String decoy = percentDecode(authority.substring(0, at));
            warnings.add(new Warning(Kind.USER_INFO, "\"" + shorten(decoy, 60) + "\" before the @ is a decoy: browsers skip it (or send it "
                + "as a login name). The link really goes to " + (host.isEmpty() ? "an unnamed site" : host) + "."));
        }

        @Nullable String logger = listed(IP_LOGGERS, host);
        if (logger != null) {
            warnings.add(new Warning(Kind.IP_LOGGER, logger + " is a known IP logger: opening the link records your IP address (and so "
                + "roughly where you live) for whoever made it."));
        }
        @Nullable String shortener = listed(SHORTENERS, host);
        if (shortener != null) {
            warnings.add(new Warning(Kind.SHORTENER, "Goes through the link shortener " + shortener + ", which hides where you end up "
                + "(and can record that you clicked)."));
        }

        if (bracketed) {
            warnings.add(new Warning(Kind.IP_ADDRESS, "Goes to a bare IP address (" + host + ") instead of a named site: often someone's "
                + "own computer or a throwaway server."));
        } else if (ipv4 != null) {
            warnings.add(new Warning(Kind.IP_ADDRESS, ipv4.equals(host)
                ? "Goes to a bare IP address (" + host + ") instead of a named site: often someone's own computer or a throwaway server."
                : "Goes to an IP address written in a disguised form: " + rawHost + " is really " + ipv4 + "."));
        } else if (host.equals("localhost") || host.endsWith(".localhost")) {
            warnings.add(new Warning(Kind.IP_ADDRESS, "Goes to your own computer (" + host + "), not to a website."));
        }

        if (!ip) {
            lookalike(rawHost, host).ifPresent(warnings::add);
        }

        if (port != null && !port.isEmpty()) {
            boolean usual = (scheme.equals("https") && port.equals("443")) || (scheme.equals("http") && port.equals("80"));
            if (!usual) {
                warnings.add(new Warning(Kind.PORT, "Uses an unusual port (:" + shorten(port, 12) + "), as home-made and temporary servers "
                    + "often do."));
            }
        }

        // Emphasis covers the labels of the raw host that make up the site, when the two line up
        int siteStart = 0;
        int siteEnd = 0;
        if (authorityStart >= 0 && !site.isEmpty() && !bracketed) {
            String trimmed = rawHost.endsWith(".") ? rawHost.substring(0, rawHost.length() - 1) : rawHost;
            String[] rawLabels = trimmed.split("\\.", -1);
            int siteLabels = site.split("\\.", -1).length;
            if (rawLabels.length == host.split("\\.", -1).length && siteLabels <= rawLabels.length) {
                int offset = 0;
                for (int i = 0; i < rawLabels.length - siteLabels; i++) {
                    offset += rawLabels[i].length() + 1;
                }
                siteStart = authorityStart + hostStart + offset;
                siteEnd = authorityStart + hostStart + trimmed.length();
            } else {
                siteStart = authorityStart + hostStart;
                siteEnd = authorityStart + hostEnd;
            }
        }
        return new Analysis(address, host, site, siteStart, siteEnd, List.copyOf(warnings));
    }

    // The name a browser contacts: percent-escapes decoded, Unicode compatibility forms folded (fullwidth
    // letters, odd dots), lower case, punycode labels shown as the letters they stand for
    static String normalizeHost(String rawHost) {
        String host = percentDecode(rawHost);
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1).toLowerCase(Locale.ROOT);
        }
        host = Normalizer.normalize(host, Normalizer.Form.NFKC);
        for (char dot : DOT_LOOKALIKES.toCharArray()) {
            host = host.replace(dot, '.');
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        for (String label : host.split("\\.", -1)) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(label.startsWith("xn--") ? unicodeLabel(label) : label);
        }
        return out.toString();
    }

    private static String unicodeLabel(String label) {
        try {
            return IDN.toUnicode(label, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return label;
        }
    }

    // Internationalized names can borrow letters that look like Latin ones from other alphabets
    // (Cyrillic "а" for "a"), or add accents that are easy to miss
    private static Optional<Warning> lookalike(String rawHost, String host) {
        boolean encoded = rawHost.toLowerCase(Locale.ROOT).contains("xn--");
        boolean special = !host.chars().allMatch(c -> c < 0x80) || encoded;
        if (!special) {
            return Optional.empty();
        }
        String ascii = asciiForm(host);
        String real = ascii.equals(host) ? "" : " Underneath, the name is " + ascii + ".";
        for (String label : host.split("\\.")) {
            Set<Character.UnicodeScript> scripts = scripts(label);
            if (scripts.size() > 1 && !cjkMix(scripts)) {
                return Optional.of(new Warning(Kind.LOOKALIKE, "The site name mixes letters from different alphabets ("
                    + describe(scripts) + "), a classic trick to imitate a familiar name." + real));
            }
        }
        return Optional.of(new Warning(Kind.LOOKALIKE, "The site name uses special or non-English letters, which can imitate a "
            + "familiar name: check it letter by letter." + real));
    }

    private static Set<Character.UnicodeScript> scripts(String label) {
        Set<Character.UnicodeScript> scripts = new LinkedHashSet<>();
        label.codePoints().forEach(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED
                && script != Character.UnicodeScript.UNKNOWN) {
                scripts.add(script);
            }
        });
        return scripts;
    }

    // Japanese, Chinese and Korean names legitimately combine these
    private static boolean cjkMix(Set<Character.UnicodeScript> scripts) {
        Set<Character.UnicodeScript> cjk = Set.of(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO);
        return cjk.containsAll(scripts);
    }

    private static String describe(Set<Character.UnicodeScript> scripts) {
        List<String> names = new ArrayList<>();
        for (Character.UnicodeScript script : scripts) {
            String name = script.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            names.add(Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
        return String.join(" and ", names);
    }

    private static String asciiForm(String host) {
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return host;
        }
    }

    // The listed domain the host is, or is under (sub.example.com matches example.com, not the other way)
    private static @Nullable String listed(Set<String> domains, String host) {
        String candidate = host;
        while (true) {
            if (domains.contains(candidate)) {
                return candidate;
            }
            int dot = candidate.indexOf('.');
            if (dot < 0) {
                return null;
            }
            candidate = candidate.substring(dot + 1);
        }
    }

    // Browsers read a host whose last label is a number as an IPv4 address, in any of several notations:
    // 2130706433, 0x7f.1, 0177.0.0.1 are all 127.0.0.1. Returns it in dotted form, or null if host isn't one.
    static @Nullable String ipv4(String host) {
        List<String> parts = new ArrayList<>(List.of(host.split("\\.", -1)));
        if (parts.size() > 1 && parts.getLast().isEmpty()) {
            parts.removeLast();
        }
        if (parts.isEmpty() || !numeric(parts.getLast()) || parts.size() > 4) {
            return null;
        }
        long[] values = new long[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            Long value = ipv4Number(parts.get(i));
            if (value == null) {
                return null;
            }
            values[i] = value;
        }
        long address = 0;
        for (int i = 0; i < values.length - 1; i++) {
            if (values[i] > 255) {
                return null;
            }
            address |= values[i] << (8 * (3 - i));
        }
        long last = values[values.length - 1];
        if (last >= 1L << (8 * (5 - values.length))) {
            return null;
        }
        address |= last;
        return (address >> 24 & 255) + "." + (address >> 16 & 255) + "." + (address >> 8 & 255) + "." + (address & 255);
    }

    private static boolean numeric(String part) {
        if (part.startsWith("0x") || part.startsWith("0X")) {
            return part.substring(2).chars().allMatch(c -> Character.digit(c, 16) >= 0);
        }
        return !part.isEmpty() && part.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static @Nullable Long ipv4Number(String part) {
        if (part.isEmpty()) {
            return null;
        }
        int radix = 10;
        String digits = part;
        if (part.startsWith("0x") || part.startsWith("0X")) {
            radix = 16;
            digits = part.substring(2);
        } else if (part.length() > 1 && part.startsWith("0")) {
            radix = 8;
            digits = part.substring(1);
        }
        // Browsers skip any number of leading zeros ("0000000000000177" is 127 in octal)
        int firstNonZero = 0;
        while (firstNonZero < digits.length() - 1 && digits.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        digits = digits.substring(firstNonZero);
        if (digits.isEmpty()) {
            return 0L;
        }
        if (digits.length() > 12) {
            return null;
        }
        try {
            return Long.parseLong(digits, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // %xx escapes as UTF-8; anything that isn't a valid escape is kept as written
    static String percentDecode(String text) {
        if (text.indexOf('%') < 0) {
            return text;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length() && hex(text.charAt(i + 1)) >= 0 && hex(text.charAt(i + 2)) >= 0) {
                bytes.write(hex(text.charAt(i + 1)) * 16 + hex(text.charAt(i + 2)));
                i += 3;
                continue;
            }
            if (bytes.size() > 0) {
                out.append(bytes.toString(StandardCharsets.UTF_8));
                bytes.reset();
            }
            out.append(c);
            i++;
        }
        if (bytes.size() > 0) {
            out.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
