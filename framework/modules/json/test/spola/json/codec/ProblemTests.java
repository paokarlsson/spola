package spola.json.codec;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodec.optionalField;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.list;
import static spola.json.codec.Problems.problem;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import spola.json.Json;
import spola.json.JsonArray;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;
import spola.test.Suite;

/** Every problem of a document at once, with its path; at most 100; safe messages. */
final class ProblemTests {

    private ProblemTests() {
    }

    record Customer(String name, Model.Status status) {
    }

    record Line(String sku, int qty) {
    }

    record Order(String id, String currency, Customer customer, List<Line> lines, Optional<String> note) {
    }

    static final ObjectCodec<Customer> CUSTOMER = JsonCodec.object(Customer::new,
            field("name", STRING, Customer::name),
            field("status", Model.STATUS, Customer::status));

    static final ObjectCodec<Line> LINE = JsonCodec.object(Line::new,
            field("sku", STRING, Line::sku),
            field("qty", INT, Line::qty));

    static final ObjectCodec<Order> ORDER = JsonCodec.object(Order::new,
            field("id", STRING, Order::id),
            field("currency", STRING, Order::currency),
            field("customer", CUSTOMER, Order::customer),
            field("lines", list(LINE), Order::lines),
            optionalField("note", STRING, Order::note));

    /** A value whose compact constructor throws a long message with line breaks. */
    record Code(String value) {
        Code {
            if (!value.equals("ok")) {
                throw new IllegalArgumentException("bad code\nFAKE LOG LINE\r\n" + "x".repeat(10_000));
            }
        }
    }

    static final ObjectCodec<Code> CODE = JsonCodec.object(Code::new, field("value", STRING, Code::value));

    static Suite tests() {
        return suite("problems",
                test("a document with problems on several levels gives every problem, in document order", () -> {
                    var document = """
                            {
                              "id": 17,
                              "customer": {"name": "Ada", "status": "PAUSED"},
                              "coupon": "FREE",
                              "lines": [
                                {"sku": "a", "qty": 1},
                                {"sku": "b", "qty": 2},
                                {"sku": "c", "qty": 3},
                                {"sku": "d", "qty": "four"},
                                {"qty": 5}
                              ],
                              "note": null
                            }
                            """;
                    var e = Problems.exception(() -> ORDER.read(document));
                    assertEquals(List.of(
                            problem("$.id", "expected string but found number"),
                            problem("$.customer.status", "unknown value \"PAUSED\"; expected one of \"ACTIVE\", \"ON_HOLD\""),
                            problem("$.coupon", "unknown member; expected one of \"id\", \"currency\", \"customer\", \"lines\", \"note\""),
                            problem("$.lines[3].qty", "expected int but found string"),
                            problem("$.lines[4].sku", "missing member"),
                            problem("$.currency", "missing member")), e.problems());
                    assertEquals("6 problems: $.id: expected string but found number; $.customer.status: unknown value"
                            + " \"PAUSED\"; expected one of \"ACTIVE\", \"ON_HOLD\"; $.coupon: unknown member; expected one of"
                            + " \"id\", \"currency\", \"customer\", \"lines\", \"note\"; $.lines[3].qty: expected int but found"
                            + " string; $.lines[4].sku: missing member; $.currency: missing member", e.getMessage());
                }),
                test("the same problems from a tree built in code", () -> {
                    var tree = JsonObject.builder()
                            .put("id", 17)
                            .put("customer", JsonObject.of("name", JsonString.of("Ada")))
                            .put("lines", JsonArray.of(JsonString.of("not a line")))
                            .build();
                    assertEquals(List.of(
                            problem("$.id", "expected string but found number"),
                            problem("$.customer.status", "missing member"),
                            problem("$.lines[0]", "expected object but found string"),
                            problem("$.currency", "missing member")),
                            Problems.of(() -> ORDER.decode(tree)));
                }),
                test("a list of 10 000 bad elements gives 100 problems and one that counts the rest", () -> {
                    var elements = new ArrayList<JsonValue>();
                    for (int i = 0; i < 10_000; i++) {
                        elements.add(JsonString.of("x" + i));
                    }
                    var text = Json.write(JsonArray.copyOf(elements));
                    var e = Problems.exception(() -> list(INT).read(text));
                    assertEquals(101, e.problems().size());
                    for (int i = 0; i < 100; i++) {
                        assertEquals(problem("$[" + i + "]", "expected int but found string"), e.problems().get(i));
                    }
                    assertEquals(problem("$", "9900 more problems not listed (at most 100 are listed)"), e.problems().get(100));
                    assertTrue(e.getMessage().length() <= 2_000, e.getMessage().length() + " characters");
                    assertTrue(e.getMessage().startsWith("more than 100 problems: $[0]: expected int but found string; "),
                            e.getMessage());
                    assertTrue(e.getMessage().matches(".*; \\.\\.\\. \\(\\d+ more\\)"), e.getMessage());
                }),
                test("exactly 100 problems are listed without a summary", () -> {
                    var elements = new ArrayList<JsonValue>();
                    for (int i = 0; i < 100; i++) {
                        elements.add(JsonString.of("x"));
                    }
                    var e = Problems.exception(() -> list(INT).decode(JsonArray.copyOf(elements)));
                    assertEquals(100, e.problems().size());
                    assertEquals(problem("$[99]", "expected int but found string"), e.problems().getLast());
                    assertTrue(e.getMessage().startsWith("100 problems: "), e.getMessage());
                }),
                test("an unknown member named with line breaks, NUL and U+2028 is reported escaped", () -> {
                    var name = "a\nb\rc\u0000d e";
                    var json = JsonObject.of("value", JsonString.of("ok"), name, JsonString.of("x"));
                    var e = Problems.exception(() -> CODE.decode(json));
                    assertEquals(List.of(problem("$[\"a\\u000ab\\u000dc\\u0000d\\u2028e\"]",
                            "unknown member; expected one of \"value\"")), e.problems());
                    assertTrue(e.getMessage().contains("$[\"a\\u000ab\\u000dc\\u0000d\\u2028e\"]"), e.getMessage());
                }),
                test("a long member name is cut in the path", () -> {
                    var name = "n".repeat(10_000) + "\n";
                    var json = JsonObject.of("value", JsonString.of("ok"), name, JsonString.of("x"));
                    var e = Problems.exception(() -> CODE.decode(json));
                    assertEquals("$[\"" + "n".repeat(40) + "\"... (10001 characters)]", e.problems().getFirst().path());
                }),
                test("an exception message with line breaks and 10 000 characters is cleaned and cut", () -> {
                    var e = Problems.exception(() -> CODE.read("{\"value\": \"bad\"}"));
                    assertEquals(1, e.problems().size());
                    var problem = e.problems().getFirst();
                    assertEquals("$", problem.path());
                    var expected = "invalid value: bad code\\u000aFAKE LOG LINE\\u000d\\u000a"
                            + "x".repeat(200 - "bad code\nFAKE LOG LINE\r\n".length()) + "... (10024 characters)";
                    assertEquals(expected, problem.message());
                    assertEquals("1 problem: $: " + expected, e.getMessage());
                }),
                test("a value from the document is quoted with at most 40 characters", () -> {
                    var value = "‮" + "v".repeat(1_000);
                    var e = Problems.exception(() -> Model.STATUS.decode(JsonString.of(value)));
                    assertEquals(List.of(problem("$", "unknown value \"\\u202e" + "v".repeat(39)
                            + "\"... (1001 characters); expected one of \"ACTIVE\", \"ON_HOLD\"")), e.problems());
                }),
                test("100 problems with long paths and messages still give a message of at most 2 000 characters", () -> {
                    var bad = JsonObject.of("value", JsonString.of("bad"));
                    var elements = new ArrayList<JsonValue>();
                    for (int i = 0; i < 150; i++) {
                        elements.add(bad);
                    }
                    var e = Problems.exception(() -> list(CODE).decode(JsonArray.copyOf(elements)));
                    assertEquals(101, e.problems().size());
                    assertTrue(e.getMessage().length() <= 2_000, e.getMessage().length() + " characters");
                    Problems.assertSafe(e.getMessage());
                }));
    }
}
