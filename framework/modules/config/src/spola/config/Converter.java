package spola.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns the text of a configuration value into a typed value. A converter has a type name,
 * used in error messages ("expected Duration"), and a function that throws an unchecked
 * exception (or returns {@code null}) when the text is not a valid value.
 *
 * <p>The built-in converters trim surrounding whitespace before parsing, except
 * {@link #STRING}, which returns the text as written. Write your own with
 * {@code new Converter<>("Color", Color::parse)}.
 *
 * @param <T> the type of value
 * @param type the name of the type, shown in error messages
 * @param parse the conversion; throws (or returns {@code null}) for invalid text
 */
public record Converter<T>(String type, Function<String, ? extends T> parse) {

    /** The text as written. */
    public static final Converter<String> STRING = new Converter<>("String", text -> text);

    /** A decimal {@code int}, as {@link Integer#parseInt(String)} reads it. */
    public static final Converter<Integer> INT = new Converter<>("int", text -> Integer.parseInt(text.strip()));

    /** A decimal {@code long}, as {@link Long#parseLong(String)} reads it. */
    public static final Converter<Long> LONG = new Converter<>("long", text -> Long.parseLong(text.strip()));

    /** {@code true} or {@code false}, ignoring case. Anything else is invalid (unlike {@link Boolean#parseBoolean}). */
    public static final Converter<Boolean> BOOLEAN = new Converter<>("boolean", Converter::parseBoolean);

    /** A {@code double}, as {@link Double#parseDouble(String)} reads it. */
    public static final Converter<Double> DOUBLE = new Converter<>("double", text -> Double.parseDouble(text.strip()));

    /**
     * A duration: either ISO-8601 as {@link Duration#parse} reads it ({@code PT30S},
     * {@code P1DT2H}) or a whole number followed by one unit: {@code ns}, {@code us},
     * {@code ms}, {@code s}, {@code m}, {@code h} or {@code d} ({@code 500ms}, {@code 30s},
     * {@code -5m}). A number without a unit is invalid.
     */
    public static final Converter<Duration> DURATION = new Converter<>("Duration", Converter::parseDuration);

    /** A URI, as {@link java.net.URI#create(String)} reads it. */
    public static final Converter<java.net.URI> URI = new Converter<>("URI", text -> java.net.URI.create(text.strip()));

    /** A file system path, as {@link Path#of(String, String...)} reads it. */
    public static final Converter<Path> PATH = new Converter<>("Path", text -> Path.of(text.strip()));

    private static final Pattern SIMPLE_DURATION = Pattern.compile("([-+]?\\d+)(ns|us|ms|s|m|h|d)");

    /**
     * Checks that both parts are present.
     *
     * @param type the name of the type, not blank
     * @param parse the conversion
     */
    public Converter {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parse, "parse");
        if (type.isBlank()) {
            throw new IllegalArgumentException("a converter's type name must not be blank");
        }
    }

    /**
     * An enum constant, by its exact name, with {@link Enum#valueOf(Class, String)} (no other
     * reflection). The type name in error messages is the enum's simple name.
     *
     * @param <E> the enum type
     * @param type the enum class
     * @return the converter
     */
    public static <E extends Enum<E>> Converter<E> forEnum(Class<E> type) {
        Objects.requireNonNull(type, "type");
        return new Converter<>(type.getSimpleName(), text -> Enum.valueOf(type, text.strip()));
    }

    /**
     * Converts the text.
     *
     * @param text the text
     * @return the value, never {@code null}
     * @throws IllegalArgumentException (or another unchecked exception) if the text is not a
     *     valid value
     */
    public T convert(String text) {
        T value = parse.apply(text);
        if (value == null) {
            throw new IllegalArgumentException("the converter returned null");
        }
        return value;
    }

    private static Boolean parseBoolean(String text) {
        var stripped = text.strip();
        if (stripped.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (stripped.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("not true or false");
    }

    private static Duration parseDuration(String text) {
        var stripped = text.strip();
        var simple = SIMPLE_DURATION.matcher(stripped);
        if (simple.matches()) {
            var amount = Long.parseLong(simple.group(1));
            return switch (simple.group(2)) {
                case "ns" -> Duration.ofNanos(amount);
                case "us" -> Duration.ofNanos(Math.multiplyExact(amount, 1_000L));
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                case "d" -> Duration.ofDays(amount);
                default -> throw new IllegalStateException("unreachable");
            };
        }
        var upper = stripped.toUpperCase(Locale.ROOT);
        if (upper.startsWith("P") || upper.startsWith("-P") || upper.startsWith("+P")) {
            try {
                return Duration.parse(stripped);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("not an ISO-8601 duration", e);
            }
        }
        throw new IllegalArgumentException("not a duration (use e.g. 30s, 500ms, 5m or PT30S)");
    }
}
