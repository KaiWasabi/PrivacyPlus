package org.kaiwasabi.privacyplus.logs;

import java.io.IOException;
import java.io.Serial;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AbstractOutputStreamAppender;
import org.apache.logging.log4j.core.appender.FileManager;
import org.apache.logging.log4j.core.appender.RandomAccessFileManager;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.impl.LocationAware;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.jspecify.annotations.Nullable;

// Cleans log lines as they are written. Minecraft, Fabric and Forge all log through log4j2, so every
// appender (latest.log, debug.log, the console the launcher reads) is put behind a CleaningAppender
// that hands it a cleaned copy of each event. The appenders keep their own layouts and filters, so the
// files look exactly as before. Re-applied when log4j reloads its configuration.
final class LiveLogs {
    private LiveLogs() {
    }

    // Wraps the appenders of every log4j context and returns the log files they write to. The supplier
    // gives the scrubber to use, or null to pass events through untouched (cleanLogs switched off).
    static Set<Path> install(Supplier<@Nullable LogScrubber> scrubber) {
        Set<LoggerContext> contexts = Collections.newSetFromMap(new IdentityHashMap<>());
        if (LogManager.getFactory() instanceof Log4jContextFactory factory) {
            contexts.addAll(factory.getSelector().getLoggerContexts());
        }
        if (LogManager.getContext(false) instanceof LoggerContext current) {
            contexts.add(current);
        }
        Set<Path> files = new LinkedHashSet<>();
        for (LoggerContext context : contexts) {
            files.addAll(install(context, scrubber));
        }
        return files;
    }

    static Set<Path> install(LoggerContext context, Supplier<@Nullable LogScrubber> scrubber) {
        Set<Path> files = wrap(context.getConfiguration(), scrubber);
        context.addPropertyChangeListener(event -> {
            if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName()) && event.getNewValue() instanceof Configuration config) {
                wrap(config, scrubber);
            }
        });
        return files;
    }

    // Swaps each appender a logger config sends to for a CleaningAppender around it, once per appender
    private static Set<Path> wrap(Configuration config, Supplier<@Nullable LogScrubber> scrubber) {
        Map<Appender, CleaningAppender> wrappers = new IdentityHashMap<>();
        Set<LoggerConfig> loggerConfigs = Collections.newSetFromMap(new IdentityHashMap<>());
        loggerConfigs.addAll(config.getLoggers().values());
        loggerConfigs.add(config.getRootLogger());
        Set<Path> files = new LinkedHashSet<>();
        for (LoggerConfig loggerConfig : loggerConfigs) {
            Map<String, AppenderRef> refs = new LinkedHashMap<>();
            loggerConfig.getAppenderRefs().forEach(ref -> refs.put(ref.getRef(), ref));
            for (Map.Entry<String, Appender> entry : new ArrayList<>(loggerConfig.getAppenders().entrySet())) {
                Appender appender = entry.getValue();
                if (appender instanceof CleaningAppender cleaning) {
                    addFile(files, cleaning.delegate);
                    continue;
                }
                AppenderRef ref = refs.get(entry.getKey());
                Filter filter = ref == null ? null : ref.getFilter();
                CleaningAppender wrapper = wrappers.computeIfAbsent(appender, a -> new CleaningAppender(a, scrubber));
                loggerConfig.removeAppender(entry.getKey());
                loggerConfig.addAppender(wrapper, ref == null ? null : ref.getLevel(), filter);
                // removeAppender stops the reference's filter, which is still in use
                if (filter != null && filter.isStopped()) {
                    filter.start();
                }
                addFile(files, appender);
            }
        }
        return files;
    }

    private static void addFile(Set<Path> files, Appender appender) {
        if (appender instanceof AbstractOutputStreamAppender<?> stream) {
            String name = null;
            if (stream.getManager() instanceof FileManager file) {
                name = file.getFileName();
            } else if (stream.getManager() instanceof RandomAccessFileManager file) {
                name = file.getFileName();
            }
            if (name != null) {
                files.add(Path.of(name).toAbsolutePath().normalize());
            }
        }
    }

    // What the last event cleaned on this thread became. Each event goes to every appender in turn on the
    // thread that logged it, so this saves cleaning it again for each of them.
    private record Cleaned(LogScrubber scrubber, @Nullable String text, @Nullable Throwable thrown, @Nullable String cleanText,
        @Nullable Throwable cleanThrown) {
    }

    private static final ThreadLocal<@Nullable Cleaned> LAST = new ThreadLocal<>();

    // A copy of the event with the message and exception text cleaned; the event itself when nothing changes
    static LogEvent clean(LogEvent event, LogScrubber scrubber) {
        Message message = event.getMessage();
        String text = message == null ? null : message.getFormattedMessage();
        Throwable thrown = event.getThrown();
        Cleaned last = LAST.get();
        if (last == null || last.scrubber() != scrubber || last.thrown() != thrown || !Objects.equals(last.text(), text)) {
            last = new Cleaned(scrubber, text, thrown, text == null ? null : scrubber.clean(text),
                thrown == null ? null : CleanedThrowable.of(thrown, scrubber));
            LAST.set(last);
        }
        String cleanText = last.cleanText();
        Throwable cleanThrown = last.cleanThrown();
        if (Objects.equals(text, cleanText) && thrown == cleanThrown) {
            return event;
        }
        Log4jLogEvent.Builder copy = new Log4jLogEvent.Builder(event);
        if (!Objects.equals(text, cleanText)) {
            copy.setMessage(new SimpleMessage(cleanText));
        }
        if (thrown != cleanThrown) {
            copy.setThrown(cleanThrown);
        }
        return copy.build();
    }

    // Lines written before install (loader start-up) are cleaned in the file itself. Each match is
    // replaced by stars of the same byte length, so nothing moves under the appender still writing to
    // the end of the file. Only the first `length` bytes are touched, and only whole lines.
    static int maskWritten(Path file, long length, LogScrubber scrubber) throws IOException {
        int masked = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long limit = Math.min(length, channel.size());
            // Start-up output is small; anything bigger isn't worth holding in memory
            if (limit <= 0 || limit > 64L << 20) {
                return 0;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) limit);
            int read = 0;
            while (buffer.hasRemaining() && read >= 0) {
                read = channel.read(buffer, buffer.position());
            }
            byte[] bytes = buffer.array();
            int filled = buffer.position();
            int lineStart = 0;
            for (int i = 0; i < filled; i++) {
                if (bytes[i] != '\n') {
                    continue;
                }
                int lineLength = i - lineStart;
                Charset charset = decodes(bytes, lineStart, lineLength) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1;
                String line = new String(bytes, lineStart, lineLength, charset);
                String clean = scrubber.mask(line, charset);
                if (!clean.equals(line)) {
                    byte[] replacement = clean.getBytes(charset);
                    if (replacement.length == lineLength) {
                        channel.write(ByteBuffer.wrap(replacement), lineStart);
                        masked++;
                    }
                }
                lineStart = i + 1;
            }
        }
        return masked;
    }

    private static boolean decodes(byte[] bytes, int offset, int length) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, offset, length));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    // Stands in for an appender in the logger configs and passes it cleaned events. Not registered in
    // the configuration itself, so lookups by name still find the original appender.
    static final class CleaningAppender extends AbstractAppender implements LocationAware {
        final Appender delegate;
        private final AppenderControl control;
        private final Supplier<@Nullable LogScrubber> scrubber;

        CleaningAppender(Appender delegate, Supplier<@Nullable LogScrubber> scrubber) {
            super(delegate.getName(), null, delegate.getLayout(), delegate.ignoreExceptions(), Property.EMPTY_ARRAY);
            this.delegate = delegate;
            // Applies the appender's own filter and recursion guard, as the logger config would
            this.control = new AppenderControl(delegate, null, null);
            this.scrubber = scrubber;
            start();
        }

        @Override
        public void append(LogEvent event) {
            LogEvent out = event;
            try {
                LogScrubber current = scrubber.get();
                if (current != null) {
                    out = clean(event, current);
                }
            } catch (RuntimeException | LinkageError e) {
                // Never lose a log line over cleaning it; log4j's status logger reports the problem
                error("PrivacyPlus couldn't clean a log event", event, e);
            }
            control.callAppender(out);
        }

        // The appender may print the file and line an event came from
        @Override
        public boolean requiresLocation() {
            return delegate instanceof LocationAware aware && aware.requiresLocation();
        }
    }

    // Prints like the original exception (class name, cleaned message, the same stack trace, causes and
    // suppressed exceptions) for layouts that render exceptions with toString() and printStackTrace(),
    // which is what PatternLayout and Minecraft's launcher XML layout do.
    static final class CleanedThrowable extends Throwable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final String text;
        private final @Nullable String localizedMessage;

        private CleanedThrowable(String text, @Nullable String message, @Nullable String localizedMessage) {
            super(message);
            this.text = text;
            this.localizedMessage = localizedMessage;
        }

        // The throwable itself when no message in it (or its causes) has anything to clean
        static Throwable of(Throwable thrown, LogScrubber scrubber) {
            if (!needsCleaning(thrown, scrubber, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                return thrown;
            }
            return copy(thrown, scrubber, new IdentityHashMap<>());
        }

        private static boolean needsCleaning(Throwable t, LogScrubber scrubber, Set<Throwable> seen) {
            if (!seen.add(t)) {
                return false;
            }
            String text = String.valueOf(t);
            if (!scrubber.clean(text).equals(text)) {
                return true;
            }
            Throwable cause = t.getCause();
            if (cause != null && needsCleaning(cause, scrubber, seen)) {
                return true;
            }
            for (Throwable suppressed : t.getSuppressed()) {
                if (needsCleaning(suppressed, scrubber, seen)) {
                    return true;
                }
            }
            return false;
        }

        private static Throwable copy(Throwable original, LogScrubber scrubber, Map<Throwable, Throwable> copies) {
            Throwable done = copies.get(original);
            if (done != null) {
                return done;
            }
            CleanedThrowable copy = new CleanedThrowable(scrubber.clean(String.valueOf(original)),
                cleanOrNull(original.getMessage(), scrubber), cleanOrNull(original.getLocalizedMessage(), scrubber));
            copies.put(original, copy);
            copy.setStackTrace(original.getStackTrace());
            Throwable cause = original.getCause();
            if (cause != null && cause != original) {
                copy.initCause(copy(cause, scrubber, copies));
            }
            for (Throwable suppressed : original.getSuppressed()) {
                Throwable cleaned = copy(suppressed, scrubber, copies);
                if (cleaned != copy) {
                    copy.addSuppressed(cleaned);
                }
            }
            return copy;
        }

        private static @Nullable String cleanOrNull(@Nullable String text, LogScrubber scrubber) {
            return text == null ? null : scrubber.clean(text);
        }

        // The stack trace is copied from the original; don't capture this one's
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }

        @Override
        public @Nullable String getLocalizedMessage() {
            return localizedMessage;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
