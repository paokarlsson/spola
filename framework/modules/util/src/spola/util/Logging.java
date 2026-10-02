package spola.util;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs Spola's console logging on the {@code java.util.logging} root logger, which is where
 * {@link System.Logger} (and so {@link Log}) writes by default. Replaces Spring Boot's default
 * console format and its {@code logging.level.*} properties.
 *
 * {@snippet :
 * Logging.configure(LoggingSettings.defaults()
 *         .withLevel("", System.Logger.Level.INFO)
 *         .withLevel("com.example", System.Logger.Level.DEBUG));
 * }
 *
 * <h2>Explicit</h2>
 * Nothing happens until {@link #configure(LoggingSettings)} is called: loading this class or
 * using a {@link Log} configures nothing. Until then the JDK's defaults apply.
 *
 * <h2>What {@code configure} does</h2>
 * <ol>
 *   <li>Removes <em>every</em> handler from the root logger (the JDK's default
 *       {@code ConsoleHandler}, the handler of an earlier {@code configure}, any other) so that
 *       no line is written twice. Handlers it did not install are removed but not closed (they
 *       belong to someone else).</li>
 *   <li>Undoes the levels an earlier {@code configure} set: each logger it touched gets back
 *       the level it had before Spola first touched it.</li>
 *   <li>Sets the root logger's level to the {@code ""} entry (or {@link System.Logger.Level#INFO}
 *       if there is none) and the level of the logger named by each other prefix. Since
 *       {@code java.util.logging} loggers inherit the level of their nearest ancestor by dotted
 *       name, the longest matching prefix wins and a prefix only matches whole name segments
 *       ({@code com.ex} does not cover {@code com.example}). The loggers are held strongly, so
 *       their levels are not lost when the JDK would otherwise garbage-collect them.</li>
 *   <li>Adds one handler that writes every record it gets as one line.</li>
 * </ol>
 * Calling it again replaces the previous configuration completely. The levels are real logger
 * levels, so {@link System.Logger#isLoggable} (and {@link Log#isDebugEnabled()} and so on)
 * answer {@code false} for a level that is off, and no work is done for it. A level set on a
 * logger by someone else (for example in a {@code logging.properties} file) is left alone and,
 * for that logger and its children, takes precedence over a shorter prefix.
 *
 * <h2>Levels</h2>
 * {@link System.Logger.Level} maps to {@code java.util.logging.Level} as the JDK maps it:
 * {@code ALL}&rarr;{@code ALL}, {@code TRACE}&rarr;{@code FINER}, {@code DEBUG}&rarr;{@code FINE},
 * {@code INFO}&rarr;{@code INFO}, {@code WARNING}&rarr;{@code WARNING},
 * {@code ERROR}&rarr;{@code SEVERE}, {@code OFF}&rarr;{@code OFF}.
 *
 * <h2>Line format</h2>
 * <pre>{@code
 * 2026-10-02T07:51:03.042+02:00 INFO  [main] com.example.OrderService : order 42 placed
 * 2026-10-02T07:51:03.050+02:00 ERROR [virtual-31] com.example.Payment : payment failed
 * java.io.IOException: connection reset
 *     at com.example.Payment.charge(Payment.java:17)
 * }</pre>
 * Timestamp (ISO-8601 with milliseconds and offset, in the configured zone), level padded to
 * five characters ({@code TRACE}, {@code DEBUG}, {@code INFO}, {@code WARN}, {@code ERROR};
 * {@code java.util.logging} levels in between round down, so {@code CONFIG} is {@code DEBUG}
 * and {@code FINEST} is {@code TRACE}), thread name in brackets (an unnamed virtual thread is
 * {@code virtual-<id>}), logger name, {@code " : "}, message. The stack trace of an exception
 * follows on the next lines. Line breaks and other control characters in the message, thread
 * name and logger name are escaped ({@code \n}, {@code \r}, {@code \}{@code uXXXX}), so a
 * record is always one line and a logged value cannot forge extra lines. Each line is written
 * and flushed as soon as it is logged.
 *
 * <h2>Threads</h2>
 * {@code configure} and {@link #reset()} are synchronized with each other. Logging while they
 * run is safe, but a record logged in the middle of a {@code configure} may be lost.
 */
public final class Logging {

    private static final Object LOCK = new Object();

    /** The handler installed by the last {@code configure}; guarded by {@link #LOCK}. */
    private static LineHandler installed;

    /** Loggers whose level Spola set, with the level each had before; guarded by {@link #LOCK}. */
    private static final Map<Logger, Level> touched = new IdentityHashMap<>();

    /** The root logger's handlers and level before the first {@code configure}; guarded by {@link #LOCK}. */
    private static List<Handler> originalHandlers;
    private static Level originalRootLevel;

    private Logging() {
    }

    /**
     * Installs the one-line console format and the levels, replacing any earlier
     * configuration. See the class documentation for exactly what changes.
     *
     * @param settings levels, output and time zone
     */
    public static void configure(LoggingSettings settings) {
        Objects.requireNonNull(settings, "settings");
        var handler = new LineHandler(settings.output(), new LineFormatter(settings.zone()));
        synchronized (LOCK) {
            var root = Logger.getLogger("");
            if (originalHandlers == null) {
                originalHandlers = List.of(root.getHandlers());
                originalRootLevel = root.getLevel();
            }
            for (var old : root.getHandlers()) {
                root.removeHandler(old);
            }
            if (installed != null) {
                installed.close();
            }
            restoreTouchedLevels();
            settings.levels().forEach((prefix, level) -> {
                if (!prefix.isEmpty()) {
                    var logger = Logger.getLogger(prefix);
                    touched.putIfAbsent(logger, logger.getLevel());
                    logger.setLevel(julLevel(level));
                }
            });
            root.setLevel(julLevel(settings.levels().getOrDefault("", System.Logger.Level.INFO)));
            root.addHandler(handler);
            installed = handler;
        }
    }

    /**
     * Undoes {@link #configure(LoggingSettings)}: removes Spola's handler, gives every logger
     * Spola touched its earlier level back, and restores the root logger's handlers and level
     * from before the first {@code configure}. Does nothing if {@code configure} has not been
     * called (or {@code reset} already was). Mostly for tests.
     */
    public static void reset() {
        synchronized (LOCK) {
            if (originalHandlers == null) {
                return;
            }
            var root = Logger.getLogger("");
            if (installed != null) {
                root.removeHandler(installed);
                installed.close();
                installed = null;
            }
            restoreTouchedLevels();
            var present = List.of(root.getHandlers());
            for (var handler : originalHandlers) {
                if (!present.contains(handler)) {
                    root.addHandler(handler);
                }
            }
            root.setLevel(originalRootLevel);
            originalHandlers = null;
            originalRootLevel = null;
        }
    }

    private static void restoreTouchedLevels() {
        for (var entry : new ArrayList<>(touched.entrySet())) {
            entry.getKey().setLevel(entry.getValue());
        }
        touched.clear();
    }

    /**
     * The {@code java.util.logging} level for a {@link System.Logger.Level}, as the JDK maps it.
     *
     * @param level the level
     * @return the {@code java.util.logging} level
     */
    static Level julLevel(System.Logger.Level level) {
        return switch (level) {
            case ALL -> Level.ALL;
            case TRACE -> Level.FINER;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARNING -> Level.WARNING;
            case ERROR -> Level.SEVERE;
            case OFF -> Level.OFF;
        };
    }
}
