package spola.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;

/** A {@link System.Logger} for tests: records what is logged at or above a threshold. */
final class RecordingLogger implements System.Logger {

    /** One logged message. {@code params} is what the logger got as MessageFormat parameters. */
    record Entry(Level level, String message, Throwable thrown, Object[] params) {
    }

    final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
    volatile Level threshold;

    RecordingLogger(Level threshold) {
        this.threshold = threshold;
    }

    Entry only() {
        if (entries.size() != 1) {
            throw new AssertionError("expected one entry, got " + entries);
        }
        return entries.getFirst();
    }

    @Override
    public String getName() {
        return "recording";
    }

    @Override
    public boolean isLoggable(Level level) {
        return threshold != Level.OFF && level != Level.OFF && level.getSeverity() >= threshold.getSeverity();
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
        if (isLoggable(level)) {
            entries.add(new Entry(level, message, thrown, null));
        }
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        if (isLoggable(level)) {
            entries.add(new Entry(level, format, null, params));
        }
    }
}
