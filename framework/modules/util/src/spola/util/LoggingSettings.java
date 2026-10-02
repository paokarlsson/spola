package spola.util;

import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What {@link Logging#configure(LoggingSettings)} installs: levels per logger-name prefix,
 * where the lines are written and the time zone of the timestamps.
 *
 * {@snippet :
 * Logging.configure(LoggingSettings.defaults()
 *         .withLevel("com.example", Level.DEBUG)
 *         .withLevel("com.example.noisy", Level.WARNING));
 * }
 *
 * <h2>Prefixes</h2>
 * A prefix is a logger name or the start of one ending at a dot boundary: {@code "com.example"}
 * covers {@code com.example} and {@code com.example.Order}, but not {@code com.examples}.
 * The empty prefix {@code ""} is the root and covers every logger. A prefix must not start or
 * end with a dot or contain two dots in a row. The longest matching prefix wins. If the map
 * has no {@code ""} entry, the root level is {@link Level#INFO} (as in Spring Boot).
 *
 * @param levels the level per prefix; copied, sorted by prefix
 * @param output where the lines go; a {@link java.io.PrintStream} is written with its own
 *     charset, any other stream gets UTF-8. Never closed by {@code Logging}.
 * @param zone the time zone of the timestamps
 */
public record LoggingSettings(Map<String, Level> levels, OutputStream output, ZoneId zone) {

    /**
     * Validates and copies the levels.
     *
     * @throws IllegalArgumentException for an invalid prefix
     * @throws NullPointerException for a {@code null} prefix, level, output or zone
     */
    public LoggingSettings {
        Objects.requireNonNull(levels, "levels");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(zone, "zone");
        var copy = new TreeMap<String, Level>();
        levels.forEach((prefix, level) -> {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(level, () -> "level for prefix '" + prefix + "'");
            if (!prefix.isEmpty() && (prefix.startsWith(".") || prefix.endsWith(".") || prefix.contains(".."))) {
                throw new IllegalArgumentException("invalid logger-name prefix '" + prefix
                        + "': it must not start or end with '.' or contain '..' (use \"\" for the root)");
            }
            copy.put(prefix, level);
        });
        levels = Collections.unmodifiableMap(copy);
    }

    /**
     * The defaults: root level {@link Level#INFO}, output {@link System#err} (the stream that is
     * {@code System.err} when this is called), the system default time zone.
     *
     * @return the default settings
     */
    public static LoggingSettings defaults() {
        return new LoggingSettings(Map.of("", Level.INFO), System.err, ZoneId.systemDefault());
    }

    /**
     * These settings with all levels replaced.
     *
     * @param newLevels the level per prefix
     * @return new settings
     */
    public LoggingSettings withLevels(Map<String, Level> newLevels) {
        return new LoggingSettings(newLevels, output, zone);
    }

    /**
     * These settings with the level of one prefix added or replaced.
     *
     * @param prefix the logger-name prefix, {@code ""} for the root
     * @param level the level
     * @return new settings
     */
    public LoggingSettings withLevel(String prefix, Level level) {
        var copy = new TreeMap<>(levels);
        copy.put(Objects.requireNonNull(prefix, "prefix"), level);
        return new LoggingSettings(copy, output, zone);
    }

    /**
     * These settings writing to another stream.
     *
     * @param newOutput the stream; never closed by {@code Logging}
     * @return new settings
     */
    public LoggingSettings withOutput(OutputStream newOutput) {
        return new LoggingSettings(levels, newOutput, zone);
    }

    /**
     * These settings with timestamps in another time zone.
     *
     * @param newZone the time zone
     * @return new settings
     */
    public LoggingSettings withZone(ZoneId newZone) {
        return new LoggingSettings(levels, output, newZone);
    }
}
