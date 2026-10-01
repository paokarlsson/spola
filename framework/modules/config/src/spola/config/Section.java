package spola.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The view of a {@link Config} that a binding function reads from, under a prefix. Every read
 * method takes a key relative to the prefix ({@code "pool.size"} under prefix {@code "db"} is
 * {@code db.pool.size}).
 *
 * <p>A read never throws for a missing key or an invalid value. It records the problem
 * ({@link ConfigProblem.MissingKey} or {@link ConfigProblem.InvalidValue}, with the key, the
 * expected type and, for an invalid value, the source) and returns a stand-in: {@code null}
 * for objects, {@code 0} or {@code false} for primitives, an empty list or {@code Optional},
 * or the default if one was given. {@link Config#bind} throws every recorded problem in one
 * {@link ConfigException} when the binding function returns. Methods with a default return
 * the default when the key is missing; an invalid value is still a problem.
 *
 * <p>A section is valid only while its binding function runs; using it afterwards throws
 * {@link IllegalStateException}.
 */
public final class Section {

    private final Config config;
    private final String prefix;
    private final List<ConfigProblem> problems;
    private volatile boolean closed;

    Section(Config config, String prefix, List<ConfigProblem> problems) {
        this.config = config;
        this.prefix = prefix;
        this.problems = problems;
    }

    void close() {
        closed = true;
    }

    /**
     * The prefix of this section, empty for the root.
     *
     * @return the prefix
     */
    public String prefix() {
        return prefix;
    }

    /**
     * The full key for a key relative to this section.
     *
     * @param key the relative key
     * @return {@code prefix + "." + key}, or {@code key} for the root
     */
    public String fullKey(String key) {
        Source.checkKey(key);
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    /**
     * Binds a nested value under a sub-prefix with the same mechanism, collecting its problems
     * into the same report:
     * {@code c.section("pool", p -> new Pool(p.intValue("size"), p.duration("timeout")))}.
     * If the nested function throws after problems were recorded, the stand-in {@code null}
     * is returned and the binding continues; otherwise its exception propagates.
     *
     * @param <T> the type of the nested value
     * @param subPrefix the sub-prefix, relative to this section
     * @param binder the function that builds the nested value
     * @return what the function returned, or {@code null} (see above)
     */
    public <T> T section(String subPrefix, Function<? super Section, ? extends T> binder) {
        checkOpen();
        Objects.requireNonNull(binder, "binder");
        var nested = new Section(config, fullKey(subPrefix), problems);
        try {
            return binder.apply(nested);
        } catch (RuntimeException e) {
            if (problems.isEmpty()) {
                throw e;
            }
            return null;
        } finally {
            nested.close();
        }
    }

    /**
     * Whether a key has a value.
     *
     * @param key the relative key
     * @return whether any source has the key
     */
    public boolean has(String key) {
        checkOpen();
        return config.entry(fullKey(key)) != null;
    }

    /**
     * A required value converted with a converter, built in or the application's own.
     *
     * @param <T> the type
     * @param key the relative key
     * @param converter the converter
     * @return the value, or {@code null} after recording a problem
     */
    public <T> T value(String key, Converter<T> converter) {
        return read(key, converter, null, true);
    }

    /**
     * A value converted with a converter, or a default if the key is missing.
     *
     * @param <T> the type
     * @param key the relative key
     * @param converter the converter
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public <T> T value(String key, Converter<T> converter, T defaultValue) {
        return read(key, converter, defaultValue, false);
    }

    /**
     * A required value converted with the application's own function, e.g.
     * {@code c.value("color", "Color", Color::parse)}.
     *
     * @param <T> the type
     * @param key the relative key
     * @param type the type name shown in error messages
     * @param convert the conversion; throws or returns {@code null} for invalid text
     * @return the value, or {@code null} after recording a problem
     */
    public <T> T value(String key, String type, Function<String, ? extends T> convert) {
        return value(key, new Converter<T>(type, convert));
    }

    /**
     * A required string, as written (after placeholders are resolved).
     *
     * @param key the relative key
     * @return the value, or {@code null} after recording a problem
     */
    public String string(String key) {
        return value(key, Converter.STRING);
    }

    /**
     * A string, or a default.
     *
     * @param key the relative key
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public String string(String key, String defaultValue) {
        return value(key, Converter.STRING, defaultValue);
    }

    /**
     * A required {@code int} ({@link Converter#INT}).
     *
     * @param key the relative key
     * @return the value, or {@code 0} after recording a problem
     */
    public int intValue(String key) {
        return unbox(value(key, Converter.INT), 0);
    }

    /**
     * An {@code int}, or a default.
     *
     * @param key the relative key
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public int intValue(String key, int defaultValue) {
        return unbox(value(key, Converter.INT, defaultValue), defaultValue);
    }

    /**
     * A required {@code long} ({@link Converter#LONG}).
     *
     * @param key the relative key
     * @return the value, or {@code 0} after recording a problem
     */
    public long longValue(String key) {
        return unbox(value(key, Converter.LONG), 0L);
    }

    /**
     * A {@code long}, or a default.
     *
     * @param key the relative key
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public long longValue(String key, long defaultValue) {
        return unbox(value(key, Converter.LONG, defaultValue), defaultValue);
    }

    /**
     * A required {@code boolean} ({@link Converter#BOOLEAN}: {@code true} or {@code false}).
     *
     * @param key the relative key
     * @return the value, or {@code false} after recording a problem
     */
    public boolean booleanValue(String key) {
        return unbox(value(key, Converter.BOOLEAN), false);
    }

    /**
     * A {@code boolean}, or a default.
     *
     * @param key the relative key
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public boolean booleanValue(String key, boolean defaultValue) {
        return unbox(value(key, Converter.BOOLEAN, defaultValue), defaultValue);
    }

    /**
     * A required {@code double} ({@link Converter#DOUBLE}).
     *
     * @param key the relative key
     * @return the value, or {@code 0} after recording a problem
     */
    public double doubleValue(String key) {
        return unbox(value(key, Converter.DOUBLE), 0.0);
    }

    /**
     * A {@code double}, or a default.
     *
     * @param key the relative key
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public double doubleValue(String key, double defaultValue) {
        return unbox(value(key, Converter.DOUBLE, defaultValue), defaultValue);
    }

    /**
     * A required {@link Duration} ({@link Converter#DURATION}: {@code 30s}, {@code 500ms},
     * {@code PT30S}, ...).
     *
     * @param key the relative key
     * @return the value, or {@code null} after recording a problem
     */
    public Duration duration(String key) {
        return value(key, Converter.DURATION);
    }

    /**
     * A {@link Duration}, or a default.
     *
     * @param key the relative key
     * @param defaultValue returned when no source has the key
     * @return the value or the default
     */
    public Duration duration(String key, Duration defaultValue) {
        return value(key, Converter.DURATION, defaultValue);
    }

    /**
     * A required {@link java.net.URI}.
     *
     * @param key the relative key
     * @return the value, or {@code null} after recording a problem
     */
    public java.net.URI uri(String key) {
        return value(key, Converter.URI);
    }

    /**
     * A required {@link Path}.
     *
     * @param key the relative key
     * @return the value, or {@code null} after recording a problem
     */
    public Path path(String key) {
        return value(key, Converter.PATH);
    }

    /**
     * A required enum constant, by its exact name ({@link Converter#forEnum}).
     *
     * @param <E> the enum type
     * @param key the relative key
     * @param type the enum class
     * @return the constant, or {@code null} after recording a problem
     */
    public <E extends Enum<E>> E enumValue(String key, Class<E> type) {
        return value(key, Converter.forEnum(type));
    }

    /**
     * An enum constant, or a default.
     *
     * @param <E> the enum type
     * @param key the relative key
     * @param type the enum class
     * @param defaultValue returned when no source has the key
     * @return the constant or the default
     */
    public <E extends Enum<E>> E enumValue(String key, Class<E> type, E defaultValue) {
        return value(key, Converter.forEnum(type), defaultValue);
    }

    /**
     * A required comma-separated list of strings. Each element is trimmed; an empty value is
     * an empty list; an empty element ({@code a,,b}) is invalid. Elements cannot contain
     * commas.
     *
     * @param key the relative key
     * @return the list (unmodifiable), or an empty list after recording a problem
     */
    public List<String> list(String key) {
        return list(key, Converter.STRING);
    }

    /**
     * A required comma-separated list, each trimmed element converted with a converter. If any
     * element is invalid, the whole value is one {@link ConfigProblem.InvalidValue} that names
     * the element.
     *
     * @param <T> the element type
     * @param key the relative key
     * @param element the converter for each element
     * @return the list (unmodifiable), or an empty list after recording a problem
     */
    public <T> List<T> list(String key, Converter<T> element) {
        var result = value(key, listOf(element));
        return result == null ? List.of() : result;
    }

    /**
     * A comma-separated list, or a default.
     *
     * @param <T> the element type
     * @param key the relative key
     * @param element the converter for each element
     * @param defaultValue returned when no source has the key
     * @return the list or the default
     */
    public <T> List<T> list(String key, Converter<T> element, List<T> defaultValue) {
        var result = value(key, listOf(element), defaultValue);
        return result == null ? List.of() : result;
    }

    /**
     * An optional string: empty if no source has the key.
     *
     * @param key the relative key
     * @return the value, if any
     */
    public Optional<String> optional(String key) {
        return optional(key, Converter.STRING);
    }

    /**
     * An optional value converted with a converter: empty if no source has the key. A present
     * but invalid value is still a problem.
     *
     * @param <T> the type
     * @param key the relative key
     * @param converter the converter
     * @return the value, if any; empty after recording a problem
     */
    public <T> Optional<T> optional(String key, Converter<T> converter) {
        return Optional.ofNullable(read(key, converter, null, false));
    }

    private static <T> Converter<List<T>> listOf(Converter<T> element) {
        Objects.requireNonNull(element, "element");
        return new Converter<>("list of " + element.type(), text -> {
            if (text.isBlank()) {
                return List.of();
            }
            var parts = text.split(",", -1);
            var values = new ArrayList<T>(parts.length);
            for (var i = 0; i < parts.length; i++) {
                var part = parts[i].strip();
                if (part.isEmpty()) {
                    throw new IllegalArgumentException("element " + i + " is empty");
                }
                try {
                    values.add(element.convert(part));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("element " + i + " is not a valid " + element.type()
                            + detail(e), e);
                }
            }
            return List.copyOf(values);
        });
    }

    private static String detail(RuntimeException e) {
        return e.getMessage() == null ? "" : ": " + e.getMessage();
    }

    private <T> T read(String key, Converter<T> converter, T defaultValue, boolean required) {
        checkOpen();
        Objects.requireNonNull(converter, "converter");
        var full = fullKey(key);
        var entry = config.entry(full);
        if (entry == null) {
            if (required) {
                problems.add(new ConfigProblem.MissingKey(full, converter.type()));
            }
            return defaultValue;
        }
        try {
            return converter.convert(entry.value());
        } catch (RuntimeException e) {
            problems.add(entry.sensitive()
                    ? new ConfigProblem.InvalidValue(full, converter.type(), entry.source(), Config.MASK, "")
                    : new ConfigProblem.InvalidValue(full, converter.type(), entry.source(),
                            Config.printable(entry.value()), e.getMessage() == null
                                    ? e.getClass().getSimpleName() : e.getMessage()));
            return defaultValue;
        }
    }

    private static <T> T unbox(T value, T standIn) {
        return value == null ? standIn : value;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("this section belongs to a binding that has finished;"
                    + " read values inside the binding function");
        }
    }
}
