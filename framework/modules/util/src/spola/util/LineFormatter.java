package spola.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The one-line format installed by {@link Logging}. See {@link Logging} for the form. Used only
 * by {@link LineHandler}, which formats in the thread that logs.
 */
final class LineFormatter extends Formatter {

    private final DateTimeFormatter time;

    LineFormatter(ZoneId zone) {
        this.time = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX").withZone(zone);
    }

    @Override
    public String format(LogRecord record) {
        var line = new StringBuilder(128);
        line.append(time.format(record.getInstant())).append(' ');
        var level = levelName(record.getLevel());
        line.append(level).append(" ".repeat(5 - level.length())).append(" [");
        escape(threadName(record), line);
        line.append("] ");
        escape(record.getLoggerName() == null ? "" : record.getLoggerName(), line);
        line.append(" : ");
        escape(formatMessage(record), line);
        line.append(System.lineSeparator());
        var thrown = record.getThrown();
        if (thrown != null) {
            line.append(stackTrace(thrown));
        }
        return line.toString();
    }

    /** The level as {@link Log} names it: TRACE, DEBUG, INFO, WARN or ERROR. */
    static String levelName(Level level) {
        int value = level == null ? Level.INFO.intValue() : level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        } else if (value >= Level.WARNING.intValue()) {
            return "WARN";
        } else if (value >= Level.INFO.intValue()) {
            return "INFO";
        } else if (value >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    private static String threadName(LogRecord record) {
        var current = Thread.currentThread();
        if (current.threadId() != record.getLongThreadID()) {
            return "thread-" + record.getLongThreadID();
        }
        if (!current.getName().isEmpty()) {
            return current.getName();
        }
        return (current.isVirtual() ? "virtual-" : "thread-") + current.threadId();
    }

    private static String stackTrace(Throwable thrown) {
        var text = new StringWriter();
        try (var writer = new PrintWriter(text)) {
            thrown.printStackTrace(writer);
        } catch (RuntimeException failure) {
            return MessageFormatter.marker("printStackTrace()", failure) + System.lineSeparator();
        }
        var trace = text.toString();
        return trace.endsWith("\n") ? trace : trace + System.lineSeparator();
    }

    /**
     * Appends {@code text} so that it cannot start a new line: line breaks and other control
     * characters (except tab) are written as escapes ({@code \n}, {@code \r},
     * {@code \}{@code uXXXX}), which keeps one record on one line and stops log forging.
     */
    static void escape(String text, StringBuilder out) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if ((Character.isISOControl(c) && c != '\t') || c == ' ' || c == ' ') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
    }
}
