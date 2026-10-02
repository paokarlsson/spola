package spola.json.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import spola.json.JsonBoolean;
import spola.json.JsonNumber;
import spola.json.JsonString;
import spola.json.JsonValue;

/**
 * The built-in codecs and the combinators. Import them statically:
 * {@code import static spola.json.codec.JsonCodecs.*;}.
 *
 * <h2>Scalars: no coercion</h2>
 * Each scalar codec accepts exactly one JSON type and never converts silently: {@code "42"} is
 * not an {@link #INT}, {@code 42} is not a {@link #STRING}, {@code true} is not a string, and
 * {@code null} is not a value of any of them (wrap the codec in {@link #nullable(JsonCodec)} or
 * use an optional field). A number must fit the type exactly: {@code 1.0} and {@code 1e2} are
 * {@code int}s, {@code 1.5} and {@code 2147483648} are not, and a {@code double} out of range is
 * a problem, not an infinity or a zero.
 *
 * <table class="striped">
 *   <caption>Built-in codecs</caption>
 *   <thead><tr><th>Codec</th><th>Java</th><th>JSON</th></tr></thead>
 *   <tbody>
 *   <tr><td>{@link #STRING}</td><td>{@code String}</td><td>string</td></tr>
 *   <tr><td>{@link #BOOLEAN}</td><td>{@code Boolean}</td><td>{@code true}, {@code false}</td></tr>
 *   <tr><td>{@link #INT}, {@link #LONG}</td><td>{@code Integer}, {@code Long}</td><td>number with an integer value in range</td></tr>
 *   <tr><td>{@link #DOUBLE}</td><td>{@code Double}</td><td>number within the range of {@code double}; written finite only</td></tr>
 *   <tr><td>{@link #BIG_DECIMAL}</td><td>{@code BigDecimal}</td><td>number with a scale within &plusmn;10 000</td></tr>
 *   <tr><td>{@link #BIG_INTEGER}</td><td>{@code BigInteger}</td><td>number with an integer value of at most 10 000 digits</td></tr>
 *   <tr><td>{@link #UUID}</td><td>{@code UUID}</td><td>string {@code 8-4-4-4-12} hex digits</td></tr>
 *   <tr><td>{@link #INSTANT}, {@link #LOCAL_DATE}, {@link #LOCAL_TIME}, {@link #LOCAL_DATE_TIME},
 *       {@link #OFFSET_DATE_TIME}, {@link #DURATION}</td><td>{@code java.time}</td>
 *       <td>ISO-8601 string, as the type's {@code toString} and {@code parse}</td></tr>
 *   <tr><td>{@link #JSON}</td><td>{@code JsonValue}</td><td>any value, as it is</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>Dates and times are ISO-8601 strings, like Spring Boot's default
 * ({@code WRITE_DATES_AS_TIMESTAMPS=false}); invalid text (such as {@code 2026-02-30}) is a
 * problem, never a {@code DateTimeParseException}.
 */
public final class JsonCodecs {

    private JsonCodecs() {
    }

    /** {@link JsonNumber#MAX_EXACT_DIGITS}: the bound on digits for {@link #BIG_INTEGER} and on the scale of {@link #BIG_DECIMAL}. */
    private static final int MAX_DIGITS = JsonNumber.MAX_EXACT_DIGITS;

    /** The smallest integer with more than {@link #MAX_DIGITS} digits. */
    private static final BigInteger TOO_MANY_DIGITS = BigInteger.TEN.pow(MAX_DIGITS);

    private static final JsonNumber ZERO = JsonNumber.of(0);

    /** A JSON string as a {@code String}. Writing a string with an unpaired surrogate fails. */
    public static final JsonCodec<String> STRING = new ScalarCodec<>(
            json -> json instanceof JsonString s ? s.value() : reject(Messages.expected("string", json)),
            value -> {
                try {
                    return JsonString.of(value);
                } catch (IllegalArgumentException e) {
                    throw new ScalarCodec.Rejected("the string has no JSON form: " + Messages.describe(e));
                }
            });

    /** {@code true} or {@code false} as a {@code Boolean}. */
    public static final JsonCodec<Boolean> BOOLEAN = new ScalarCodec<>(
            json -> json instanceof JsonBoolean b ? b.value() : reject(Messages.expected("boolean", json)),
            JsonBoolean::of);

    /** A number with an integer value in the range of {@code int}: {@code 42}, {@code 1.0}, {@code 1e2}. */
    public static final JsonCodec<Integer> INT = new ScalarCodec<>(
            json -> {
                var n = number(json, "int");
                try {
                    return n.intValueExact();
                } catch (ArithmeticException e) {
                    throw notExact(n, "int");
                }
            },
            value -> JsonNumber.of(value));

    /** A number with an integer value in the range of {@code long}. */
    public static final JsonCodec<Long> LONG = new ScalarCodec<>(
            json -> {
                var n = number(json, "long");
                try {
                    return n.longValueExact();
                } catch (ArithmeticException e) {
                    throw notExact(n, "long");
                }
            },
            value -> JsonNumber.of(value));

    /**
     * A number as the nearest {@code double}. A number whose magnitude is too large (it would
     * be an infinity) or too small (a non-zero number that would be zero) is a problem. Writing
     * {@code NaN} or an infinity fails: JSON has no such numbers.
     */
    public static final JsonCodec<Double> DOUBLE = new ScalarCodec<>(
            json -> {
                var n = number(json, "double");
                double value = n.doubleValue();
                if (Double.isInfinite(value)) {
                    throw new ScalarCodec.Rejected("expected double but found " + Messages.number(n)
                            + " (outside the range of double)");
                }
                if (value == 0 && !ZERO.equals(n)) {
                    throw new ScalarCodec.Rejected("expected double but found " + Messages.number(n)
                            + " (too small for double; it would be 0)");
                }
                return value;
            },
            value -> {
                if (!Double.isFinite(value)) {
                    throw new ScalarCodec.Rejected(value + " is not a JSON number (RFC 8259 section 6)");
                }
                return JsonNumber.of(value);
            });

    /**
     * A number as an exact {@code BigDecimal}, keeping its scale ({@code 1.10} stays
     * {@code 1.10}). The scale must be within &plusmn;{@value JsonNumber#MAX_EXACT_DIGITS}, the
     * bound {@code JsonNumber} puts on exact integers: {@code 1e1000000000} would be cheap to
     * hold but not to compute with ({@code toBigInteger}, {@code toPlainString}), so it is
     * rejected in both directions. The number of digits is not bounded here: {@link
     * JsonCodec#read(String)} parses with {@code JsonLimits.DEFAULT}, whose {@code maxNumberLength}
     * is 1 000 characters, so a value written with longer text is valid JSON that {@code read}
     * rejects; parse with larger limits and {@link JsonCodec#decode} to read it back.
     */
    public static final JsonCodec<BigDecimal> BIG_DECIMAL = new ScalarCodec<>(
            json -> {
                var n = number(json, "decimal");
                BigDecimal value;
                try {
                    value = n.bigDecimalValue();
                } catch (ArithmeticException e) {
                    throw scaleOutOfRange(n);
                }
                if (Math.abs((long) value.scale()) > MAX_DIGITS) {
                    throw scaleOutOfRange(n);
                }
                return value;
            },
            value -> {
                if (Math.abs((long) value.scale()) > MAX_DIGITS) {
                    throw new ScalarCodec.Rejected("the scale " + value.scale() + " is outside +-" + MAX_DIGITS);
                }
                return JsonNumber.of(value);
            });

    /**
     * A number with an integer value of at most {@value JsonNumber#MAX_EXACT_DIGITS} digits, as
     * {@link JsonNumber#bigIntegerValueExact()}: {@code 1e1000000000} is rejected at once, in both
     * directions. {@link JsonCodec#read(String)} parses with {@code JsonLimits.DEFAULT}, whose
     * {@code maxNumberLength} is 1 000 characters, so an integer of 1 001 to 10 000 digits is
     * written but not read back by {@code read}; parse with larger limits and
     * {@link JsonCodec#decode} for that.
     */
    public static final JsonCodec<BigInteger> BIG_INTEGER = new ScalarCodec<>(
            json -> {
                var n = number(json, "integer");
                try {
                    return n.bigIntegerValueExact();
                } catch (ArithmeticException e) {
                    throw new ScalarCodec.Rejected("expected integer but found " + Messages.number(n)
                            + (isInteger(n) ? " (more than " + MAX_DIGITS + " digits)" : " (not an integer)"));
                }
            },
            value -> {
                if (value.abs().compareTo(TOO_MANY_DIGITS) >= 0) {
                    throw new ScalarCodec.Rejected("the integer has more than " + MAX_DIGITS + " digits");
                }
                return JsonNumber.of(value);
            });

    /**
     * A UUID as a string of 32 hex digits in the groups 8-4-4-4-12
     * ({@code 123e4567-e89b-12d3-a456-426614174000}); upper and lower case are read, lower case
     * is written. Shorter forms that {@link java.util.UUID#fromString(String)} accepts
     * ({@code 1-2-3-4-5}) are rejected.
     */
    public static final JsonCodec<UUID> UUID = new ScalarCodec<>(
            json -> {
                var text = string(json, "UUID");
                if (!isUuid(text)) {
                    throw new ScalarCodec.Rejected("expected a UUID (8-4-4-4-12 hex digits) but found "
                            + Messages.excerpt(text));
                }
                return java.util.UUID.fromString(text);
            },
            value -> JsonString.of(value.toString()));

    /** An instant as ISO-8601 with an offset ({@code 2026-10-02T09:18:00Z}), as {@link Instant#parse}. */
    public static final JsonCodec<Instant> INSTANT = time("an ISO-8601 instant such as 2026-10-02T09:18:00Z",
            Instant::parse);

    /** A date as ISO-8601 ({@code 2026-10-02}), as {@link LocalDate#parse}; {@code 2026-02-30} is a problem. */
    public static final JsonCodec<LocalDate> LOCAL_DATE = time("an ISO-8601 date such as 2026-10-02",
            LocalDate::parse);

    /** A time as ISO-8601 ({@code 11:18} or {@code 11:18:30.5}), as {@link LocalTime#parse}. */
    public static final JsonCodec<LocalTime> LOCAL_TIME = time("an ISO-8601 time such as 11:18:30",
            LocalTime::parse);

    /** A date and time without offset as ISO-8601 ({@code 2026-10-02T11:18:30}), as {@link LocalDateTime#parse}. */
    public static final JsonCodec<LocalDateTime> LOCAL_DATE_TIME = time(
            "an ISO-8601 date and time such as 2026-10-02T11:18:30", LocalDateTime::parse);

    /** A date and time with offset as ISO-8601 ({@code 2026-10-02T11:18:30+02:00}), as {@link OffsetDateTime#parse}. */
    public static final JsonCodec<OffsetDateTime> OFFSET_DATE_TIME = time(
            "an ISO-8601 date and time with offset such as 2026-10-02T11:18:30+02:00", OffsetDateTime::parse);

    /** A duration as ISO-8601 ({@code PT1H30M}), as {@link Duration#parse}. */
    public static final JsonCodec<Duration> DURATION = time("an ISO-8601 duration such as PT1H30M",
            Duration::parse);

    /**
     * Any JSON value as it is (also JSON {@code null}): for a part of a document the application
     * reads with the tree API. Writing Java {@code null} fails.
     */
    public static final JsonCodec<JsonValue> JSON = new ScalarCodec<>(json -> json, value -> value);

    /**
     * A JSON array as an unmodifiable {@code List}, in order. Each bad element is its own
     * problem ({@code $.items[3]}).
     *
     * @param <E>     the element type
     * @param element the codec of each element
     * @return the codec
     */
    public static <E> JsonCodec<List<E>> list(JsonCodec<E> element) {
        return new ListCodec<>(Objects.requireNonNull(element, "element"));
    }

    /**
     * A JSON array as an unmodifiable {@code Set} that keeps the order of the array. An element
     * equal to an earlier one is a problem (not dropped silently).
     *
     * @param <E>     the element type
     * @param element the codec of each element
     * @return the codec
     */
    public static <E> JsonCodec<Set<E>> set(JsonCodec<E> element) {
        return new SetCodec<>(Objects.requireNonNull(element, "element"));
    }

    /**
     * A JSON object as an unmodifiable {@code Map<String, V>} in member order: for objects whose
     * member names are data (ids, locales), not fields.
     *
     * @param <V>   the value type
     * @param value the codec of each member's value
     * @return the codec
     */
    public static <V> JsonCodec<Map<String, V>> map(JsonCodec<V> value) {
        return new MapCodec<>(Objects.requireNonNull(value, "value"));
    }

    /**
     * JSON {@code null} as {@code Optional.empty()} and any other value as present. Writes
     * {@code null} for an empty value. For a member that may be missing, use
     * {@link JsonCodec#optionalField(String, JsonCodec, Function)} instead, which also accepts a
     * missing member and leaves an empty value out.
     *
     * @param <T>   the type inside the {@code Optional}
     * @param inner the codec of a present value
     * @return the codec
     */
    public static <T> JsonCodec<Optional<T>> optional(JsonCodec<T> inner) {
        return new OptionalCodec<>(Objects.requireNonNull(inner, "inner"));
    }

    /**
     * JSON {@code null} as Java {@code null}, in both directions.
     *
     * @param <T>   the type
     * @param inner the codec of a non-null value
     * @return the codec
     */
    public static <T> JsonCodec<T> nullable(JsonCodec<T> inner) {
        return new NullableCodec<>(Objects.requireNonNull(inner, "inner"));
    }

    /**
     * A codec obtained on first use, for recursive types: the codec of a tree node refers to
     * itself through {@code lazy(() -> NODE)}, since the constant is not yet assigned while its
     * initializer runs. The supplier is called once.
     *
     * <pre>{@code
     * record Node(String name, List<Node> children) {}
     * static final JsonCodec<Node> NODE = JsonCodec.object(Node::new,
     *         field("name", STRING, Node::name),
     *         field("children", list(lazy(() -> Holder.NODE)), Node::children));
     * }</pre>
     *
     * <p>A supplier that returns a codec which leads back to the same lazy codec without an
     * object or array in between would recurse forever; this is detected and fails with
     * {@link IllegalStateException}.
     *
     * @param <T>      the type
     * @param supplier gives the codec; must not return {@code null}
     * @return the codec
     */
    public static <T> JsonCodec<T> lazy(Supplier<? extends JsonCodec<T>> supplier) {
        return new LazyCodec<>(supplier);
    }

    /**
     * An enum as a string with the constant's {@link Enum#name()}: {@code enumOf(Status.values())}.
     * An unknown name is a problem that lists the allowed ones.
     *
     * @param <E>    the enum type
     * @param values the constants to accept, usually {@code E.values()}
     * @return the codec
     */
    public static <E extends Enum<E>> JsonCodec<E> enumOf(E[] values) {
        return new EnumCodec<>(values, Enum::name);
    }

    /**
     * An enum as a string with a name the application chooses, such as
     * {@code enumOf(Status.values(), s -> s.name().toLowerCase(Locale.ROOT))}. Names are
     * compared exactly (case-sensitive).
     *
     * @param <E>    the enum type
     * @param values the constants to accept, usually {@code E.values()}
     * @param name   the JSON name of each constant
     * @return the codec
     * @throws IllegalArgumentException if two constants have the same name
     */
    public static <E extends Enum<E>> JsonCodec<E> enumOf(E[] values, Function<? super E, String> name) {
        return new EnumCodec<>(values, name);
    }

    private static <T> T reject(String message) throws ScalarCodec.Rejected {
        throw new ScalarCodec.Rejected(message);
    }

    private static JsonNumber number(JsonValue json, String type) throws ScalarCodec.Rejected {
        if (json instanceof JsonNumber n) {
            return n;
        }
        throw new ScalarCodec.Rejected(Messages.expected(type, json));
    }

    private static String string(JsonValue json, String type) throws ScalarCodec.Rejected {
        if (json instanceof JsonString s) {
            return s.value();
        }
        throw new ScalarCodec.Rejected(Messages.expected("a " + type + " string", json));
    }

    private static ScalarCodec.Rejected notExact(JsonNumber n, String type) {
        return new ScalarCodec.Rejected("expected " + type + " but found " + Messages.number(n)
                + (isInteger(n) ? " (outside the range of " + type + ")" : " (not an integer)"));
    }

    private static ScalarCodec.Rejected scaleOutOfRange(JsonNumber n) {
        return new ScalarCodec.Rejected("expected decimal but found " + Messages.number(n)
                + " (the exponent is outside +-" + MAX_DIGITS + ")");
    }

    /** Whether the number has an integer value; only called for an error message. */
    private static boolean isInteger(JsonNumber n) {
        try {
            var value = n.bigDecimalValue();
            return value.signum() == 0 || value.scale() <= 0 || value.stripTrailingZeros().scale() <= 0;
        } catch (ArithmeticException e) {
            // the exponent does not fit an int: a huge positive one makes an integer
            var text = n.text();
            int e2 = Math.max(text.indexOf('e'), text.indexOf('E'));
            return text.charAt(e2 + 1) != '-';
        }
    }

    private static boolean isUuid(String text) {
        if (text.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = text.charAt(i);
            boolean hyphen = i == 8 || i == 13 || i == 18 || i == 23;
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (hyphen ? c != '-' : !hex) {
                return false;
            }
        }
        return true;
    }

    /** A {@code java.time} codec: ISO-8601 text read with {@code parse} and written with {@code toString}. */
    private static <T> JsonCodec<T> time(String expected, Function<String, T> parse) {
        return new ScalarCodec<>(
                json -> {
                    if (!(json instanceof JsonString s)) {
                        throw new ScalarCodec.Rejected(Messages.expected(expected, json));
                    }
                    try {
                        return parse.apply(s.value());
                    } catch (RuntimeException e) {
                        // DateTimeParseException and ArithmeticException; their messages quote the input raw
                        throw new ScalarCodec.Rejected("expected " + expected + " but found " + Messages.excerpt(s.value()));
                    }
                },
                value -> JsonString.of(value.toString()));
    }
}
