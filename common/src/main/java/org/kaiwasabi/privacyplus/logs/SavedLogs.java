package org.kaiwasabi.privacyplus.logs;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.jspecify.annotations.Nullable;

// Cleans what earlier sessions left behind: rotated logs (logs/*.log.gz and old .log files), crash
// reports and the JVM's own crash logs (hs_err_pid*.log, which list the launch command and environment).
// A small state file remembers what was already done (just sizes and modification times, no names), so
// each file is read once. Files are rewritten through a temporary file that is moved over the original,
// keeping its modification time, so a crash halfway never leaves a broken file.
final class SavedLogs {
    // What the cleaning should do; each part can be switched on by itself
    record Settings(boolean scrub, boolean removeChat, int deleteAfterDays) {
        boolean anything() {
            return scrub || removeChat || deleteAfterDays > 0;
        }
    }

    record Result(int cleaned, int deleted, int failed) {
        String summary() {
            String text;
            if (cleaned > 0 && deleted > 0) {
                text = "Cleaned " + files(cleaned) + ", deleted " + deleted;
            } else if (cleaned > 0) {
                text = "Cleaned " + files(cleaned);
            } else if (deleted > 0) {
                text = "Deleted " + files(deleted);
            } else if (failed > 0) {
                return "Couldn't clean " + files(failed);
            } else {
                return "Nothing to clean";
            }
            return failed > 0 ? text + " (" + failed + " couldn't be cleaned)" : text;
        }

        private static String files(int count) {
            return count + (count == 1 ? " log file" : " log files");
        }
    }

    static final String STATE_FILE = "privacyplus-cleaned-logs.txt";
    static final String TEMP_SUFFIX = ".privacyplus-tmp";
    private static final int SCRUBBED = 1;
    private static final int CHAT_REMOVED = 2;
    // Bump when the cleaning rules change, so files are cleaned again with the new ones
    private static final String STATE_VERSION = "v1";

    // Vanilla's ChatComponent.logChatMessage writes "[CHAT] text" or "[tag] [CHAT] text" after the layout's
    // prefix (time, thread and level, maybe the logger, all in brackets or parentheses). The prefix is a few
    // groups at most; the bound keeps a server-made line of thousands of "[]" from overflowing the stack.
    private static final Pattern CHAT_LINE = Pattern.compile("^(?:(?:\\[[^\\]]*]|\\([^)]*\\)):?\\s*){1,8}\\[CHAT] ");
    // The built-in server (singleplayer, LAN) logs the same chat as "<name> text" or "[Not Secure] <name> text"
    private static final Pattern SERVER_CHAT_LINE = Pattern.compile(
        "^(?:(?:\\[[^\\]]*]|\\([^)]*\\)):?\\s*){1,8}<(?:<player>|[^<>\\s][^<>]{0,39})> ");
    // Lines that name the account a log belongs to, also in logs from before the mod was installed
    private static final Pattern SETTING_USER = Pattern.compile("Setting user: ([A-Za-z0-9_]{2,16})(?![A-Za-z0-9_])");
    private static final Pattern USERNAME_ARG = Pattern.compile("--username[,=\\s]+([A-Za-z0-9_]{2,16})(?![A-Za-z0-9_])");
    private static final Pattern UUID_ARG = Pattern.compile(
        "--uuid[,=\\s]+([0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12})(?![0-9a-fA-F])");
    private static final Pattern SESSION_UUID = Pattern.compile("token:[^\\s:]+:([0-9a-fA-F]{32})(?![0-9a-fA-F])");
    private static final Pattern NAME_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private record Candidate(Path path, boolean gzip, boolean chatLog) {
    }

    // Names and UUIDs a file itself reveals, and the charset it decodes with
    private record Scan(Set<String> names, Set<UUID> ids, Charset charset) {
    }

    private final Path gameDirectory;
    private final Path stateFile;
    private final Set<Path> liveFiles;
    private final Clock clock;

    // liveFiles: absolute paths log4j is writing to right now; they are never touched
    SavedLogs(Path gameDirectory, Path stateFile, Set<Path> liveFiles, Clock clock) {
        this.gameDirectory = gameDirectory;
        this.stateFile = stateFile;
        this.liveFiles = Set.copyOf(liveFiles);
        this.clock = clock;
    }

    // One cleaner at a time: the start-up run and the settings button in this game, and other games
    // sharing the folder (each rewrite would otherwise race on the same files)
    Result run(Settings settings, Supplier<Identity> identity) throws IOException {
        synchronized (SavedLogs.class) {
            Files.createDirectories(this.stateFile.getParent());
            Path lockFile = this.stateFile.resolveSibling(this.stateFile.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    return new Result(0, 0, 0);
                }
                return this.runLocked(settings, identity);
            }
        }
    }

    private Result runLocked(Settings settings, Supplier<Identity> identity) throws IOException {
        Map<String, Integer> state = readState();
        Map<String, Integer> next = new HashMap<>();
        Instant cutoff = settings.deleteAfterDays() > 0 ? clock.instant().minus(Duration.ofDays(settings.deleteAfterDays())) : null;
        Scrubbers scrubbers = new Scrubbers(identity);
        int cleaned = 0;
        int deleted = 0;
        int failed = 0;
        for (Candidate candidate : candidates()) {
            String key = null;
            try {
                BasicFileAttributes attrs = Files.readAttributes(candidate.path(), BasicFileAttributes.class);
                key = key(attrs);
                if (cutoff != null && age(candidate.path(), attrs).isBefore(cutoff)) {
                    Files.deleteIfExists(candidate.path());
                    deleted++;
                    continue;
                }
                int needed = (settings.scrub() ? SCRUBBED : 0) | (settings.removeChat() && candidate.chatLog() ? CHAT_REMOVED : 0);
                int done = state.getOrDefault(key, 0);
                if ((done & needed) == needed) {
                    if (done != 0) {
                        next.put(key, done);
                    }
                    continue;
                }
                if (rewrite(candidate, attrs, needed, scrubbers)) {
                    cleaned++;
                    next.merge(key(Files.readAttributes(candidate.path(), BasicFileAttributes.class)), needed | done, (a, b) -> a | b);
                } else {
                    next.merge(key, needed | done, (a, b) -> a | b);
                }
            } catch (NoSuchFileException e) {
                // Gone meanwhile (log4j renames rotated logs at start-up)
            } catch (IOException | RuntimeException | StackOverflowError e) {
                // One bad file mustn't stop the others (or the state being saved) on every start-up
                failed++;
                PrivacyPlus.LOG.warn("Couldn't clean {}: {}", candidate.path().getFileName(), e.toString());
                if (key != null && state.containsKey(key)) {
                    next.put(key, state.get(key));
                }
            }
        }
        if (!next.equals(state)) {
            writeState(next);
        }
        return new Result(cleaned, deleted, failed);
    }

    private List<Candidate> candidates() throws IOException {
        List<Candidate> found = new ArrayList<>();
        Path logs = gameDirectory.resolve("logs");
        Set<String> names = new HashSet<>();
        for (Path file : list(logs)) {
            names.add(file.getFileName().toString());
        }
        for (Path file : list(logs)) {
            String name = file.getFileName().toString();
            if (name.endsWith(TEMP_SUFFIX)) {
                // Left behind by a crash during an earlier rewrite; the original is still intact
                Files.deleteIfExists(file);
            } else if (isLive(file)) {
                continue;
            } else if (name.endsWith(".log.gz")) {
                found.add(new Candidate(file, true, true));
            } else if (name.endsWith(".log") && !names.contains(name + ".gz")) {
                // A .log beside its .log.gz is one log4j is still compressing
                found.add(new Candidate(file, false, true));
            }
        }
        for (Path file : list(gameDirectory.resolve("crash-reports"))) {
            String name = file.getFileName().toString();
            if (name.endsWith(TEMP_SUFFIX)) {
                Files.deleteIfExists(file);
            } else if (name.endsWith(".txt")) {
                found.add(new Candidate(file, false, false));
            }
        }
        for (Path file : list(gameDirectory)) {
            String name = file.getFileName().toString();
            if (name.startsWith("hs_err_pid") && name.endsWith(TEMP_SUFFIX)) {
                Files.deleteIfExists(file);
            } else if (name.startsWith("hs_err_pid") && name.endsWith(".log")) {
                found.add(new Candidate(file, false, false));
            }
        }
        return found;
    }

    private boolean isLive(Path file) {
        String name = file.getFileName().toString();
        return name.equals("latest.log") || name.equals("debug.log") || liveFiles.contains(file.toAbsolutePath().normalize());
    }

    private static List<Path> list(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    // When the file was last written to, or the end of the day in its name if that is earlier: log4j
    // compresses a log at the next start-up, so the .gz itself can be much newer than its content
    private Instant age(Path file, BasicFileAttributes attrs) {
        Instant modified = attrs.lastModifiedTime().toInstant();
        Matcher m = NAME_DATE.matcher(file.getFileName().toString());
        if (m.find()) {
            try {
                LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                Instant endOfDay = date.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
                return endOfDay.isBefore(modified) ? endOfDay : modified;
            } catch (DateTimeException e) {
                // Not a date after all
            }
        }
        return modified;
    }

    private static String key(BasicFileAttributes attrs) {
        return attrs.size() + " " + attrs.lastModifiedTime().toMillis();
    }

    // Returns whether the file changed. Reads it twice: first for the names it reveals and its charset,
    // then to write the cleaned copy.
    private boolean rewrite(Candidate candidate, BasicFileAttributes attrs, int flags, Scrubbers scrubbers) throws IOException {
        Path file = candidate.path();
        Scan scan;
        try {
            scan = scan(candidate, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // Logs from older Java versions on Windows are in the system charset; Latin-1 keeps every byte as it is
            scan = scan(candidate, StandardCharsets.ISO_8859_1);
        }
        LogScrubber scrubber = (flags & SCRUBBED) != 0 ? scrubbers.forFile(scan) : null;
        boolean removeChat = (flags & CHAT_REMOVED) != 0;
        // A name of its own, still ending in TEMP_SUFFIX so a crash's leftovers are found and removed
        Path temp = Files.createTempFile(file.getParent(), file.getFileName() + ".", TEMP_SUFFIX);
        boolean changed = false;
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                BufferedOutputStream buffered = new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16);
                OutputStream body = candidate.gzip() ? new GZIPOutputStream(buffered, 1 << 16) : buffered;
                try (Writer out = new BufferedWriter(new OutputStreamWriter(body, scan.charset()), 1 << 16);
                     Reader in = reader(candidate, decoder(scan.charset()))) {
                    LineReader lines = new LineReader(in);
                    String line;
                    while ((line = lines.next()) != null) {
                        int ending = line.endsWith("\r\n") ? 2 : line.endsWith("\n") ? 1 : 0;
                        String text = line.substring(0, line.length() - ending);
                        if (removeChat && isChat(text)) {
                            changed = true;
                            continue;
                        }
                        String clean = scrubber == null ? text : scrubber.clean(text);
                        changed |= !clean.equals(text);
                        out.write(clean);
                        out.write(line, text.length(), ending);
                    }
                    // Everything on the disk before the move makes it the real file
                    out.flush();
                    if (body instanceof GZIPOutputStream gzip) {
                        gzip.finish();
                    }
                    buffered.flush();
                    channel.force(true);
                }
            }
            if (!changed) {
                return false;
            }
            if (candidate.gzip()) {
                verifyGzip(temp);
            }
            Files.setLastModifiedTime(temp, attrs.lastModifiedTime());
            // log4j may have rotated a new log into this name meanwhile; never replace a file we didn't read
            BasicFileAttributes now = Files.readAttributes(file, BasicFileAttributes.class);
            if (now.size() != attrs.size() || !now.lastModifiedTime().equals(attrs.lastModifiedTime())) {
                return false;
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private Scan scan(Candidate candidate, Charset charset) throws IOException {
        Set<String> names = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        try (Reader in = reader(candidate, decoder(charset))) {
            LineReader lines = new LineReader(in);
            String line;
            while ((line = lines.next()) != null) {
                if (line.contains("Setting user: ")) {
                    find(SETTING_USER, line, names);
                }
                if (line.contains("--username")) {
                    find(USERNAME_ARG, line, names);
                }
                if (line.contains("--uuid")) {
                    findIds(UUID_ARG, line, ids);
                }
                if (line.contains("token:")) {
                    findIds(SESSION_UUID, line, ids);
                }
            }
        }
        return new Scan(names, ids, charset);
    }

    private static void find(Pattern pattern, String line, Set<String> into) {
        Matcher m = pattern.matcher(line);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private static void findIds(Pattern pattern, String line, Set<UUID> into) {
        Matcher m = pattern.matcher(line);
        while (m.find()) {
            String hex = m.group(1).replace("-", "");
            into.add(new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16)));
        }
    }

    static boolean isChat(String line) {
        if (line.contains("[CHAT] ") && CHAT_LINE.matcher(line).find()) {
            return true;
        }
        return line.contains("[Server thread/INFO]") && SERVER_CHAT_LINE.matcher(line).find();
    }

    private static Reader reader(Candidate candidate, CharsetDecoder decoder) throws IOException {
        InputStream in = Files.newInputStream(candidate.path());
        try {
            return new InputStreamReader(candidate.gzip() ? new GZIPInputStream(in, 1 << 16) : in, decoder);
        } catch (IOException e) {
            in.close();
            throw e;
        }
    }

    // Strict, so a file that isn't valid UTF-8 is noticed instead of silently changed
    private static CharsetDecoder decoder(Charset charset) {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    // Reads the new .gz back to the end before it replaces the old one
    private static void verifyGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file), 1 << 16)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
    }

    private Map<String, Integer> readState() {
        Map<String, Integer> state = new HashMap<>();
        try {
            List<String> lines = Files.readAllLines(stateFile, StandardCharsets.UTF_8);
            if (!lines.contains(STATE_VERSION)) {
                return state;
            }
            for (String line : lines) {
                String[] parts = line.trim().split(" ");
                if (parts.length == 3 && !line.startsWith("#")) {
                    try {
                        state.put(Long.parseLong(parts[0]) + " " + Long.parseLong(parts[1]), Integer.parseInt(parts[2]));
                    } catch (NumberFormatException e) {
                        // Skip a damaged line; that file is just cleaned again
                    }
                }
            }
        } catch (NoSuchFileException e) {
            // First run
        } catch (IOException e) {
            PrivacyPlus.LOG.warn("Couldn't read {}: {}", stateFile.getFileName(), e.toString());
        }
        return state;
    }

    private void writeState(Map<String, Integer> state) throws IOException {
        StringBuilder text = new StringBuilder("# PrivacyPlus: log files already cleaned (size, time modified, what was done).\n")
            .append("# Deleting this file only makes the next start-up check them again.\n")
            .append(STATE_VERSION).append('\n');
        state.forEach((key, flags) -> text.append(key).append(' ').append(flags).append('\n'));
        Files.createDirectories(stateFile.getParent());
        Path temp = stateFile.resolveSibling(stateFile.getFileName() + TEMP_SUFFIX);
        Files.writeString(temp, text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // One scrubber for the run, plus one per file that names another account (an older log from
    // before the mod, or from an account you no longer use)
    private static final class Scrubbers {
        private final Supplier<Identity> identity;
        private @Nullable Identity base;
        private @Nullable LogScrubber baseScrubber;

        Scrubbers(Supplier<Identity> identity) {
            this.identity = identity;
        }

        LogScrubber forFile(Scan scan) {
            if (base == null) {
                base = identity.get();
                baseScrubber = new LogScrubber(base);
            }
            Identity forFile = base.plus(scan.names(), scan.ids(), Set.of());
            return forFile == base ? baseScrubber : new LogScrubber(forFile);
        }
    }

    // Splits text into lines, keeping each line's ending so the file can be written back byte for byte
    static final class LineReader {
        private final Reader in;
        private final char[] buffer = new char[8192];
        private int position;
        private int length;

        LineReader(Reader in) {
            this.in = in;
        }

        // The next line with its "\n" or "\r\n", the last one possibly without; null at the end
        @Nullable String next() throws IOException {
            StringBuilder line = null;
            while (true) {
                if (position == length) {
                    length = in.read(buffer);
                    position = 0;
                    if (length <= 0) {
                        length = 0;
                        return line == null ? null : line.toString();
                    }
                }
                int start = position;
                while (position < length && buffer[position] != '\n') {
                    position++;
                }
                boolean ended = position < length;
                if (ended) {
                    position++;
                }
                if (line == null) {
                    line = new StringBuilder(position - start + 16);
                }
                line.append(buffer, start, position - start);
                if (ended) {
                    return line.toString();
                }
            }
        }
    }
}
