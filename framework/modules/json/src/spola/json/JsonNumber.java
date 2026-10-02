package spola.json;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * A JSON number (RFC 8259 §6), kept as the exact text it had in the document (or that a factory
 * produced). Nothing is rounded or converted when parsing: {@code 1.10} stays {@code 1.10} and
 * is written back as {@code 1.10}. Conversions happen on request:
 *
 * <ul>
 *   <li>{@link #intValueExact()}, {@link #longValueExact()} and {@link #bigIntegerValueExact()}
 *       return the value only when it is an integer in range, and otherwise throw
 *       {@link ArithmeticException} (the JDK's convention, as in
 *       {@link BigDecimal#intValueExact()}); they never truncate or round. {@code 1.0} and
 *       {@code 1e2} are integers; {@code 1.5} is not.</li>
 *   <li>{@link #bigDecimalValue()} is exact and cheap even for {@code 1e1000000000} (the
 *       digits of the power of ten are never computed).</li>
 *   <li>{@link #doubleValue()} rounds to the nearest {@code double}, like
 *       {@link Double#parseDouble(String)}.</li>
 * </ul>
 *
 * <p>Equality is numeric: {@code 1}, {@code 1.0}, {@code 1e0} and {@code 10e-1} are equal and
 * have the same hash code, and {@code -0} equals {@code 0}. The text is not part of equality.
 * Comparing and hashing take time linear in the length of the text, whatever the exponent.
 */
public final class JsonNumber implements JsonValue {

    /**
     * The largest number of decimal digits {@link #bigIntegerValueExact()} produces. Larger
     * integers (such as {@code 1e1000000000}) are rejected with {@link ArithmeticException}
     * instead of spending minutes computing a power of ten.
     */
    public static final int MAX_EXACT_DIGITS = 10_000;

    private static final BigInteger MAX_EXACT = BigInteger.valueOf(MAX_EXACT_DIGITS);
    private static final BigInteger LONG_DIGITS = BigInteger.valueOf(19);

    private final String text;
    private final int hash;

    /** {@code text} must match the grammar of RFC 8259 §6; the parser and the factories ensure it. */
    JsonNumber(String text) {
        this.text = text;
        this.hash = canonical(text).hashCode();
    }

    /**
     * A number from a {@code long}.
     *
     * @param value the value
     * @return the number, with the text of {@link Long#toString(long)}
     */
    public static JsonNumber of(long value) {
        return new JsonNumber(Long.toString(value));
    }

    /**
     * A number from a {@link BigInteger}.
     *
     * @param value the value
     * @return the number, with the text of {@link BigInteger#toString()}
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static JsonNumber of(BigInteger value) {
        return new JsonNumber(value.toString());
    }

    /**
     * A number from a {@link BigDecimal}, keeping its scale ({@code 1.10} stays {@code 1.10}).
     *
     * @param value the value
     * @return the number, with the text of {@link BigDecimal#toString()} (such as
     *         {@code 1.10} or {@code 1E+3}, both valid JSON)
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static JsonNumber of(BigDecimal value) {
        return new JsonNumber(value.toString());
    }

    /**
     * A number from a {@code double}.
     *
     * @param value a finite value
     * @return the number, with the text of {@link Double#toString(double)} (such as {@code 0.1},
     *         {@code 1.0} or {@code 1.0E10})
     * @throws IllegalArgumentException if {@code value} is NaN or infinite; JSON has no such
     *                                  numbers (RFC 8259 §6)
     */
    public static JsonNumber of(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON has no number " + value + " (RFC 8259 section 6)");
        }
        return new JsonNumber(Double.toString(value));
    }

    /**
     * The number's text, exactly as in the document or as the factory produced it.
     *
     * @return the text, matching the grammar of RFC 8259 §6
     */
    public String text() {
        return text;
    }

    /**
     * The value as an {@code int}, if it is an integer in range.
     *
     * @return the value
     * @throws ArithmeticException if the number has a fractional part or is outside the range of
     *                             {@code int}
     */
    public int intValueExact() {
        long value = longValueExact("int");
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw outOfRange("int");
        }
        return (int) value;
    }

    /**
     * The value as a {@code long}, if it is an integer in range.
     *
     * @return the value
     * @throws ArithmeticException if the number has a fractional part or is outside the range of
     *                             {@code long}
     */
    public long longValueExact() {
        return longValueExact("long");
    }

    private long longValueExact(String type) {
        var c = integerCanonical(type);
        if (c.signum() == 0) {
            return 0;
        }
        if (c.integerDigits().compareTo(LONG_DIGITS) > 0) {
            throw outOfRange(type);
        }
        var value = c.toBigInteger();
        if (value.bitLength() > 63) {
            throw outOfRange(type);
        }
        return value.longValue();
    }

    /**
     * The value as a {@link BigInteger}, if it is an integer of at most
     * {@link #MAX_EXACT_DIGITS} digits.
     *
     * @return the value
     * @throws ArithmeticException if the number has a fractional part or more than
     *                             {@link #MAX_EXACT_DIGITS} digits
     */
    public BigInteger bigIntegerValueExact() {
        var c = integerCanonical("integer");
        if (c.signum() == 0) {
            return BigInteger.ZERO;
        }
        if (c.integerDigits().compareTo(MAX_EXACT) > 0) {
            throw new ArithmeticException("number " + Text.excerpt(text) + " has more than "
                    + MAX_EXACT_DIGITS + " digits");
        }
        return c.toBigInteger();
    }

    /**
     * The exact value as a {@link BigDecimal}. Cheap for any exponent: {@code 1e1000000000}
     * becomes the unscaled value 1 with scale -1000000000.
     *
     * @return the value, with the scale the text implies ({@code 1.10} has scale 2)
     * @throws ArithmeticException if the exponent is outside what {@link BigDecimal} can
     *                             represent (a scale beyond the range of {@code int})
     */
    public BigDecimal bigDecimalValue() {
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            var failure = new ArithmeticException("number " + Text.excerpt(text)
                    + " has an exponent outside the range of BigDecimal");
            failure.initCause(e);
            throw failure;
        }
    }

    /**
     * The value rounded to the nearest {@code double}, as {@link Double#parseDouble(String)}
     * does: a magnitude too large becomes an infinity, one too small becomes zero.
     *
     * @return the value
     */
    public double doubleValue() {
        return Double.parseDouble(text);
    }

    /**
     * Numeric equality: {@code 1}, {@code 1.0}, {@code 1e0} and {@code 10e-1} are equal.
     *
     * @param other another object
     * @return whether {@code other} is a {@code JsonNumber} with the same numeric value
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNumber number && hash == number.hash
                && canonical(text).equals(canonical(number.text));
    }

    /**
     * A hash code consistent with numeric equality.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * The number's text.
     *
     * @return {@link #text()}
     */
    @Override
    public String toString() {
        return text;
    }

    private Canonical integerCanonical(String type) {
        var c = canonical(text);
        if (c.exponent().signum() < 0) {
            throw new ArithmeticException("number " + Text.excerpt(text) + " has a fractional part, not an " + type);
        }
        return c;
    }

    private ArithmeticException outOfRange(String type) {
        return new ArithmeticException("number " + Text.excerpt(text) + " is outside the range of " + type);
    }

    /**
     * The value as {@code signum × digits × 10^exponent}, where {@code digits} has neither
     * leading nor trailing zeros. Two numbers are equal exactly when their canonical forms are.
     * Zero is {@code (0, "", 0)}.
     */
    private record Canonical(int signum, String digits, BigInteger exponent) {

        /** The number of digits of the integer value (for a non-negative exponent). */
        BigInteger integerDigits() {
            return exponent.add(BigInteger.valueOf(digits.length()));
        }

        /** The integer value; only called once the number of digits has been checked. */
        BigInteger toBigInteger() {
            var value = new BigInteger(digits).multiply(BigInteger.TEN.pow(exponent.intValueExact()));
            return signum < 0 ? value.negate() : value;
        }
    }

    private static Canonical canonical(String text) {
        int length = text.length();
        int i = 0;
        boolean negative = text.charAt(0) == '-';
        if (negative) {
            i++;
        }
        int intStart = i;
        while (i < length && isDigit(text.charAt(i))) {
            i++;
        }
        var all = new StringBuilder(length).append(text, intStart, i);
        int fractionDigits = 0;
        if (i < length && text.charAt(i) == '.') {
            int start = ++i;
            while (i < length && isDigit(text.charAt(i))) {
                i++;
            }
            fractionDigits = i - start;
            all.append(text, start, i);
        }
        var exponent = BigInteger.ZERO;
        if (i < length) {
            // 'e' or 'E', then an optional sign and at least one digit (BigInteger accepts '+')
            exponent = new BigInteger(text.substring(i + 1));
        }
        int first = 0;
        while (first < all.length() && all.charAt(first) == '0') {
            first++;
        }
        if (first == all.length()) {
            return new Canonical(0, "", BigInteger.ZERO);
        }
        int end = all.length();
        while (all.charAt(end - 1) == '0') {
            end--;
        }
        int trailingZeros = all.length() - end;
        return new Canonical(negative ? -1 : 1, all.substring(first, end),
                exponent.subtract(BigInteger.valueOf(fractionDigits)).add(BigInteger.valueOf(trailingZeros)));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
