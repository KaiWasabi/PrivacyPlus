package org.kaiwasabi.privacyplus.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.WriterAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LiveLogsTest {
    private static final LogScrubber SCRUBBER = new LogScrubber(new Identity(List.of("/Users/alice"), "alice", Set.of(), Set.of(), Set.of())
        .withAccount("Wasabi_Fan", UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"), List.of()));

    private final LoggerContext context = new LoggerContext("privacyplus-test");

    // A configuration with one appender writing "%m%n" (and exceptions) to a StringWriter
    private static final class TestConfiguration extends AbstractConfiguration {
        private final StringWriter out;

        TestConfiguration(LoggerContext context, StringWriter out) {
            super(context, ConfigurationSource.NULL_SOURCE);
            this.out = out;
        }

        @Override
        protected void doConfigure() {
            WriterAppender appender = WriterAppender.newBuilder().setName("Out").setTarget(out)
                .setLayout(PatternLayout.newBuilder().setPattern("%m%n").build()).build();
            appender.start();
            addAppender(appender);
            LoggerConfig root = getRootLogger();
            root.setLevel(Level.INFO);
            root.addAppender(appender, null, null);
        }
    }

    @AfterEach
    void stop() {
        context.stop();
    }

    @Test
    void messagesAndExceptionsAreCleanedOnTheirWayOut() {
        StringWriter out = new StringWriter();
        context.start(new TestConfiguration(context, out));
        LiveLogs.install(context, () -> SCRUBBER);
        Logger logger = context.getLogger("test");

        String sanity = "sanity " + UUID.randomUUID();
        logger.info("Setting user: {} in {} ({})", "Wasabi_Fan", "/Users/alice/game", sanity);
        IllegalStateException cause = new IllegalStateException("bad file /Users/alice/x");
        logger.warn("Couldn't load", new java.io.IOException("cannot open /Users/alice/y", cause));
        logger.info("nothing to clean here");

        String text = out.toString();
        assertTrue(text.startsWith("Setting user: <player> in ~/game (" + sanity + ")\n"), text);
        assertTrue(text.contains("Couldn't load\njava.io.IOException: cannot open ~/y\n\tat "), "the exception keeps its class: " + text);
        assertTrue(text.contains("Caused by: java.lang.IllegalStateException: bad file ~/x\n\tat "), text);
        assertTrue(text.contains(LiveLogsTest.class.getName() + ".messagesAndExceptionsAreCleanedOnTheirWayOut"),
            "stack trace kept: " + text);
        assertTrue(text.endsWith("nothing to clean here\n"), text);
        assertFalse(text.contains("alice") || text.contains("Wasabi_Fan"), text);
    }

    @Test
    void switchedOffPassesEventsThrough() {
        StringWriter out = new StringWriter();
        context.start(new TestConfiguration(context, out));
        AtomicBoolean on = new AtomicBoolean(false);
        LiveLogs.install(context, () -> on.get() ? SCRUBBER : null);
        context.getLogger("test").info("as is: /Users/alice");
        on.set(true);
        context.getLogger("test").info("cleaned: /Users/alice");
        assertEquals("as is: /Users/alice\ncleaned: ~\n", out.toString());
    }

    @Test
    void stillCleansAfterLog4jReloadsItsConfiguration() {
        context.start(new TestConfiguration(context, new StringWriter()));
        LiveLogs.install(context, () -> SCRUBBER);
        StringWriter out = new StringWriter();
        context.setConfiguration(new TestConfiguration(context, out));
        context.getLogger("test").info("reloaded: /Users/alice");
        assertEquals("reloaded: ~\n", out.toString());
    }

    @Test
    void linesWrittenBeforeInstallAreMaskedInPlace(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("latest.log");
        String before = "[main/INFO]: Loading /Users/alice/mods\n[main/INFO]: Setting user: Wasabi_Fan\n[main/INFO]: café ☃\n";
        Files.writeString(file, before, StandardCharsets.UTF_8);
        long written = Files.size(file);
        String after = "[main/INFO]: written later by the appender, /Users/alice";
        Files.writeString(file, after, StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(2, LiveLogs.maskWritten(file, written, SCRUBBER));
        String masked = Files.readString(file);
        assertEquals("[main/INFO]: Loading ************/mods\n[main/INFO]: Setting user: **********\n[main/INFO]: café ☃\n" + after,
            masked);
        assertEquals(written + after.getBytes(StandardCharsets.UTF_8).length, Files.size(file), "nothing moved");
    }
}
