package spola.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The built-in constraints, meant to be imported statically. Each has a fixed code and an
 * English default message; change them with {@link Constraint#withCode} and
 * {@link Constraint#withMessage}.
 *
 * <table>
 *   <caption>Built-in constraints</caption>
 *   <tr><th>Constraint</th><th>Jakarta</th><th>code</th><th>message</th></tr>
 *   <tr><td>{@link #notNull()}</td><td>{@code @NotNull}</td><td>{@code notNull}</td><td>{@code must not be null}</td></tr>
 *   <tr><td>{@link #notBlank()}</td><td>{@code @NotBlank}</td><td>{@code notBlank}</td><td>{@code must not be blank}</td></tr>
 *   <tr><td>{@link #notEmpty()}, {@link #notEmptyCollection()}, {@link #notEmptyMap()}</td><td>{@code @NotEmpty}</td><td>{@code notEmpty}</td><td>{@code must not be empty}</td></tr>
 *   <tr><td>{@link #length}, {@link #minLength}, {@link #maxLength}</td><td>{@code @Size} on text</td><td>{@code length}</td><td>{@code length must be between 1 and 100}, {@code length must be at least 1}, {@code length must be at most 100}</td></tr>
 *   <tr><td>{@link #size}, {@link #mapSize}</td><td>{@code @Size}</td><td>{@code size}</td><td>{@code size must be between 1 and 50}</td></tr>
 *   <tr><td>{@link #min}, {@link #max}, {@link #range}</td><td>{@code @Min}, {@code @Max}</td><td>{@code min}, {@code max}, {@code range}</td><td>{@code must be at least 1}, {@code must be at most 10}, {@code must be between 1 and 10}</td></tr>
 *   <tr><td>{@link #decimalMin}, {@link #decimalMax}</td><td>{@code @DecimalMin}, {@code @DecimalMax}</td><td>{@code decimalMin}, {@code decimalMax}</td><td>{@code must be at least 0.5} / {@code must be greater than 0.5}, {@code must be at most 9.99} / {@code must be less than 9.99}</td></tr>
 *   <tr><td>{@link #positive()}, {@link #positiveOrZero()}, {@link #negative()}, {@link #negativeOrZero()}</td><td>same</td><td>same</td><td>{@code must be greater than 0}, {@code must be greater than or equal to 0}, {@code must be less than 0}, {@code must be less than or equal to 0}</td></tr>
 *   <tr><td>{@link #digits}</td><td>{@code @Digits}</td><td>{@code digits}</td><td>{@code must have at most 3 integer digits and 2 fraction digits}</td></tr>
 *   <tr><td>{@link #matches}</td><td>{@code @Pattern}</td><td>{@code pattern}</td><td>{@code must match the required format}</td></tr>
 *   <tr><td>{@link #email()}</td><td>{@code @Email}</td><td>{@code email}</td><td>{@code must be a well-formed email address}</td></tr>
 *   <tr><td>{@link #oneOf}</td><td>(an enum, or your own)</td><td>{@code oneOf}</td><td>{@code must be one of the allowed values}</td></tr>
 *   <tr><td>{@link #past}, {@link #pastOrPresent}, {@link #future}, {@link #futureOrPresent}</td><td>{@code @Past} and the others</td><td>same</td><td>{@code must be in the past}, {@code must be in the past or in the present}, {@code must be in the future}, {@code must be in the future or in the present}</td></tr>
 * </table>
 *
 * <p><b>{@code null}</b> is valid for every constraint except {@code notNull}, {@code notBlank}
 * and the {@code notEmpty} constraints.
 *
 * <p><b>Numbers</b> are compared exactly: {@code Byte}, {@code Short}, {@code Integer},
 * {@code Long}, {@code BigInteger} and {@code BigDecimal} as they are; {@code Double} and
 * {@code Float} through {@code BigDecimal} of their shortest decimal text
 * ({@code Double.toString}), and they are never valid when NaN or infinite. Any other
 * {@code Number} is compared through {@code new BigDecimal(toString())}; if that text is not a
 * number, the value is not valid (no exception).
 *
 * <p><b>Parameters</b> are checked when the constraint is made: no {@code null}, no negative
 * lengths, sizes or digit counts, and {@code min <= max}. A wrong parameter throws
 * {@link IllegalArgumentException}.
 */
public final class Constraints {

    private Constraints() {
    }

    // ---- presence -------------------------------------------------------------------------

    /**
     * Not {@code null} ({@code @NotNull}). Code {@code notNull}, message
     * {@code must not be null}.
     *
     * @return the constraint, for any type
     */
    public static Constraint<Object> notNull() {
        return Constraint.checkingNull("notNull", v -> v != null, "must not be null");
    }

    /**
     * Not {@code null} and not blank as in {@link String#isBlank()}: at least one code point
     * that is not white space ({@code @NotBlank}). Code {@code notBlank}, message
     * {@code must not be blank}.
     *
     * @return the constraint
     */
    public static Constraint<CharSequence> notBlank() {
        return Constraint.checkingNull("notBlank", v -> v != null && !v.toString().isBlank(), "must not be blank");
    }

    /**
     * Text that is not {@code null} and not empty ({@code @NotEmpty} on text; white space
     * counts). Code {@code notEmpty}, message {@code must not be empty}.
     *
     * @return the constraint
     */
    public static Constraint<CharSequence> notEmpty() {
        return Constraint.checkingNull("notEmpty", v -> v != null && !v.isEmpty(), "must not be empty");
    }

    /**
     * A collection that is not {@code null} and not empty ({@code @NotEmpty} on a collection).
     * Code {@code notEmpty}, message {@code must not be empty}.
     *
     * @return the constraint
     */
    public static Constraint<Collection<?>> notEmptyCollection() {
        return Constraint.checkingNull("notEmpty", v -> v != null && !v.isEmpty(), "must not be empty");
    }

    /**
     * A map that is not {@code null} and not empty ({@code @NotEmpty} on a map). Code
     * {@code notEmpty}, message {@code must not be empty}.
     *
     * @return the constraint
     */
    public static Constraint<Map<?, ?>> notEmptyMap() {
        return Constraint.checkingNull("notEmpty", v -> v != null && !v.isEmpty(), "must not be empty");
    }

    // ---- text length and collection size --------------------------------------------------

    /**
     * Text of {@code min} to {@code max} code points, inclusive ({@code @Size} on text, but
     * counted in code points: an emoji outside the BMP counts as 1, not 2). Code
     * {@code length}, message {@code length must be between <min> and <max>}.
     *
     * @param min the fewest code points
     * @param max the most code points
     * @return the constraint
     * @throws IllegalArgumentException if {@code min} is negative or greater than {@code max}
     */
    public static Constraint<CharSequence> length(int min, int max) {
        bounds("length", min, max);
        return Constraint.of("length", v -> between(codePoints(v), min, max),
                "length must be between " + min + " and " + max);
    }

    /**
     * Text of at least {@code min} code points. Code {@code length}, message
     * {@code length must be at least <min>}.
     *
     * @param min the fewest code points
     * @return the constraint
     * @throws IllegalArgumentException if {@code min} is negative
     */
    public static Constraint<CharSequence> minLength(int min) {
        notNegative("minLength", min);
        return Constraint.of("length", v -> codePoints(v) >= min, "length must be at least " + min);
    }

    /**
     * Text of at most {@code max} code points. Code {@code length}, message
     * {@code length must be at most <max>}.
     *
     * @param max the most code points
     * @return the constraint
     * @throws IllegalArgumentException if {@code max} is negative
     */
    public static Constraint<CharSequence> maxLength(int max) {
        notNegative("maxLength", max);
        return Constraint.of("length", v -> codePoints(v) <= max, "length must be at most " + max);
    }

    /**
     * A collection of {@code min} to {@code max} elements, inclusive ({@code @Size}). Code
     * {@code size}, message {@code size must be between <min> and <max>}.
     *
     * @param min the fewest elements
     * @param max the most elements
     * @return the constraint
     * @throws IllegalArgumentException if {@code min} is negative or greater than {@code max}
     */
    public static Constraint<Collection<?>> size(int min, int max) {
        bounds("size", min, max);
        return Constraint.of("size", v -> between(v.size(), min, max), "size must be between " + min + " and " + max);
    }

    /**
     * A map of {@code min} to {@code max} entries, inclusive ({@code @Size} on a map). Code
     * {@code size}, message {@code size must be between <min> and <max>}.
     *
     * @param min the fewest entries
     * @param max the most entries
     * @return the constraint
     * @throws IllegalArgumentException if {@code min} is negative or greater than {@code max}
     */
    public static Constraint<Map<?, ?>> mapSize(int min, int max) {
        bounds("mapSize", min, max);
        return Constraint.of("size", v -> between(v.size(), min, max), "size must be between " + min + " and " + max);
    }

    // ---- numbers ---------------------------------------------------------------------------

    /**
     * A number of at least {@code min} ({@code @Min}). Code {@code min}, message
     * {@code must be at least <min>}.
     *
     * @param min the smallest valid value
     * @return the constraint
     */
    public static Constraint<Number> min(long min) {
        var bound = BigDecimal.valueOf(min);
        return number("min", d -> d.compareTo(bound) >= 0, "must be at least " + min);
    }

    /**
     * A number of at most {@code max} ({@code @Max}). Code {@code max}, message
     * {@code must be at most <max>}.
     *
     * @param max the largest valid value
     * @return the constraint
     */
    public static Constraint<Number> max(long max) {
        var bound = BigDecimal.valueOf(max);
        return number("max", d -> d.compareTo(bound) <= 0, "must be at most " + max);
    }

    /**
     * A number from {@code min} to {@code max}, inclusive ({@code @Min} and {@code @Max}
     * together, or Hibernate's {@code @Range}). Code {@code range}, message
     * {@code must be between <min> and <max>}.
     *
     * @param min the smallest valid value
     * @param max the largest valid value
     * @return the constraint
     * @throws IllegalArgumentException if {@code min} is greater than {@code max}
     */
    public static Constraint<Number> range(long min, long max) {
        if (min > max) {
            throw new IllegalArgumentException("range: min " + min + " is greater than max " + max);
        }
        var low = BigDecimal.valueOf(min);
        var high = BigDecimal.valueOf(max);
        return number("range", d -> d.compareTo(low) >= 0 && d.compareTo(high) <= 0,
                "must be between " + min + " and " + max);
    }

    /**
     * A number of at least {@code min} ({@code inclusive}) or greater than {@code min}
     * ({@code @DecimalMin}). Code {@code decimalMin}, message {@code must be at least <min>}
     * when inclusive and {@code must be greater than <min>} when not, with {@code min} written
     * by {@link BigDecimal#toString()}.
     *
     * @param min       the bound
     * @param inclusive whether {@code min} itself is valid
     * @return the constraint
     * @throws IllegalArgumentException if {@code min} is {@code null}
     */
    public static Constraint<Number> decimalMin(BigDecimal min, boolean inclusive) {
        notNull("decimalMin", min);
        return inclusive
                ? number("decimalMin", d -> d.compareTo(min) >= 0, "must be at least " + min)
                : number("decimalMin", d -> d.compareTo(min) > 0, "must be greater than " + min);
    }

    /**
     * A number of at most {@code max} ({@code inclusive}) or less than {@code max}
     * ({@code @DecimalMax}). Code {@code decimalMax}, message {@code must be at most <max>}
     * when inclusive and {@code must be less than <max>} when not, with {@code max} written by
     * {@link BigDecimal#toString()}.
     *
     * @param max       the bound
     * @param inclusive whether {@code max} itself is valid
     * @return the constraint
     * @throws IllegalArgumentException if {@code max} is {@code null}
     */
    public static Constraint<Number> decimalMax(BigDecimal max, boolean inclusive) {
        notNull("decimalMax", max);
        return inclusive
                ? number("decimalMax", d -> d.compareTo(max) <= 0, "must be at most " + max)
                : number("decimalMax", d -> d.compareTo(max) < 0, "must be less than " + max);
    }

    /**
     * A number greater than 0 ({@code @Positive}). Code {@code positive}, message
     * {@code must be greater than 0}.
     *
     * @return the constraint
     */
    public static Constraint<Number> positive() {
        return number("positive", d -> d.signum() > 0, "must be greater than 0");
    }

    /**
     * A number of 0 or more ({@code @PositiveOrZero}); {@code -0.0} counts as 0. Code
     * {@code positiveOrZero}, message {@code must be greater than or equal to 0}.
     *
     * @return the constraint
     */
    public static Constraint<Number> positiveOrZero() {
        return number("positiveOrZero", d -> d.signum() >= 0, "must be greater than or equal to 0");
    }

    /**
     * A number less than 0 ({@code @Negative}); {@code -0.0} counts as 0. Code
     * {@code negative}, message {@code must be less than 0}.
     *
     * @return the constraint
     */
    public static Constraint<Number> negative() {
        return number("negative", d -> d.signum() < 0, "must be less than 0");
    }

    /**
     * A number of 0 or less ({@code @NegativeOrZero}). Code {@code negativeOrZero}, message
     * {@code must be less than or equal to 0}.
     *
     * @return the constraint
     */
    public static Constraint<Number> negativeOrZero() {
        return number("negativeOrZero", d -> d.signum() <= 0, "must be less than or equal to 0");
    }

    /**
     * A number with at most {@code integer} digits before the decimal point and at most
     * {@code fraction} after it ({@code @Digits}), counted as Hibernate Validator does: on the
     * value without trailing zeros, so {@code 1.50} has 1 fraction digit and {@code 100} has 3
     * integer digits; {@code 0.5} has 0 integer digits. Code {@code digits}, message
     * {@code must have at most <integer> integer digits and <fraction> fraction digits}.
     *
     * @param integer  the most integer digits
     * @param fraction the most fraction digits
     * @return the constraint
     * @throws IllegalArgumentException if a count is negative
     */
    public static Constraint<Number> digits(int integer, int fraction) {
        notNegative("digits integer", integer);
        notNegative("digits fraction", fraction);
        return number("digits", d -> {
            if (d.signum() == 0) {
                return true;
            }
            var stripped = d.stripTrailingZeros();
            long integerDigits = (long) stripped.precision() - stripped.scale();
            long fractionDigits = Math.max(stripped.scale(), 0);
            return integerDigits <= integer && fractionDigits <= fraction;
        }, "must have at most " + integer + " integer digits and " + fraction + " fraction digits");
    }

    // ---- text formats ----------------------------------------------------------------------

    /**
     * Text that the pattern matches as a whole ({@code @Pattern}; {@link java.util.regex.Matcher#matches()},
     * not {@code find()}). Code {@code pattern}, message {@code must match the required format}
     * (the pattern itself is not shown). The pattern runs on the value as given; a pattern
     * that backtracks badly is as slow here as anywhere.
     *
     * @param pattern the pattern
     * @return the constraint
     * @throws IllegalArgumentException if {@code pattern} is {@code null}
     */
    public static Constraint<CharSequence> matches(Pattern pattern) {
        notNull("matches", pattern);
        return Constraint.of("pattern", v -> pattern.matcher(v).matches(), "must match the required format");
    }

    /**
     * A well-formed email address ({@code @Email}), by a deliberately simple and exact
     * definition. Code {@code email}, message {@code must be a well-formed email address}.
     * Valid is:
     * <ul>
     *   <li>exactly one {@code @};</li>
     *   <li>before it, a local part of 1 to 64 characters in RFC 5322 {@code dot-atom}:
     *       {@code atext} ({@code A-Z a-z 0-9 ! # $ % & ' * + - / = ? ^ _ ` { | } ~}) and dots,
     *       with no dot first or last and no two dots in a row;</li>
     *   <li>after it, a domain of 1 to 253 characters: labels of 1 to 63 letters, digits and
     *       hyphens ({@code A-Z a-z 0-9 -}), separated by single dots, no hyphen first or last
     *       in a label.</li>
     * </ul>
     * Nothing else: no quoted local parts ({@code "a b"@x.se}), no IP literals
     * ({@code a@[127.0.0.1]}), no comments, and nothing outside ASCII (no internationalized
     * addresses). A domain without a dot is valid ({@code a@b}), as in Hibernate Validator.
     *
     * @return the constraint
     */
    public static Constraint<CharSequence> email() {
        return Constraint.of("email", v -> Email.isValid(v), "must be a well-formed email address");
    }

    // ---- choices and time ------------------------------------------------------------------

    /**
     * One of the given values, by {@link Object#equals} and {@link Object#hashCode} (so
     * {@code BigDecimal} {@code 1.0} is not {@code 1}). Code {@code oneOf}, message
     * {@code must be one of the allowed values} (the values are not listed: there may be many,
     * and they may be secret). The values are copied when the constraint is made.
     *
     * @param allowed the valid values; not empty, no {@code null}
     * @param <T>     the type of the values
     * @return the constraint
     * @throws IllegalArgumentException if {@code allowed} is {@code null}, empty or contains
     *                                  {@code null}
     */
    public static <T> Constraint<T> oneOf(Collection<? extends T> allowed) {
        notNull("oneOf", allowed);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("oneOf: no allowed values");
        }
        for (var value : allowed) {
            if (value == null) {
                throw new IllegalArgumentException("oneOf: null is not an allowed value (null is always valid)");
            }
        }
        Set<T> set = Set.copyOf(allowed);
        return Constraint.of("oneOf", set::contains, "must be one of the allowed values");
    }

    /**
     * Before now ({@code @Past}). {@code now} is asked for each value, for example
     * {@code () -> LocalDate.now(clock)}: there is no hidden system clock. Code {@code past},
     * message {@code must be in the past}.
     *
     * @param now the current time, asked once per checked value; must not return {@code null}
     * @param <C> the type of the time, such as {@code LocalDate} or {@code Instant}
     * @return the constraint
     * @throws IllegalArgumentException if {@code now} is {@code null}
     */
    public static <C extends Comparable<? super C>> Constraint<C> past(Supplier<? extends C> now) {
        return time("past", now, c -> c < 0, "must be in the past");
    }

    /**
     * Before now or now ({@code @PastOrPresent}). Code {@code pastOrPresent}, message
     * {@code must be in the past or in the present}.
     *
     * @param now the current time, asked once per checked value; must not return {@code null}
     * @param <C> the type of the time
     * @return the constraint
     * @throws IllegalArgumentException if {@code now} is {@code null}
     */
    public static <C extends Comparable<? super C>> Constraint<C> pastOrPresent(Supplier<? extends C> now) {
        return time("pastOrPresent", now, c -> c <= 0, "must be in the past or in the present");
    }

    /**
     * After now ({@code @Future}). Code {@code future}, message {@code must be in the future}.
     *
     * @param now the current time, asked once per checked value; must not return {@code null}
     * @param <C> the type of the time
     * @return the constraint
     * @throws IllegalArgumentException if {@code now} is {@code null}
     */
    public static <C extends Comparable<? super C>> Constraint<C> future(Supplier<? extends C> now) {
        return time("future", now, c -> c > 0, "must be in the future");
    }

    /**
     * After now or now ({@code @FutureOrPresent}). Code {@code futureOrPresent}, message
     * {@code must be in the future or in the present}.
     *
     * @param now the current time, asked once per checked value; must not return {@code null}
     * @param <C> the type of the time
     * @return the constraint
     * @throws IllegalArgumentException if {@code now} is {@code null}
     */
    public static <C extends Comparable<? super C>> Constraint<C> futureOrPresent(Supplier<? extends C> now) {
        return time("futureOrPresent", now, c -> c >= 0, "must be in the future or in the present");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private interface Comparison {
        boolean holds(int comparison);
    }

    private static <C extends Comparable<? super C>> Constraint<C> time(String code, Supplier<? extends C> now,
                                                                       Comparison valid, String message) {
        notNull(code, now);
        return Constraint.of(code, v -> {
            C current = now.get();
            if (current == null) {
                throw new NullPointerException(code + ": the supplier of the current time returned null");
            }
            return valid.holds(v.compareTo(current));
        }, message);
    }

    private static Constraint<Number> number(String code, Predicate<BigDecimal> test, String message) {
        return Constraint.of(code, n -> {
            var decimal = decimal(n);
            return decimal != null && test.test(decimal);
        }, message);
    }

    /** The number as a BigDecimal, or null when it is not a finite number. */
    static BigDecimal decimal(Number n) {
        return switch (n) {
            case Integer i -> BigDecimal.valueOf(i);
            case Long l -> BigDecimal.valueOf(l);
            case Short s -> BigDecimal.valueOf(s);
            case Byte b -> BigDecimal.valueOf(b);
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d -> d.isNaN() || d.isInfinite() ? null : new BigDecimal(Double.toString(d));
            case Float f -> f.isNaN() || f.isInfinite() ? null : new BigDecimal(Float.toString(f));
            default -> {
                var text = n.toString();
                if (text == null) {
                    yield null;
                }
                try {
                    yield new BigDecimal(text);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    private static int codePoints(CharSequence text) {
        return Character.codePointCount(text, 0, text.length());
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static void bounds(String name, int min, int max) {
        notNegative(name + " min", min);
        if (min > max) {
            throw new IllegalArgumentException(name + ": min " + min + " is greater than max " + max);
        }
    }

    private static void notNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative: " + value);
        }
    }

    private static void notNull(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + ": the parameter must not be null");
        }
    }
}
