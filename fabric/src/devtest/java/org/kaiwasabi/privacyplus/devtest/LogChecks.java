package org.kaiwasabi.privacyplus.devtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.kaiwasabi.privacyplus.logs.LogCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// In-game checks for the log and crash-report cleaner; run near the end of SelfTest
final class LogChecks {
    private static final Logger LOG = LoggerFactory.getLogger("PrivacyPlus-LogChecks");
    // Looks like a JSON web token, as real access tokens do, but isn't one
    private static final String FAKE_TOKEN = "eyJhbGciOiJub25lIn0.eyJzZWxmdGVzdCI6dHJ1ZX0.c2VsZnRlc3Qtc2lnbmF0dXJl";

    private LogChecks() {
    }

    static void run(SelfTest test, Minecraft mc) throws Exception {
        Path dir = mc.gameDirectory.toPath();
        String home = System.getProperty("user.home");
        String osUser = System.getProperty("user.name");
        User user = mc.getUser();
        String name = user.getName();
        String uuid = user.getProfileId().toString();
        String id = UUID.randomUUID().toString().substring(0, 8);
        String sanity = "log sanity " + UUID.randomUUID();

        Collection<Appender> appenders = ((LoggerContext) LogManager.getContext(false)).getConfiguration().getRootLogger()
            .getAppenders().values();
        test.check("every log appender is behind the cleaner " + appenders.stream().map(a -> a.getClass().getSimpleName()).toList(),
            !appenders.isEmpty() && appenders.stream().allMatch(a -> a.getClass().getSimpleName().equals("CleaningAppender")));

        liveLines(test, dir, home, osUser, name, uuid, id, sanity);
        crashReport(test, home, name, id);
        savedLogs(test, dir, home, name, uuid, id, sanity);
    }

    // A line logged through an ordinary logger reaches latest.log and debug.log cleaned
    private static void liveLines(SelfTest test, Path dir, String home, String osUser, String name, String uuid, String id, String sanity)
        throws Exception {
        LOG.info("logcheck {} home {}/Documents user {} player {} uuid {} token token:{}:{} ip 93.184.216.34 lan 192.168.1.20 {}",
            id, home, osUser, name, uuid, FAKE_TOKEN, uuid.replace("-", ""), sanity);
        LOG.warn("logcheck " + id + " exception", new IOException("cannot open " + home + "/secret.txt"));
        Thread.sleep(1000);

        String log = Files.readString(dir.resolve("logs/latest.log"));
        String line = log.lines().filter(l -> l.contains("logcheck " + id + " home")).findFirst().orElse("");
        test.check("live: the test line reached latest.log", !line.isEmpty());
        test.check("live: home folder became ~ (" + line + ")", line.contains("home ~/Documents") && !line.contains(home));
        test.check("live: computer user name became <user>", line.contains("user <user>") || osUser.length() < 3);
        test.check("live: account name became <player>", line.contains("player <player>") && !containsWord(line, name));
        test.check("live: account UUID became <uuid>", line.contains("uuid <uuid>") && !line.contains(uuid));
        test.check("live: session token became <token>", line.contains("token <token>") && !line.contains(FAKE_TOKEN));
        test.check("live: public IP became <ip>, LAN address kept", line.contains("ip <ip> lan 192.168.1.20"));
        test.check("live: a random UUID survives", line.contains(sanity));
        test.check("live: exception message cleaned, class name kept", log.contains("java.io.IOException: cannot open ~/secret.txt"));
        test.check("latest.log has no home folder anywhere (start-up lines blanked out)", !log.contains(home));
        test.check("latest.log never names the account", !containsWord(log, name));

        Path debugFile = dir.resolve("logs/debug.log");
        if (Files.exists(debugFile)) {
            String debug = Files.readString(debugFile);
            test.check("debug.log line cleaned too", debug.contains("logcheck " + id + " home ~/Documents") && debug.contains(sanity));
            test.check("debug.log has no home folder or account name anywhere", !debug.contains(home) && !containsWord(debug, name));
        }
    }

    private static void crashReport(SelfTest test, String home, String name, String id) {
        CrashReport report = new CrashReport("PrivacyPlus self-test " + id,
            new IllegalStateException("crash check in " + home + "/crash for " + name));
        String text = report.getFriendlyReport(ReportType.CRASH);
        test.check("crash report has no home folder", !text.contains(home) && text.contains("crash check in ~/crash"));
        test.check("crash report has no account name", !containsWord(text, name) && text.contains("for <player>"));
    }

    // A fake rotated log with identity data and a chat line, cleaned with chat kept and then removed
    private static void savedLogs(SelfTest test, Path dir, String home, String name, String uuid, String id, String sanity)
        throws IOException {
        Path fake = dir.resolve("logs/privacyplus-selftest.log.gz");
        String chat = "[10:00:03] [Render thread/INFO]: [CHAT] <" + name + "> selftest chat " + id;
        String content = String.join("\n",
            "[10:00:00] [main/INFO]: Loading mods from " + home + "/mods",
            "[10:00:01] [Render thread/INFO]: Setting user: " + name,
            "[10:00:02] [Render thread/INFO]: (Session ID is token:" + FAKE_TOKEN + ":" + uuid.replace("-", "") + ")",
            chat,
            "[10:00:04] [Render thread/INFO]: Connecting to 93.184.216.34, 25565 " + sanity,
            "");
        boolean cleanLogs = PrivacyPlusConfig.cleanLogs;
        boolean removeChat = PrivacyPlusConfig.removeChatFromOldLogs;
        try {
            PrivacyPlusConfig.cleanLogs = true;
            PrivacyPlusConfig.removeChatFromOldLogs = false;
            writeGzip(fake, content);
            String summary = LogCleaner.cleanSavedLogs();
            String kept = readGzip(fake);
            test.check("saved logs: summary says what was cleaned (" + summary + ")", summary.startsWith("Cleaned"));
            test.check("saved logs: identity removed (" + kept.replace('\n', '|') + ")", !kept.contains(home) && !containsWord(kept, name)
                && !kept.contains(FAKE_TOKEN) && !kept.contains("93.184.216.34"));
            test.check("saved logs: placeholders in place", kept.contains("Loading mods from ~/mods") && kept.contains("Setting user: <player>")
                && kept.contains("(Session ID is <token>)") && kept.contains("Connecting to <ip>, 25565 " + sanity));
            test.check("saved logs: chat kept while removeChatFromOldLogs is off", kept.contains("[CHAT] <<player>> selftest chat " + id));
            byte[] once = Files.readAllBytes(fake);
            LogCleaner.cleanSavedLogs();
            test.check("saved logs: an already cleaned file isn't rewritten",
                Arrays.equals(once, Files.readAllBytes(fake)));

            PrivacyPlusConfig.removeChatFromOldLogs = true;
            writeGzip(fake, content);
            LogCleaner.cleanSavedLogs();
            String stripped = readGzip(fake);
            test.check("saved logs: chat removed while removeChatFromOldLogs is on", !stripped.contains("selftest chat " + id)
                && !stripped.contains("[CHAT]"));
            test.check("saved logs: the other lines stay, cleaned", stripped.contains("Setting user: <player>") && stripped.contains(sanity)
                && !stripped.contains(home));
        } finally {
            PrivacyPlusConfig.cleanLogs = cleanLogs;
            PrivacyPlusConfig.removeChatFromOldLogs = removeChat;
            Files.deleteIfExists(fake);
        }
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    // An hour old, so it is clearly a file from an earlier session
    private static void writeGzip(Path file, String text) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    }

    private static String readGzip(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
