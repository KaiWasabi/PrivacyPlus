package org.kaiwasabi.privacyplus.logs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.kaiwasabi.privacyplus.PrivacyPlus;
import org.kaiwasabi.privacyplus.PrivacyPlusConfig;
import org.jspecify.annotations.Nullable;

// Removes your identity from log files and crash reports: new log lines are cleaned before log4j writes
// them, lines from before the mod loaded are blanked out in place, crash reports are cleaned as they are
// made, and old logs are cleaned (and, if asked, stripped of chat or deleted) in the background at start-up.
// Purely local: nothing here is visible to a server.
public final class LogCleaner {
    // Let log4j finish compressing the log it rotated at start-up before touching old logs
    private static final long STARTUP_DELAY_MILLIS = 10_000;
    // How long to wait for Minecraft to have a signed-in user before cleaning without the account name
    private static final long ACCOUNT_WAIT_MILLIS = 60_000;

    private static final Object ACCOUNT_LOCK = new Object();
    private static final Object SAVED_LOCK = new Object();
    private static volatile Identity identity = Identity.ofSystem();
    private static volatile LogScrubber scrubber = new LogScrubber(identity);
    // The Minecraft user the scrubber knows about; account switcher mods can swap it
    private static volatile @Nullable User account;
    private static volatile @Nullable Path gameDirectory;
    private static volatile Set<Path> liveFiles = Set.of();
    private static volatile boolean installed;

    private LogCleaner() {
    }

    // Called once at startup with the game directory (the one holding logs/ and crash-reports/)
    public static void install(Path gameDirectory) {
        if (installed) {
            return;
        }
        LogCleaner.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        installed = true;
        try {
            liveFiles = Set.copyOf(LiveLogs.install(() -> PrivacyPlusConfig.cleanLogs ? scrubber() : null));
        } catch (RuntimeException | LinkageError e) {
            PrivacyPlus.LOG.warn("Couldn't hook into logging, new log lines won't be cleaned", e);
        }
        // Everything in these files so far was written before the cleaning started
        Map<Path, Long> written = new HashMap<>();
        for (Path file : liveFiles) {
            try {
                written.put(file, Files.size(file));
            } catch (IOException e) {
                // Not created yet, so nothing to clean in it
            }
        }
        if (!settings().anything()) {
            return;
        }
        Thread.ofPlatform().name("PrivacyPlus log cleaner").daemon().priority(Thread.MIN_PRIORITY).start(() -> {
            try {
                awaitAccount();
                if (PrivacyPlusConfig.cleanLogs) {
                    for (Map.Entry<Path, Long> entry : written.entrySet()) {
                        try {
                            LiveLogs.maskWritten(entry.getKey(), entry.getValue(), scrubber());
                        } catch (IOException e) {
                            PrivacyPlus.LOG.warn("Couldn't clean the start of {}: {}", entry.getKey().getFileName(), e.toString());
                        }
                    }
                }
                Thread.sleep(STARTUP_DELAY_MILLIS);
                SavedLogs.Result result = cleanSaved();
                if (result.cleaned() + result.deleted() + result.failed() > 0) {
                    PrivacyPlus.LOG.info("Old logs: {}", result.summary());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException e) {
                PrivacyPlus.LOG.warn("Couldn't clean old log files", e);
            }
        });
    }

    // Cleans the saved (rotated) log files now, following the current settings. Returns a short summary
    // for the settings screen, such as "Cleaned 12 log files". Blocks while it reads the files.
    public static String cleanSavedLogs() {
        if (gameDirectory == null) {
            return "Nothing to clean";
        }
        try {
            return cleanSaved().summary();
        } catch (IOException | RuntimeException e) {
            PrivacyPlus.LOG.warn("Couldn't clean old log files", e);
            return "Couldn't clean the log files: " + e.getMessage();
        }
    }

    // A crash report's text with your identity removed (when cleanLogs is on). Never throws: a crash
    // report must always come out, cleaned or not.
    public static String cleanReport(String report) {
        if (!PrivacyPlusConfig.cleanLogs) {
            return report;
        }
        try {
            return scrubber().clean(report);
        } catch (RuntimeException | LinkageError e) {
            return report;
        }
    }

    private static SavedLogs.Result cleanSaved() throws IOException {
        Path dir = gameDirectory;
        if (dir == null) {
            return new SavedLogs.Result(0, 0, 0);
        }
        synchronized (SAVED_LOCK) {
            SavedLogs saved = new SavedLogs(dir, dir.resolve("config").resolve(SavedLogs.STATE_FILE), liveFiles, Clock.systemDefaultZone());
            return saved.run(settings(), () -> {
                refreshAccount();
                return identity;
            });
        }
    }

    private static SavedLogs.Settings settings() {
        return new SavedLogs.Settings(PrivacyPlusConfig.cleanLogs, PrivacyPlusConfig.removeChatFromOldLogs,
            PrivacyPlusConfig.deleteLogsAfterDays);
    }

    static LogScrubber scrubber() {
        refreshAccount();
        return scrubber;
    }

    // The account is only known once Minecraft has a user. Mod init runs inside Minecraft's
    // constructor, so the instance can exist with no user yet; before install, Minecraft may not even
    // be loaded, and looking it up then could start its class initialisation far too early.
    private static void refreshAccount() {
        if (!installed) {
            return;
        }
        User user;
        try {
            Minecraft mc = Minecraft.getInstance();
            user = mc == null ? null : mc.getUser();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (user == null || user == account) {
            return;
        }
        synchronized (ACCOUNT_LOCK) {
            if (user != account) {
                try {
                    identity = identity.withAccount(user.getName(), user.getProfileId(),
                        Arrays.asList(user.getAccessToken(), user.getXuid().orElse(null), user.getClientId().orElse(null)));
                    scrubber = new LogScrubber(identity);
                } catch (RuntimeException e) {
                    // A user a mod made up without a name or tokens; don't retry on every log line
                }
                account = user;
            }
        }
    }

    private static void awaitAccount() throws InterruptedException {
        long deadline = System.currentTimeMillis() + ACCOUNT_WAIT_MILLIS;
        refreshAccount();
        while (account == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            refreshAccount();
        }
    }
}
