package org.kaiwasabi.privacyplus.logs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

// Everything that identifies this player in a log: the computer's home folder (in every spelling a log
// may use) and user name, the Minecraft accounts' names and UUIDs, and secrets such as access tokens.
record Identity(List<String> homes, @Nullable String userName, Set<String> names, Set<UUID> ids, Set<String> secrets) {
    // Shorter secrets aren't real tokens (the dev environment's access token is "FabricMC")
    static final int MIN_SECRET_LENGTH = 12;

    private static final Pattern WINDOWS_TEMP = Pattern.compile("(?i)^(.+?)[\\\\/]AppData[\\\\/]Local[\\\\/]Temp[\\\\/]?$");

    Identity {
        homes = List.copyOf(homes);
        names = Set.copyOf(names);
        ids = Set.copyOf(ids);
        secrets = Set.copyOf(secrets);
    }

    // This computer's home folder and user name, from the JVM's own properties
    static Identity ofSystem() {
        return new Identity(homeSpellings(System.getProperty("user.home"), System.getProperty("java.io.tmpdir")),
            System.getProperty("user.name"), Set.of(), Set.of(), Set.of());
    }

    // Adds a signed-in account; accessToken, xuid and clientId go in secrets (short ones are left out)
    Identity withAccount(String name, @Nullable UUID id, List<@Nullable String> accountSecrets) {
        Set<String> moreSecrets = accountSecrets.stream()
            .filter(s -> s != null && s.length() >= MIN_SECRET_LENGTH)
            .collect(Collectors.toSet());
        return plus(Set.of(name), id == null ? Set.of() : Set.of(id), moreSecrets);
    }

    Identity plus(Set<String> moreNames, Set<UUID> moreIds, Set<String> moreSecrets) {
        if (names.containsAll(moreNames) && ids.containsAll(moreIds) && secrets.containsAll(moreSecrets)) {
            return this;
        }
        return new Identity(homes, userName, union(names, moreNames), union(ids, moreIds), union(secrets, moreSecrets));
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        return Stream.concat(a.stream(), b.stream()).collect(Collectors.toSet());
    }

    // The home folder as logs write it: as is, with forward slashes (file: URLs, Java paths on Windows),
    // with doubled backslashes (escaped in JSON or Java strings), URL-encoded, its real path behind
    // symlinks, and the 8.3 short form Windows sometimes uses (C:\Users\JOHNSM~1, seen in the temp folder).
    // Longest first, so the most specific spelling wins.
    static List<String> homeSpellings(@Nullable String home, @Nullable String tmpDir) {
        Set<String> bases = new LinkedHashSet<>();
        addHome(bases, home);
        if (home != null) {
            try {
                addHome(bases, Path.of(home).toRealPath().toString());
            } catch (IOException | InvalidPathException | SecurityException ignored) {
                // A missing home folder still has its spelling above
            }
        }
        if (tmpDir != null) {
            Matcher m = WINDOWS_TEMP.matcher(tmpDir);
            if (m.matches()) {
                addHome(bases, m.group(1));
            }
        }
        Set<String> spellings = new LinkedHashSet<>();
        for (String base : bases) {
            spellings.add(base);
            String forward = base.replace('\\', '/');
            spellings.add(forward);
            if (base.indexOf('\\') >= 0) {
                spellings.add(base.replace("\\", "\\\\"));
            }
            spellings.add(urlEncodePath(forward));
        }
        List<String> sorted = new ArrayList<>(spellings);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        return sorted;
    }

    private static void addHome(Set<String> bases, @Nullable String home) {
        if (home == null) {
            return;
        }
        String trimmed = home.strip();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // "/", "C:" or "/a" would match far too much
        if (trimmed.length() >= 4) {
            bases.add(trimmed);
        }
    }

    // Percent-encodes what a file: URL encodes (spaces, non-ASCII), keeping / and :
    private static String urlEncodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || "/:-._~".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format(Locale.ROOT, "%02X", c));
            }
        }
        return out.toString();
    }
}
