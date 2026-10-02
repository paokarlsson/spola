package spola.json.codec;

import static spola.json.codec.JsonCodecs.BIG_DECIMAL;
import static spola.json.codec.JsonCodecs.BIG_INTEGER;
import static spola.json.codec.JsonCodecs.BOOLEAN;
import static spola.json.codec.JsonCodecs.DOUBLE;
import static spola.json.codec.JsonCodecs.DURATION;
import static spola.json.codec.JsonCodecs.INSTANT;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.LOCAL_DATE;
import static spola.json.codec.JsonCodecs.LOCAL_DATE_TIME;
import static spola.json.codec.JsonCodecs.LOCAL_TIME;
import static spola.json.codec.JsonCodecs.LONG;
import static spola.json.codec.JsonCodecs.OFFSET_DATE_TIME;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.UUID;
import static spola.json.codec.JsonCodecs.map;
import static spola.json.codec.Problems.problem;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import spola.json.JsonNumber;
import spola.test.Suite;

/** No silent coercion: each scalar codec accepts exactly its JSON type and range. */
final class StrictnessTests {

    private StrictnessTests() {
    }

    /** {@code json} decoded by {@code codec} as the member {@code value} is rejected with {@code message}. */
    record Rejected(String label, JsonCodec<?> codec, String json, String message) {
    }

    /** {@code json} decoded by {@code codec} gives {@code expected}. */
    record Accepted(String label, JsonCodec<?> codec, String json, Object expected) {
    }

    static final List<Rejected> REJECTED = List.of(
            new Rejected("\"42\" as INT", INT, "\"42\"", "expected int but found string"),
            new Rejected("42 as STRING", STRING, "42", "expected string but found number"),
            new Rejected("true as STRING", STRING, "true", "expected string but found boolean"),
            new Rejected("null as STRING", STRING, "null", "expected string but found null"),
            new Rejected("{} as STRING", STRING, "{}", "expected string but found object"),
            new Rejected("1.5 as INT", INT, "1.5", "expected int but found 1.5 (not an integer)"),
            new Rejected("2147483648 as INT", INT, "2147483648", "expected int but found 2147483648 (outside the range of int)"),
            new Rejected("-2147483649 as INT", INT, "-2147483649", "expected int but found -2147483649 (outside the range of int)"),
            new Rejected("1e10 as INT", INT, "1e10", "expected int but found 1e10 (outside the range of int)"),
            new Rejected("null as INT", INT, "null", "expected int but found null"),
            new Rejected("[] as INT", INT, "[]", "expected int but found array"),
            new Rejected("9223372036854775808 as LONG", LONG, "9223372036854775808",
                    "expected long but found 9223372036854775808 (outside the range of long)"),
            new Rejected("0.5e1 as LONG is 5, but 0.55e1 is not", LONG, "0.55e1", "expected long but found 0.55e1 (not an integer)"),
            new Rejected("1e400 as DOUBLE", DOUBLE, "1e400", "expected double but found 1e400 (outside the range of double)"),
            new Rejected("-1e400 as DOUBLE", DOUBLE, "-1e400", "expected double but found -1e400 (outside the range of double)"),
            new Rejected("1e-400 as DOUBLE", DOUBLE, "1e-400", "expected double but found 1e-400 (too small for double; it would be 0)"),
            new Rejected("\"1.5\" as DOUBLE", DOUBLE, "\"1.5\"", "expected double but found string"),
            new Rejected("1e1000000000 as BIG_INTEGER", BIG_INTEGER, "1e1000000000",
                    "expected integer but found 1e1000000000 (more than 10000 digits)"),
            new Rejected("1.5 as BIG_INTEGER", BIG_INTEGER, "1.5", "expected integer but found 1.5 (not an integer)"),
            new Rejected("1e1000000000 as BIG_DECIMAL", BIG_DECIMAL, "1e1000000000",
                    "expected decimal but found 1e1000000000 (the exponent is outside +-10000)"),
            new Rejected("1e-99999999999 as BIG_DECIMAL", BIG_DECIMAL, "1e-99999999999",
                    "expected decimal but found 1e-99999999999 (the exponent is outside +-10000)"),
            new Rejected("\"1.5\" as BIG_DECIMAL", BIG_DECIMAL, "\"1.5\"", "expected decimal but found string"),
            new Rejected("\"true\" as BOOLEAN", BOOLEAN, "\"true\"", "expected boolean but found string"),
            new Rejected("1 as BOOLEAN", BOOLEAN, "1", "expected boolean but found number"),
            new Rejected("invalid UUID", UUID, "\"not-a-uuid\"",
                    "expected a UUID (8-4-4-4-12 hex digits) but found \"not-a-uuid\""),
            new Rejected("short UUID that UUID.fromString accepts", UUID, "\"1-2-3-4-5\"",
                    "expected a UUID (8-4-4-4-12 hex digits) but found \"1-2-3-4-5\""),
            new Rejected("UUID as a number", UUID, "42", "expected a UUID string but found number"),
            new Rejected("2026-02-30 as LOCAL_DATE", LOCAL_DATE, "\"2026-02-30\"",
                    "expected an ISO-8601 date such as 2026-10-02 but found \"2026-02-30\""),
            new Rejected("2026-1-2 as LOCAL_DATE", LOCAL_DATE, "\"2026-1-2\"",
                    "expected an ISO-8601 date such as 2026-10-02 but found \"2026-1-2\""),
            new Rejected("a date as a number", LOCAL_DATE, "20261002",
                    "expected an ISO-8601 date such as 2026-10-02 but found number"),
            new Rejected("Instant without zone", INSTANT, "\"2026-10-02T09:18:00\"",
                    "expected an ISO-8601 instant such as 2026-10-02T09:18:00Z but found \"2026-10-02T09:18:00\""),
            new Rejected("Instant as epoch seconds", INSTANT, "1759396680",
                    "expected an ISO-8601 instant such as 2026-10-02T09:18:00Z but found number"),
            new Rejected("25:00 as LOCAL_TIME", LOCAL_TIME, "\"25:00\"",
                    "expected an ISO-8601 time such as 11:18:30 but found \"25:00\""),
            new Rejected("a date as LOCAL_DATE_TIME", LOCAL_DATE_TIME, "\"2026-10-02\"",
                    "expected an ISO-8601 date and time such as 2026-10-02T11:18:30 but found \"2026-10-02\""),
            new Rejected("OFFSET_DATE_TIME without offset", OFFSET_DATE_TIME, "\"2026-10-02T11:18:30\"",
                    "expected an ISO-8601 date and time with offset such as 2026-10-02T11:18:30+02:00"
                            + " but found \"2026-10-02T11:18:30\""),
            new Rejected("invalid Duration", DURATION, "\"1 hour\"",
                    "expected an ISO-8601 duration such as PT1H30M but found \"1 hour\""),
            new Rejected("Duration that overflows", DURATION, "\"PT99999999999999999999H\"",
                    "expected an ISO-8601 duration such as PT1H30M but found \"PT99999999999999999999H\""),
            new Rejected("unknown enum name", Model.STATUS, "\"PAUSED\"",
                    "unknown value \"PAUSED\"; expected one of \"ACTIVE\", \"ON_HOLD\""),
            new Rejected("enum name in another case", Model.STATUS, "\"active\"",
                    "unknown value \"active\"; expected one of \"ACTIVE\", \"ON_HOLD\""),
            new Rejected("enum as a number", Model.STATUS, "0",
                    "expected a string (one of \"ACTIVE\", \"ON_HOLD\") but found number"),
            new Rejected("enum with own names given the Java name", Model.COLOR, "\"DARK_BLUE\"",
                    "unknown value \"DARK_BLUE\"; expected one of \"red\", \"dark-blue\""));

    static final List<Accepted> ACCEPTED = List.of(
            new Accepted("1.0 as INT", INT, "1.0", 1),
            new Accepted("1e2 as INT", INT, "1e2", 100),
            new Accepted("-0 as INT", INT, "-0", 0),
            new Accepted("-2147483648 as INT", INT, "-2147483648", Integer.MIN_VALUE),
            new Accepted("1E+18 as LONG", LONG, "1E+18", 1_000_000_000_000_000_000L),
            new Accepted("0.5e1 as LONG", LONG, "0.5e1", 5L),
            new Accepted("1.7976931348623157e308 as DOUBLE", DOUBLE, "1.7976931348623157e308", Double.MAX_VALUE),
            new Accepted("4.9e-324 as DOUBLE", DOUBLE, "4.9e-324", Double.MIN_VALUE),
            new Accepted("0e-999 as DOUBLE", DOUBLE, "0e-999", 0.0),
            new Accepted("42 as DOUBLE", DOUBLE, "42", 42.0),
            new Accepted("1e100 as BIG_INTEGER", BIG_INTEGER, "1e100", BigInteger.TEN.pow(100)),
            new Accepted("1.10 as BIG_DECIMAL keeps the scale", BIG_DECIMAL, "1.10", new BigDecimal("1.10")),
            new Accepted("1e10000 as BIG_DECIMAL", BIG_DECIMAL, "1e10000", new BigDecimal("1e10000")),
            new Accepted("upper case UUID", UUID, "\"123E4567-E89B-12D3-A456-426614174000\"",
                    java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
            new Accepted("2024-02-29 as LOCAL_DATE", LOCAL_DATE, "\"2024-02-29\"", LocalDate.of(2024, 2, 29)),
            new Accepted("Instant with offset", INSTANT, "\"2026-10-02T11:18:00+02:00\"",
                    Instant.parse("2026-10-02T09:18:00Z")),
            new Accepted("string with escapes", STRING, "\"a\\nb\\u2028\"", "a\nb "),
            new Accepted("enum with own name", Model.COLOR, "\"dark-blue\"", Model.Color.DARK_BLUE));

    static Suite tests() {
        return suite("strict scalars",
                test("the table has at least 30 rejected cases", () -> assertTrue(REJECTED.size() >= 30)),
                parameterized("rejected", REJECTED, Rejected::label, c -> {
                    var codec = map(c.codec());
                    var problems = Problems.of(() -> codec.read("{\"value\": " + c.json() + "}"));
                    assertEquals(List.of(problem("$.value", c.message())), problems);
                    // the same at the root
                    var root = Problems.of(() -> c.codec().read(c.json()));
                    assertEquals(List.of(problem("$", c.message())), root);
                }),
                parameterized("accepted", ACCEPTED, Accepted::label, c -> {
                    assertEquals(c.expected(), c.codec().read(c.json()));
                }),
                test("1e1000000000 as BIG_INTEGER fails within one second", () -> {
                    long start = System.nanoTime();
                    var problems = Problems.of(() -> BIG_INTEGER.read("1e1000000000"));
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    assertEquals(1, problems.size());
                    assertTrue(millis < 1_000, millis + " ms");
                }),
                test("1e1000000000 as BIG_DECIMAL and every integer type fails within one second", () -> {
                    long start = System.nanoTime();
                    for (var codec : List.<JsonCodec<?>>of(INT, LONG, BIG_INTEGER, BIG_DECIMAL, DOUBLE)) {
                        Problems.of(() -> codec.read("1e1000000000"));
                        Problems.of(() -> codec.read("-1e1000000000"));
                    }
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    assertTrue(millis < 1_000, millis + " ms");
                }),
                test("writing NaN or an infinity as DOUBLE fails with the path", () -> {
                    var codec = map(DOUBLE);
                    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                        var problems = Problems.of(() -> codec.encode(java.util.Map.of("x", bad)));
                        assertEquals(List.of(problem("$.x", bad + " is not a JSON number (RFC 8259 section 6)")), problems);
                    }
                }),
                test("writing a BigInteger with more than 10 000 digits or a BigDecimal with a huge scale fails", () -> {
                    assertEquals(List.of(problem("$", "the integer has more than 10000 digits")),
                            Problems.of(() -> BIG_INTEGER.encode(BigInteger.TEN.pow(10_000))));
                    assertEquals(JsonNumber.of(BigInteger.TEN.pow(9_999)), BIG_INTEGER.encode(BigInteger.TEN.pow(9_999)));
                    assertEquals(List.of(problem("$", "the scale -10001 is outside +-10000")),
                            Problems.of(() -> BIG_DECIMAL.encode(new BigDecimal("1e10001"))));
                }),
                test("writing a string with an unpaired surrogate fails with the path", () -> {
                    var problems = Problems.of(() -> map(STRING).encode(java.util.Map.of("s", "a\uD800")));
                    assertEquals(1, problems.size());
                    assertEquals("$.s", problems.getFirst().path());
                    assertTrue(problems.getFirst().message().startsWith("the string has no JSON form: "),
                            problems.getFirst().message());
                }));
    }
}
