package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.decimalMax;
import static spola.validation.Constraints.decimalMin;
import static spola.validation.Constraints.digits;
import static spola.validation.Constraints.email;
import static spola.validation.Constraints.future;
import static spola.validation.Constraints.futureOrPresent;
import static spola.validation.Constraints.length;
import static spola.validation.Constraints.mapSize;
import static spola.validation.Constraints.matches;
import static spola.validation.Constraints.max;
import static spola.validation.Constraints.maxLength;
import static spola.validation.Constraints.min;
import static spola.validation.Constraints.minLength;
import static spola.validation.Constraints.negative;
import static spola.validation.Constraints.negativeOrZero;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.notEmpty;
import static spola.validation.Constraints.notEmptyCollection;
import static spola.validation.Constraints.notEmptyMap;
import static spola.validation.Constraints.notNull;
import static spola.validation.Constraints.oneOf;
import static spola.validation.Constraints.past;
import static spola.validation.Constraints.pastOrPresent;
import static spola.validation.Constraints.positive;
import static spola.validation.Constraints.positiveOrZero;
import static spola.validation.Constraints.range;
import static spola.validation.Constraints.size;

import java.io.Serial;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import spola.test.Suite;

/** Criterion 3: every built-in constraint, valid and invalid values, exact code and message. */
final class ConstraintTableTests {

    private ConstraintTableTests() {
    }

    /** "Now" for the time constraints: a fixed clock. */
    static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    static final Supplier<LocalDate> NOW = () -> TODAY;
    static final Instant INSTANT = Instant.parse("2026-10-03T11:31:00Z");
    static final Supplier<Instant> NOW_INSTANT = () -> INSTANT;

    /** One row: a constraint, a value and the expected outcome ({@code code == null}: valid). */
    record Case<T>(String label, Constraint<T> constraint, T value, String code, String message) {

        void run() {
            var result = Validator.of(constraint).check(value);
            assertEquals(constraint.accepts(value), code == null, "accepts() and check() disagree");
            if (code == null) {
                assertEquals(new Validated.Valid<>(value), result);
            } else {
                assertEquals(new Validated.Invalid<T>(List.of(new Violation("$", code, message)), 1), result);
            }
        }

        @Override
        public String toString() {
            return label + (code == null ? " -> valid" : " -> " + code + ": " + message);
        }
    }

    static <T> Case<T> valid(String label, Constraint<T> constraint, T value) {
        return new Case<>(label, constraint, value, null, null);
    }

    static <T> Case<T> invalid(String label, Constraint<T> constraint, T value, String code, String message) {
        return new Case<>(label, constraint, value, code, message);
    }

    /** A {@code Number} whose text is not a number: never valid, never an exception. */
    static final class Weird extends Number {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public int intValue() {
            return 5;
        }

        @Override
        public long longValue() {
            return 5;
        }

        @Override
        public float floatValue() {
            return 5;
        }

        @Override
        public double doubleValue() {
            return 5;
        }

        @Override
        public String toString() {
            return "five";
        }
    }

    static final String EMOJI = "😀"; // one code point, two chars

    static List<Case<?>> cases() {
        var cases = new ArrayList<Case<?>>();
        // notNull
        cases.add(valid("notNull: text", notNull(), "x"));
        cases.add(valid("notNull: 0", notNull(), 0));
        cases.add(invalid("notNull: null", notNull(), null, "notNull", "must not be null"));
        // notBlank (String.isBlank)
        cases.add(valid("notBlank: a", notBlank(), "a"));
        cases.add(valid("notBlank: NBSP is not white space", notBlank(), " "));
        cases.add(invalid("notBlank: null", notBlank(), null, "notBlank", "must not be blank"));
        cases.add(invalid("notBlank: empty", notBlank(), "", "notBlank", "must not be blank"));
        cases.add(invalid("notBlank: spaces and tab", notBlank(), "  \t\n", "notBlank", "must not be blank"));
        cases.add(invalid("notBlank: em space", notBlank(), " ", "notBlank", "must not be blank"));
        cases.add(invalid("notBlank: StringBuilder of spaces", notBlank(), new StringBuilder("  "), "notBlank",
                "must not be blank"));
        // notEmpty (text, collection, map)
        cases.add(valid("notEmpty: a space", notEmpty(), " "));
        cases.add(invalid("notEmpty: empty", notEmpty(), "", "notEmpty", "must not be empty"));
        cases.add(invalid("notEmpty: null", notEmpty(), null, "notEmpty", "must not be empty"));
        cases.add(valid("notEmptyCollection: [1]", notEmptyCollection(), List.of(1)));
        cases.add(invalid("notEmptyCollection: []", notEmptyCollection(), List.of(), "notEmpty", "must not be empty"));
        cases.add(invalid("notEmptyCollection: null", notEmptyCollection(), null, "notEmpty", "must not be empty"));
        cases.add(valid("notEmptyMap: {a=1}", notEmptyMap(), Map.of("a", 1)));
        cases.add(invalid("notEmptyMap: {}", notEmptyMap(), Map.of(), "notEmpty", "must not be empty"));
        cases.add(invalid("notEmptyMap: null", notEmptyMap(), null, "notEmpty", "must not be empty"));
        // length in code points
        cases.add(valid("length(2,4): min", length(2, 4), "ab"));
        cases.add(valid("length(2,4): max", length(2, 4), "abcd"));
        cases.add(valid("length(2,4): null", length(2, 4), null));
        cases.add(valid("length(2,4): 4 emoji are 4, not 8", length(2, 4), EMOJI.repeat(4)));
        cases.add(invalid("length(2,4): min - 1", length(2, 4), "a", "length", "length must be between 2 and 4"));
        cases.add(invalid("length(2,4): max + 1", length(2, 4), "abcde", "length", "length must be between 2 and 4"));
        cases.add(invalid("length(2,4): 1 emoji is 1, not 2", length(2, 4), EMOJI, "length",
                "length must be between 2 and 4"));
        cases.add(invalid("length(2,4): 5 emoji", length(2, 4), EMOJI.repeat(5), "length",
                "length must be between 2 and 4"));
        cases.add(valid("minLength(2): min", minLength(2), "ab"));
        cases.add(valid("minLength(2): null", minLength(2), null));
        cases.add(invalid("minLength(2): min - 1", minLength(2), "a", "length", "length must be at least 2"));
        cases.add(invalid("minLength(2): one emoji", minLength(2), EMOJI, "length", "length must be at least 2"));
        cases.add(valid("maxLength(3): max", maxLength(3), "abc"));
        cases.add(valid("maxLength(3): 3 emoji", maxLength(3), EMOJI.repeat(3)));
        cases.add(valid("maxLength(3): null", maxLength(3), null));
        cases.add(invalid("maxLength(3): max + 1", maxLength(3), "abcd", "length", "length must be at most 3"));
        // size
        cases.add(valid("size(1,3): min", size(1, 3), List.of(1)));
        cases.add(valid("size(1,3): max", size(1, 3), List.of(1, 2, 3)));
        cases.add(valid("size(1,3): null", size(1, 3), null));
        cases.add(invalid("size(1,3): min - 1", size(1, 3), List.of(), "size", "size must be between 1 and 3"));
        cases.add(invalid("size(1,3): max + 1", size(1, 3), List.of(1, 2, 3, 4), "size", "size must be between 1 and 3"));
        cases.add(valid("mapSize(1,2): min", mapSize(1, 2), Map.of("a", 1)));
        cases.add(valid("mapSize(1,2): null", mapSize(1, 2), null));
        cases.add(valid("mapSize(1,2): max", mapSize(1, 2), Map.of("a", 1, "b", 2)));
        cases.add(invalid("mapSize(1,2): min - 1", mapSize(1, 2), Map.of(), "size", "size must be between 1 and 2"));
        cases.add(invalid("mapSize(1,2): max + 1", mapSize(1, 2), Map.of("a", 1, "b", 2, "c", 3), "size",
                "size must be between 1 and 2"));
        // min, max, range over every number type
        cases.add(valid("min(1): Integer 1", min(1), 1));
        cases.add(invalid("min(1): Integer 0", min(1), 0, "min", "must be at least 1"));
        cases.add(valid("min(1): Long 1", min(1), 1L));
        cases.add(invalid("min(1): Long 0", min(1), 0L, "min", "must be at least 1"));
        cases.add(invalid("min(1): Long.MIN_VALUE", min(1), Long.MIN_VALUE, "min", "must be at least 1"));
        cases.add(valid("min(1): Short 1", min(1), (short) 1));
        cases.add(invalid("min(1): Byte 0", min(1), (byte) 0, "min", "must be at least 1"));
        cases.add(valid("min(1): BigDecimal 1.00", min(1), new BigDecimal("1.00")));
        cases.add(invalid("min(1): BigDecimal 0.99", min(1), new BigDecimal("0.99"), "min", "must be at least 1"));
        cases.add(valid("min(1): BigInteger 1", min(1), BigInteger.ONE));
        cases.add(invalid("min(1): BigInteger 0", min(1), BigInteger.ZERO, "min", "must be at least 1"));
        cases.add(valid("min(1): Double 1.0", min(1), 1.0));
        cases.add(invalid("min(1): Double 0.999", min(1), 0.999, "min", "must be at least 1"));
        cases.add(invalid("min(1): Double NaN", min(1), Double.NaN, "min", "must be at least 1"));
        cases.add(invalid("min(1): Double +infinity", min(1), Double.POSITIVE_INFINITY, "min", "must be at least 1"));
        cases.add(valid("min(1): Float 1.5", min(1), 1.5f));
        cases.add(invalid("min(1): Float NaN", min(1), Float.NaN, "min", "must be at least 1"));
        cases.add(valid("min(1): AtomicInteger 5 (via toString)", min(1), new AtomicInteger(5)));
        cases.add(invalid("min(1): a Number whose text is not a number", min(1), new Weird(), "min", "must be at least 1"));
        cases.add(valid("min(1): null", min(1), null));
        cases.add(valid("max(10): Integer 10", max(10), 10));
        cases.add(invalid("max(10): Integer 11", max(10), 11, "max", "must be at most 10"));
        cases.add(invalid("max(10): Double -infinity", max(10), Double.NEGATIVE_INFINITY, "max", "must be at most 10"));
        cases.add(invalid("max(10): BigInteger 10^30", max(10), BigInteger.TEN.pow(30), "max", "must be at most 10"));
        cases.add(valid("max(10): null", max(10), null));
        cases.add(valid("max(Long.MAX_VALUE): BigInteger 2^63 - 1", max(Long.MAX_VALUE),
                BigInteger.TWO.pow(63).subtract(BigInteger.ONE)));
        cases.add(invalid("max(Long.MAX_VALUE): BigInteger 2^63 (exact, not via double)", max(Long.MAX_VALUE),
                BigInteger.TWO.pow(63), "max", "must be at most 9223372036854775807"));
        cases.add(valid("range(1,10): min", range(1, 10), 1));
        cases.add(valid("range(1,10): max", range(1, 10), 10L));
        cases.add(invalid("range(1,10): min - 1", range(1, 10), 0, "range", "must be between 1 and 10"));
        cases.add(invalid("range(1,10): max + 1", range(1, 10), 11, "range", "must be between 1 and 10"));
        cases.add(invalid("range(1,10): Double NaN", range(1, 10), Double.NaN, "range", "must be between 1 and 10"));
        cases.add(valid("range(1,10): null", range(1, 10), null));
        // decimalMin / decimalMax, with and without inclusive
        var half = new BigDecimal("0.5");
        cases.add(valid("decimalMin(0.5, inclusive): 0.5", decimalMin(half, true), new BigDecimal("0.5")));
        cases.add(valid("decimalMin(0.5, inclusive): Double 0.5", decimalMin(half, true), 0.5));
        cases.add(invalid("decimalMin(0.5, inclusive): 0.49", decimalMin(half, true), new BigDecimal("0.49"),
                "decimalMin", "must be at least 0.5"));
        cases.add(invalid("decimalMin(0.5, exclusive): 0.50", decimalMin(half, false), new BigDecimal("0.50"),
                "decimalMin", "must be greater than 0.5"));
        cases.add(valid("decimalMin(0.5, exclusive): 0.51", decimalMin(half, false), new BigDecimal("0.51")));
        cases.add(valid("decimalMin(0.5, exclusive): null", decimalMin(half, false), null));
        var max = new BigDecimal("9.99");
        cases.add(valid("decimalMax(9.99, inclusive): 9.99", decimalMax(max, true), new BigDecimal("9.99")));
        cases.add(invalid("decimalMax(9.99, inclusive): 10", decimalMax(max, true), 10, "decimalMax", "must be at most 9.99"));
        cases.add(invalid("decimalMax(9.99, exclusive): 9.99", decimalMax(max, false), 9.99, "decimalMax",
                "must be less than 9.99"));
        cases.add(valid("decimalMax(9.99, exclusive): 9.98", decimalMax(max, false), 9.98));
        cases.add(valid("decimalMax(9.99, inclusive): null", decimalMax(max, true), null));
        // sign
        cases.add(valid("positive: 1", positive(), 1));
        cases.add(valid("positive: Double.MIN_VALUE", positive(), Double.MIN_VALUE));
        cases.add(invalid("positive: 0", positive(), 0, "positive", "must be greater than 0"));
        cases.add(invalid("positive: -0.0", positive(), -0.0, "positive", "must be greater than 0"));
        cases.add(invalid("positive: NaN", positive(), Double.NaN, "positive", "must be greater than 0"));
        cases.add(valid("positive: null", positive(), null));
        cases.add(valid("positiveOrZero: 0", positiveOrZero(), 0));
        cases.add(valid("positiveOrZero: -0.0", positiveOrZero(), -0.0));
        cases.add(invalid("positiveOrZero: -1", positiveOrZero(), -1, "positiveOrZero", "must be greater than or equal to 0"));
        cases.add(valid("positiveOrZero: null", positiveOrZero(), null));
        cases.add(valid("negative: -1", negative(), -1L));
        cases.add(invalid("negative: 0", negative(), BigInteger.ZERO, "negative", "must be less than 0"));
        cases.add(valid("negative: null", negative(), null));
        cases.add(valid("negativeOrZero: 0", negativeOrZero(), BigDecimal.ZERO));
        cases.add(invalid("negativeOrZero: 1", negativeOrZero(), 1, "negativeOrZero", "must be less than or equal to 0"));
        cases.add(valid("negativeOrZero: null", negativeOrZero(), null));
        // digits
        var digits = digits(3, 2);
        var digitsMessage = "must have at most 3 integer digits and 2 fraction digits";
        cases.add(valid("digits(3,2): 123.45", digits, new BigDecimal("123.45")));
        cases.add(valid("digits(3,2): -123.45", digits, new BigDecimal("-123.45")));
        cases.add(valid("digits(3,2): 1.500 (trailing zeros do not count)", digits, new BigDecimal("1.500")));
        cases.add(valid("digits(3,2): Integer 999", digits, 999));
        cases.add(valid("digits(3,2): Double 0.05", digits, 0.05));
        cases.add(valid("digits(3,2): 0", digits, 0));
        cases.add(valid("digits(3,2): null", digits, null));
        cases.add(invalid("digits(3,2): Integer 1000", digits, 1000, "digits", digitsMessage));
        cases.add(invalid("digits(3,2): 1.234", digits, new BigDecimal("1.234"), "digits", digitsMessage));
        cases.add(invalid("digits(3,2): 1E+3", digits, new BigDecimal("1E+3"), "digits", digitsMessage));
        cases.add(invalid("digits(3,2): Double infinity", digits, Double.POSITIVE_INFINITY, "digits", digitsMessage));
        // matches: the whole value
        var lower = matches(Pattern.compile("[a-z]+"));
        cases.add(valid("matches([a-z]+): abc", lower, "abc"));
        cases.add(valid("matches([a-z]+): null", lower, null));
        cases.add(invalid("matches([a-z]+): abc1 (find would match)", lower, "abc1", "pattern", "must match the required format"));
        cases.add(invalid("matches([a-z]+): 1abc", lower, "1abc", "pattern", "must match the required format"));
        cases.add(invalid("matches([a-z]+): empty", lower, "", "pattern", "must match the required format"));
        // email (the full table is EmailTests)
        cases.add(valid("email: a@b", email(), "a@b"));
        cases.add(valid("email: null", email(), null));
        cases.add(invalid("email: plain", email(), "plain", "email", "must be a well-formed email address"));
        // oneOf
        Collection<String> colours = List.of("red", "green");
        cases.add(valid("oneOf(red, green): red", oneOf(colours), "red"));
        cases.add(valid("oneOf(red, green): null", oneOf(colours), null));
        cases.add(invalid("oneOf(red, green): blue", oneOf(colours), "blue", "oneOf", "must be one of the allowed values"));
        cases.add(invalid("oneOf(red, green): Red (equals is exact)", oneOf(colours), "Red", "oneOf",
                "must be one of the allowed values"));
        // time against a fixed clock: exactly "now" tells the variants apart
        cases.add(valid("past: yesterday", past(NOW), TODAY.minusDays(1)));
        cases.add(valid("past: null", past(NOW), null));
        cases.add(invalid("past: now", past(NOW), TODAY, "past", "must be in the past"));
        cases.add(invalid("past: tomorrow", past(NOW), TODAY.plusDays(1), "past", "must be in the past"));
        cases.add(valid("pastOrPresent: now", pastOrPresent(NOW), TODAY));
        cases.add(valid("pastOrPresent: null", pastOrPresent(NOW), null));
        cases.add(invalid("pastOrPresent: tomorrow", pastOrPresent(NOW), TODAY.plusDays(1), "pastOrPresent",
                "must be in the past or in the present"));
        cases.add(valid("future: tomorrow", future(NOW), TODAY.plusDays(1)));
        cases.add(valid("future: null", future(NOW), null));
        cases.add(invalid("future: now", future(NOW), TODAY, "future", "must be in the future"));
        cases.add(invalid("future: yesterday", future(NOW), TODAY.minusDays(1), "future", "must be in the future"));
        cases.add(valid("futureOrPresent: now", futureOrPresent(NOW), TODAY));
        cases.add(valid("futureOrPresent: null", futureOrPresent(NOW), null));
        cases.add(invalid("futureOrPresent: yesterday", futureOrPresent(NOW), TODAY.minusDays(1), "futureOrPresent",
                "must be in the future or in the present"));
        cases.add(invalid("past (Instant): now", past(NOW_INSTANT), INSTANT, "past", "must be in the past"));
        cases.add(valid("pastOrPresent (Instant): now", pastOrPresent(NOW_INSTANT), INSTANT));
        cases.add(valid("past (Instant): 1 ns before now", past(NOW_INSTANT), INSTANT.minusNanos(1)));
        cases.add(invalid("future (Instant): now", future(NOW_INSTANT), INSTANT, "future", "must be in the future"));
        cases.add(valid("futureOrPresent (Instant): now", futureOrPresent(NOW_INSTANT), INSTANT));
        return cases;
    }

    static Suite tests() {
        var cases = cases();
        return suite("built-in constraints",
                test("the table has at least 80 cases, valid and invalid ones for every constraint", () -> {
                    assertTrue(cases.size() >= 80, cases.size() + " cases");
                    var constraints = List.of("notNull", "notBlank", "notEmpty", "notEmptyCollection", "notEmptyMap",
                            "length", "minLength", "maxLength", "size", "mapSize", "min", "max", "range",
                            "decimalMin", "decimalMax", "positive", "positiveOrZero", "negative", "negativeOrZero",
                            "digits", "matches", "email", "oneOf", "past", "pastOrPresent", "future", "futureOrPresent");
                    for (var name : constraints) {
                        var prefix = java.util.regex.Pattern.compile("^" + name + "[:( ]");
                        var mine = cases.stream().filter(c -> prefix.matcher(c.label()).find()).toList();
                        assertTrue(mine.stream().anyMatch(c -> c.code() == null), name + " has no valid case");
                        assertTrue(mine.stream().anyMatch(c -> c.code() != null), name + " has no invalid case");
                        var presence = List.of("notNull", "notBlank", "notEmpty", "notEmptyCollection", "notEmptyMap");
                        assertTrue(mine.stream().anyMatch(c -> c.value() == null
                                && (c.code() == null) != presence.contains(name)), name + " has no null case");
                    }
                }),
                test("a value is checked once per check: time is asked for each value", () -> {
                    var asked = new AtomicInteger();
                    var constraint = past(() -> {
                        asked.incrementAndGet();
                        return TODAY;
                    });
                    var validator = Validator.of(constraint);
                    validator.check(TODAY);
                    validator.check(TODAY.minusDays(1));
                    validator.check(null);
                    assertEquals(2, asked.get());
                }),
                test("withMessage and withCode change only what they say", () -> {
                    var custom = notBlank().withMessage("namn saknas").withCode("required");
                    assertEquals("required", custom.code());
                    assertEquals("namn saknas", custom.message());
                    assertEquals(new Validated.Invalid<String>(List.of(new Violation("$", "required", "namn saknas")), 1),
                            Validator.<String>of(custom).check(" "));
                    assertEquals("notBlank", notBlank().code());
                }),
                parameterized("cases", cases, Case::toString, Case::run));
    }
}
