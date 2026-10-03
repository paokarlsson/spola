package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.notNull;
import static spola.validation.Constraints.range;

import java.util.List;
import spola.test.Suite;

/**
 * Criterion 5: the paths in {@link Violation#path()}. Each expected text is the one
 * {@code spola.json.codec} gives for the same path; the comment on each test names the codec
 * test (in {@code modules/json/test/spola/json/codec/}) that pins the codec's text. The codec
 * writes {@code \}{@code uXXXX} with lower-case hex digits ({@code Messages.appendEscaped} uses
 * {@code Integer.toHexString}), so ESC is {@code \}{@code u001b} in both.
 */
final class PathTests {

    private PathTests() {
    }

    record Address(String city) {
    }

    record Person(String name, Address address) {
    }

    record Line(String sku, int qty) {
    }

    record Order(List<Line> lines) {
    }

    static final Validator<Address> ADDRESS = Validator.<Address>builder()
            .field("city", Address::city, notBlank())
            .build();

    static final Validator<Person> PERSON = Validator.<Person>builder()
            .field("name", Person::name, notBlank())
            .nested("address", Person::address, ADDRESS)
            .build();

    static final Validator<Order> ORDER = Validator.<Order>builder()
            .eachNested("lines", Order::lines, Validator.<Line>builder().field("qty", Line::qty, range(1, 9)).build())
            .build();

    /** The one path a validator reports for a value. */
    static <T> String pathOf(Validator<T> validator, T value) {
        var result = validator.check(value);
        var invalid = (Validated.Invalid<T>) result;
        assertEquals(1, invalid.violations().size(), invalid.toString());
        return invalid.violations().getFirst().path();
    }

    /** A validator that rejects at the path {@code where} leads to. */
    static Validator<Object> at(java.util.function.Function<Errors, Errors> where) {
        return (value, errors) -> where.apply(errors).reject("x", "x");
    }

    static Suite tests() {
        return suite("paths",
                // codec: ProblemTests "an exception message with line breaks ..." (path "$")
                test("the root is $", () -> assertEquals("$", pathOf(Validator.of(notNull()), null))),
                // codec: MappingTests, problem("$.z", ...) for a declared field
                test("a field is $.name", () -> assertEquals("$.name", pathOf(PERSON, new Person(" ", null)))),
                // codec: ProblemTests, "$." + line1 for a nested member; path of a nested object codec
                test("a nested field is $.address.city", () -> assertEquals("$.address.city",
                        pathOf(PERSON, new Person("Ada", new Address(""))))),
                // codec: ProblemTests "more than 100 problems" ("$[" + i + "]") and member rendering
                test("a list element's field is $.lines[3].qty", () -> {
                    var ok = new Line("a", 1);
                    assertEquals("$.lines[3].qty", pathOf(ORDER, new Order(List.of(ok, ok, ok, new Line("a", 0)))));
                }),
                // codec: MappingTests, problem("$[\"a b\"]", ...) for a name that is not an identifier
                test("a name that is not an identifier is $[\"first name\"]", () -> {
                    assertEquals("$[\"first name\"]", pathOf(at(e -> e.at("first name")), "x"));
                    assertEquals("$[\"first name\"]", pathOf(Validator.<Object>builder()
                            .field("first name", v -> null, notNull()).build(), "x"));
                    assertEquals("$[\"\"]", pathOf(at(e -> e.at("")), "x"));
                    assertEquals("$[\"9lives\"]", pathOf(at(e -> e.at("9lives")), "x"));
                    assertEquals("$._a$9", pathOf(at(e -> e.at("_a$9")), "x"));
                }),
                // codec: ProblemTests "an unknown member named with line breaks, NUL and U+2028
                // is reported escaped" (the same name and the same expected text as the second assertion)
                test("an atKey with a control character is escaped as in the codec", () -> {
                    assertEquals("$[\"a\\u001bb\"]", pathOf(at(e -> e.atKey("a\u001bb")), "x"));
                    assertEquals("$[\"a\\u000ab\\u000dc\\u0000d\\u2028e\"]",
                            pathOf(at(e -> e.atKey("a\nb\rc\u0000d\u2028e")), "x"));
                    assertEquals("$[\"say \\\"hi\\\" \\\\ \\u00e5\"]", pathOf(at(e -> e.atKey("say \"hi\" \\ å")), "x"));
                }),
                // codec: ProblemTests "a long map key is cut in the path, as data from the document"
                test("an atKey longer than 40 characters is cut and followed by its length", () -> {
                    assertEquals("$[\"" + "k".repeat(40) + "\"... (41 characters)]",
                            pathOf(at(e -> e.atKey("k".repeat(41))), "x"));
                    assertEquals("$." + "k".repeat(40), pathOf(at(e -> e.atKey("k".repeat(40))), "x"));
                    // codec: ProblemTests "a long member name is cut in the path"
                    assertEquals("$[\"" + "n".repeat(40) + "\"... (10001 characters)]",
                            pathOf(at(e -> e.atKey("n".repeat(10_000) + "\n")), "x"));
                }),
                // codec: ProblemTests "declared field names over 40 characters are never cut"
                test("a declared name longer than 40 characters is written in full", () -> {
                    var line1 = "shippingAddressAdditionalDeliveryInstructionsLine1";
                    var odd = "shipping address \u00e5 \"line\" 3 " + "x".repeat(40);
                    assertEquals("$." + line1, pathOf(at(e -> e.at(line1)), "x"));
                    assertEquals("$[\"shipping address \\u00e5 \\\"line\\\" 3 " + "x".repeat(40) + "\"]",
                            pathOf(at(e -> e.at(odd)), "x"));
                }),
                test("indexes, keys and names combine; Errors.path() is the same text", () -> {
                    var seen = new java.util.ArrayList<String>();
                    Validator<Object> deep = (value, errors) -> {
                        var here = errors.at("map").atKey("a b").atIndex(0).at("v");
                        seen.add(here.path());
                        here.reject("x", "x");
                    };
                    assertEquals("$.map[\"a b\"][0].v", pathOf(deep, "x"));
                    assertEquals(List.of("$.map[\"a b\"][0].v"), seen);
                }),
                test("a negative index and null names are refused", () -> {
                    Validator<Object> negative = (value, errors) -> errors.atIndex(-1);
                    assertThrows(IllegalArgumentException.class, () -> negative.check("x"));
                    Validator<Object> nullName = (value, errors) -> errors.at(null);
                    assertThrows(NullPointerException.class, () -> nullName.check("x"));
                    Validator<Object> nullKey = (value, errors) -> errors.atKey(null);
                    assertThrows(NullPointerException.class, () -> nullKey.check("x"));
                }));
    }
}
