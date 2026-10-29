package org.kaiwasabi.privacyplus.logs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedLogsTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ACCOUNT = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");
    private static final Identity IDENTITY = new Identity(List.of("/Users/alice"), "alice", Set.of(), Set.of(), Set.of())
        .withAccount("Wasabi_Fan", ACCOUNT, List.of());
    private static final String SANITY = "sanity " + UUID.randomUUID();
    private static final String SESSION = String.join("\n",
        "[10:00:00] [main/INFO]: Loading mods from /Users/alice/mods",
        "[10:00:01] [Render thread/INFO]: Setting user: Wasabi_Fan",
        "[10:00:02] [Render thread/INFO]: [CHAT] <Wasabi_Fan> meet at the base",
        "[10:00:02] [Server thread/INFO]: [Not Secure] <Wasabi_Fan> meet at the base",
        "[10:00:03] [Render thread/INFO]: [System] [CHAT] Wasabi_Fan joined the game",
        "[10:00:04] [Render thread/INFO]: Connecting to 93.184.216.34, 25565 " + SANITY,
        "");

    @TempDir
    Path game;
    private Path logs;

    @BeforeEach
    void setUp() throws IOException {
        logs = Files.createDirectories(game.resolve("logs"));
        Files.createDirectories(game.resolve("crash-reports"));
    }

    private SavedLogs.Result run(boolean scrub, boolean removeChat, int days) throws IOException {
        SavedLogs saved = new SavedLogs(game, game.resolve("config").resolve(SavedLogs.STATE_FILE), Set.of(), CLOCK);
        return saved.run(new SavedLogs.Settings(scrub, removeChat, days), () -> IDENTITY);
    }

    @Test
    void cleansRotatedLogsCrashReportsAndJvmCrashLogs() throws IOException {
        Path gz = writeGzip(logs.resolve("2026-10-29-1.log.gz"), SESSION);
        Path plain = write(logs.resolve("old.log"), SESSION);
        Path crash = write(game.resolve("crash-reports/crash-2026-10-29_10.00.00-client.txt"),
            "Description: Rendering\njava.io.IOException: /Users/alice/x\n");
        Path jvm = write(game.resolve("hs_err_pid42.log"),
            "java_command: Main --username Wasabi_Fan --accessToken abcdef1234567890\nHOME=/Users/alice\n");
        Path latest = write(logs.resolve("latest.log"), SESSION);
        Path debug = write(logs.resolve("debug.log"), SESSION);
        FileTime yesterday = FileTime.from(NOW.minus(Duration.ofDays(1)));
        for (Path file : List.of(gz, plain, crash, jvm)) {
            Files.setLastModifiedTime(file, yesterday);
        }

        assertEquals(new SavedLogs.Result(4, 0, 0), run(true, false, 0));

        String cleaned = readGzip(gz);
        assertEquals(SESSION.replace("/Users/alice", "~").replace("Wasabi_Fan", "<player>").replace("93.184.216.34", "<ip>"), cleaned);
        assertTrue(cleaned.contains("[CHAT] <<player>> meet at the base"), "chat stays unless asked");
        assertTrue(cleaned.contains(SANITY));
        assertEquals(cleaned, Files.readString(plain));
        assertEquals("Description: Rendering\njava.io.IOException: ~/x\n", Files.readString(crash));
        assertEquals("java_command: Main --username <player> --accessToken <token>\nHOME=~\n", Files.readString(jvm));
        for (Path file : List.of(gz, plain, crash, jvm)) {
            assertEquals(yesterday, Files.getLastModifiedTime(file), "modification time kept: " + file);
        }
        assertEquals(SESSION, Files.readString(latest), "the live log is never touched");
        assertEquals(SESSION, Files.readString(debug));
        assertNoTempFiles();
    }

    @Test
    void remembersWhatWasCleaned() throws IOException {
        Path gz = writeGzip(logs.resolve("2026-10-29-1.log.gz"), SESSION);
        writeGzip(logs.resolve("2026-10-29-2.log.gz"), "nothing to see here\n");
        assertEquals(new SavedLogs.Result(1, 0, 0), run(true, false, 0));
        byte[] once = Files.readAllBytes(gz);
        assertTrue(Files.readString(game.resolve("config").resolve(SavedLogs.STATE_FILE)).contains("v1"));

        assertEquals(new SavedLogs.Result(0, 0, 0), run(true, false, 0), "nothing left to do");
        assertArrayEquals(once, Files.readAllBytes(gz));

        // log4j renames rotated logs (debug-1 becomes debug-2); that doesn't make them new
        Files.move(gz, logs.resolve("2026-10-29-3.log.gz"));
        assertEquals(new SavedLogs.Result(0, 0, 0), run(true, false, 0));

        // A setting switched on later still applies to files cleaned before
        assertEquals(new SavedLogs.Result(1, 0, 0), run(true, true, 0));
        assertEquals("Nothing to clean", run(true, true, 0).summary());
    }

    @Test
    void removesChatLinesWhenAsked() throws IOException {
        Path gz = writeGzip(logs.resolve("debug-1.log.gz"), SESSION);
        Path fabric = writeGzip(logs.resolve("2026-10-28-1.log.gz"), String.join("\n",
            "[05:26:06] [Render thread/INFO] (Minecraft) [CHAT] <Player225> hello",
            "[05:26:06] [Server thread/INFO] (Minecraft) <<player>> hello",
            "[05:26:07] [Render thread/INFO] (Minecraft) Not chat: [CHAT] in the middle",
            "[29Oct2026 12:34:56.789] [Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [CHAT] forge",
            "[05:26:08] [Server thread/INFO] (Minecraft) Player225 joined the game",
            ""));
        Path crash = write(game.resolve("crash-reports/crash.txt"), "[CHAT] crash reports keep their text\n");

        assertEquals(new SavedLogs.Result(2, 0, 0), run(false, true, 0));
        assertEquals(String.join("\n",
            "[10:00:00] [main/INFO]: Loading mods from /Users/alice/mods",
            "[10:00:01] [Render thread/INFO]: Setting user: Wasabi_Fan",
            "[10:00:04] [Render thread/INFO]: Connecting to 93.184.216.34, 25565 " + SANITY,
            ""), readGzip(gz), "chat removed, the rest left as it was (cleanLogs off)");
        assertEquals(String.join("\n",
            "[05:26:07] [Render thread/INFO] (Minecraft) Not chat: [CHAT] in the middle",
            "[05:26:08] [Server thread/INFO] (Minecraft) Player225 joined the game",
            ""), readGzip(fabric));
        assertEquals("[CHAT] crash reports keep their text\n", Files.readString(crash));
    }

    @Test
    void deletesOldLogsAndCrashReports() throws IOException {
        Path old = write(logs.resolve("debug-3.log.gz"), "x");
        Path oldByName = writeGzip(logs.resolve("2026-01-01-1.log.gz"), "rotated long after the session\n");
        Path oldCrash = write(game.resolve("crash-reports/crash-old.txt"), "x");
        Path recent = writeGzip(logs.resolve("2026-10-29-1.log.gz"), "recent\n");
        Path oldLive = write(logs.resolve("latest.log"), "live");
        FileTime fortyDaysAgo = FileTime.from(NOW.minus(Duration.ofDays(40)));
        Files.setLastModifiedTime(old, fortyDaysAgo);
        Files.setLastModifiedTime(oldCrash, fortyDaysAgo);
        Files.setLastModifiedTime(oldLive, fortyDaysAgo);
        Files.setLastModifiedTime(recent, FileTime.from(NOW.minus(Duration.ofDays(2))));

        SavedLogs.Result result = run(false, false, 30);
        assertEquals(new SavedLogs.Result(0, 3, 0), result);
        assertEquals("Deleted 3 log files", result.summary());
        assertFalse(Files.exists(old) || Files.exists(oldByName) || Files.exists(oldCrash));
        assertTrue(Files.exists(recent) && Files.exists(oldLive));
    }

    @Test
    void fileNamingAnotherAccountIsCleanedToo() throws IOException {
        Path gz = writeGzip(logs.resolve("2025-01-01-1.log.gz"), String.join("\n",
            "[main/INFO]: ModLauncher running: args [--username, OldMain, --uuid, 123e4567e89b12d3a456426614174000]",
            "[Render thread/INFO]: Setting user: OldMain",
            "[Render thread/INFO]: [CHAT] <OldMain> hi, 123e4567-e89b-12d3-a456-426614174000",
            "[Render thread/INFO]: [CHAT] <Friend> hi OldMain",
            ""));
        run(true, false, 0);
        assertEquals(String.join("\n",
            "[main/INFO]: ModLauncher running: args [--username, <player>, --uuid, <uuid>]",
            "[Render thread/INFO]: Setting user: <player>",
            "[Render thread/INFO]: [CHAT] <<player>> hi, <uuid>",
            "[Render thread/INFO]: [CHAT] <Friend> hi <player>",
            ""), readGzip(gz));
    }

    @Test
    void keepsLineEndingsAndBytesItDoesNotUnderstand() throws IOException {
        // Latin-1 é, as old Windows logs have; not valid UTF-8
        byte[] latin1 = "café /Users/alice/x\r\nno newline at the end /Users/alice".getBytes(StandardCharsets.ISO_8859_1);
        Path file = logs.resolve("windows.log");
        Files.write(file, latin1);
        Path utf8 = write(logs.resolve("utf8.log"), "café ☃ /Users/alice\r\n\r\nlast\n");
        assertEquals(new SavedLogs.Result(2, 0, 0), run(true, false, 0));
        assertArrayEquals("café ~/x\r\nno newline at the end ~".getBytes(StandardCharsets.ISO_8859_1), Files.readAllBytes(file));
        assertEquals("café ☃ ~\r\n\r\nlast\n", Files.readString(utf8));
    }

    @Test
    void skipsBrokenAndBusyFiles() throws IOException {
        Path broken = write(logs.resolve("2026-10-29-1.log.gz"), "not gzip at all");
        Path compressing = write(logs.resolve("2026-10-29-2.log"), SESSION);
        writeGzip(logs.resolve("2026-10-29-2.log.gz"), "half written");
        Path leftover = write(logs.resolve("2026-10-29-3.log.gz" + SavedLogs.TEMP_SUFFIX), "from a crash");

        SavedLogs.Result result = run(true, false, 0);
        assertEquals(1, result.failed());
        assertEquals("not gzip at all", Files.readString(broken), "left alone");
        assertEquals(SESSION, Files.readString(compressing), "log4j is still compressing it");
        assertFalse(Files.exists(leftover));
        assertNoTempFiles();
    }

    @Test
    void summaries() {
        assertEquals("Cleaned 12 log files, deleted 3", new SavedLogs.Result(12, 3, 0).summary());
        assertEquals("Cleaned 1 log file", new SavedLogs.Result(1, 0, 0).summary());
        assertEquals("Cleaned 2 log files (1 couldn't be cleaned)", new SavedLogs.Result(2, 0, 1).summary());
        assertEquals("Couldn't clean 1 log file", new SavedLogs.Result(0, 0, 1).summary());
        assertEquals("Nothing to clean", new SavedLogs.Result(0, 0, 0).summary());
    }

    @Test
    void chatLineFormats() {
        assertTrue(SavedLogs.isChat("[12:00:00] [Render thread/INFO]: [CHAT] hi"));
        assertTrue(SavedLogs.isChat("[12:00:00] [Render thread/INFO]: [Not Secure] [CHAT] <a> hi"));
        assertTrue(SavedLogs.isChat("[12:00:00] [Render thread/INFO]: [CHAT] "));
        assertTrue(SavedLogs.isChat("[12:00:00] [Server thread/INFO]: <Wasabi_Fan> hi"));
        assertFalse(SavedLogs.isChat("[12:00:00] [Server thread/INFO]: Wasabi_Fan joined the game"));
        assertFalse(SavedLogs.isChat("[12:00:00] [Render thread/INFO]: Said [CHAT] hi"));
        assertFalse(SavedLogs.isChat("[12:00:00] [Worker/INFO]: <init> failed"));
    }

    private void assertNoTempFiles() throws IOException {
        try (Stream<Path> files = Files.walk(game)) {
            assertTrue(files.noneMatch(f -> f.toString().endsWith(SavedLogs.TEMP_SUFFIX)));
        }
    }

    private static Path write(Path file, String text) throws IOException {
        return Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Path writeGzip(Path file, String text) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static String readGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
