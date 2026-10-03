package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.notNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import spola.test.Suite;

/** Criterion 7: at most 100 violations are kept, all are counted, and the message stays short. */
final class LimitTests {

    private LimitTests() {
    }

    record Items(List<String> items) {
    }

    static final Validator<Items> ITEMS = Validator.<Items>builder()
            .each("items", Items::items, notNull())
            .build();

    static Suite tests() {
        return suite("the limit of 100",
                test("10 000 invalid elements: the first 100 violations in order, count 10 000", () -> {
                    var items = new Items(Arrays.asList(new String[10_000]));
                    var invalid = (Validated.Invalid<Items>) ITEMS.check(items);
                    assertEquals(100, invalid.violations().size());
                    assertEquals(10_000, invalid.count());
                    for (int i = 0; i < 100; i++) {
                        assertEquals(new Violation("$.items[" + i + "]", "notNull", "must not be null"),
                                invalid.violations().get(i));
                    }
                    var e = assertThrows(ValidationException.class, () -> ITEMS.require(items));
                    assertEquals(invalid.violations(), e.violations());
                    assertEquals(10_000, e.count());
                    assertTrue(e.getMessage().length() <= 2_000, e.getMessage().length() + " characters");
                    assertEquals("10000 violations: $.items[0]: must not be null; $.items[1]: must not be null;"
                            + " $.items[2]: must not be null; ... (9997 more)", e.getMessage());
                }),
                test("exactly 100 violations: all kept, count 100", () -> {
                    var invalid = (Validated.Invalid<Items>) ITEMS.check(new Items(Arrays.asList(new String[100])));
                    assertEquals(100, invalid.violations().size());
                    assertEquals(100, invalid.count());
                }),
                test("one violation: the message says 1 violation", () -> {
                    var e = assertThrows(ValidationException.class, () -> ITEMS.require(new Items(Arrays.asList("a", null))));
                    assertEquals("1 violation: $.items[1]: must not be null", e.getMessage());
                }),
                test("long paths and messages still give a message of at most 2 000 characters", () -> {
                    var name = "n".repeat(10_000);
                    Validator<Object> noisy = (value, errors) -> {
                        for (int i = 0; i < 5; i++) {
                            errors.at(name).atKey("kå".repeat(100)).reject("long", "å".repeat(10_000));
                        }
                    };
                    var e = assertThrows(ValidationException.class, () -> noisy.require("x"));
                    assertTrue(e.getMessage().length() <= 2_000, e.getMessage().length() + " characters");
                    assertTrue(e.getMessage().startsWith("5 violations: $." + "n".repeat(100)), e.getMessage());
                    assertTrue(e.getMessage().endsWith("; ... (2 more)"), e.getMessage());
                    var message = e.violations().getFirst().message();
                    assertEquals("å".repeat(500) + "... (10000 characters)", message);
                }),
                test("Invalid refuses inconsistent counts and sizes", () -> {
                    var one = new Violation("$", "x", "x");
                    assertThrows(IllegalArgumentException.class, () -> new Validated.Invalid<>(List.of(), 0));
                    assertThrows(IllegalArgumentException.class, () -> new Validated.Invalid<>(List.of(one), 2));
                    assertThrows(IllegalArgumentException.class, () -> new Validated.Invalid<>(List.of(one), 0));
                    var many = new ArrayList<Violation>();
                    for (int i = 0; i < 101; i++) {
                        many.add(one);
                    }
                    assertThrows(IllegalArgumentException.class, () -> new Validated.Invalid<>(many, 101));
                    assertEquals(500, new Validated.Invalid<>(many.subList(0, 100), 500).count());
                }),
                test("a million violations are counted without keeping or rendering them", () -> {
                    Validator<Object> million = (value, errors) -> {
                        var deep = errors;
                        for (int i = 0; i < 1_000; i++) {
                            deep = deep.at("level" + i);
                        }
                        for (int i = 0; i < 1_000_000; i++) {
                            deep.reject("x", "x");
                        }
                    };
                    var invalid = (Validated.Invalid<Object>) million.check("x");
                    assertEquals(1_000_000, invalid.count());
                    assertEquals(100, invalid.violations().size());
                }));
    }
}
