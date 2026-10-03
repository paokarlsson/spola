package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Criterion 8: a validator of your own as a lambda, and constraints of your own. */
final class CustomValidatorTests {

    private CustomValidatorTests() {
    }

    /**
     * Stock per SKU: keys must be SKUs (reported under {@code keys[i]}, in iteration order),
     * quantities must not be negative (reported at the key).
     */
    static final Validator<Map<String, Integer>> STOCK = (stock, errors) -> {
        int index = 0;
        for (var entry : stock.entrySet()) {
            if (!entry.getKey().matches("[A-Z]{3}-[0-9]{4}")) {
                errors.at("keys").atIndex(index).reject("sku", "must be a SKU like ABC-1234");
            }
            if (entry.getValue() == null) {
                errors.atKey(entry.getKey()).reject("notNull", "must not be null");
            } else if (entry.getValue() < 0) {
                errors.atKey(entry.getKey()).reject("negative", "must not be negative");
            }
            index++;
        }
    };

    record Box(String label, Optional<String> code, List<String> tags) {
    }

    static Suite tests() {
        return suite("validators and constraints of your own",
                test("a lambda validator with at, atIndex, atKey and reject validates a Map<String, Integer>", () -> {
                    var stock = new LinkedHashMap<String, Integer>();
                    stock.put("ABC-1234", 5);
                    stock.put("abc", -1);
                    stock.put("XYZ-0001", null);
                    stock.put("first name", -3);
                    assertEquals(new Validated.Invalid<Map<String, Integer>>(List.of(
                            new Violation("$.keys[1]", "sku", "must be a SKU like ABC-1234"),
                            new Violation("$.abc", "negative", "must not be negative"),
                            new Violation("$[\"XYZ-0001\"]", "notNull", "must not be null"),
                            new Violation("$.keys[3]", "sku", "must be a SKU like ABC-1234"),
                            new Violation("$[\"first name\"]", "negative", "must not be negative")), 5),
                            STOCK.check(stock));
                    var fine = Map.of("ABC-1234", 1);
                    assertEquals(new Validated.Valid<>(fine), STOCK.check(fine));
                }),
                test("a lambda validator nests in a builder at the field's path", () -> {
                    record Shop(Map<String, Integer> stock) {
                    }
                    var shop = Validator.<Shop>builder().nested("stock", Shop::stock, STOCK).build();
                    assertEquals(List.of(new Violation("$.stock.keys[0]", "sku", "must be a SKU like ABC-1234"),
                            new Violation("$.stock.bad", "negative", "must not be negative")),
                            ((Validated.Invalid<Shop>) shop.check(new Shop(Map.of("bad", -1)))).violations());
                }),
                test("Constraint.of with withMessage and withCode is never called with null", () -> {
                    var calls = new AtomicInteger();
                    Constraint<String> shout = Constraint.<String>of("upper", s -> {
                        if (s == null) {
                            throw new AssertionError("called with null");
                        }
                        calls.incrementAndGet();
                        return s.equals(s.toUpperCase(java.util.Locale.ROOT));
                    }, "must be upper case");
                    var custom = shout.withMessage("ska vara versaler").withCode("versaler");
                    var validator = Validator.<Box>builder()
                            .field("label", Box::label, shout, custom)
                            .optional("code", Box::code, custom)
                            .each("tags", Box::tags, custom)
                            .build();
                    assertEquals(new Validated.Valid<>(null), Validator.of(custom).check(null));
                    var nulls = new Box(null, Optional.empty(), Arrays.asList(null, "OK", null));
                    assertEquals(new Validated.Valid<>(nulls), validator.check(nulls));
                    assertEquals(1, calls.get());
                    var lower = new Box("low", Optional.of("x"), Arrays.asList("ok", null));
                    assertEquals(List.of(
                            new Violation("$.label", "upper", "must be upper case"),
                            new Violation("$.label", "versaler", "ska vara versaler"),
                            new Violation("$.code", "versaler", "ska vara versaler"),
                            new Violation("$.tags[0]", "versaler", "ska vara versaler")),
                            ((Validated.Invalid<Box>) validator.check(lower)).violations());
                    assertEquals("upper", shout.code());
                    assertEquals("must be upper case", shout.message());
                }),
                test("codes are printable ASCII without spaces; null arguments are refused", () -> {
                    assertThrows(IllegalArgumentException.class, () -> Constraint.of("", s -> true, "m"));
                    assertThrows(IllegalArgumentException.class, () -> Constraint.of("a b", s -> true, "m"));
                    assertThrows(IllegalArgumentException.class, () -> Constraint.of("a\nb", s -> true, "m"));
                    assertThrows(IllegalArgumentException.class, () -> Constraint.of("å", s -> true, "m"));
                    assertThrows(NullPointerException.class, () -> Constraint.of(null, s -> true, "m"));
                    assertThrows(NullPointerException.class, () -> Constraint.of("c", null, "m"));
                    assertThrows(NullPointerException.class, () -> Constraint.of("c", s -> true, null));
                    assertThrows(NullPointerException.class, () -> Constraints.notBlank().withMessage(null));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.notBlank().withCode(" "));
                    Validator<Object> badCode = (value, errors) -> errors.reject("bad code", "m");
                    assertThrows(IllegalArgumentException.class, () -> badCode.check("x"));
                    Validator<Object> nullMessage = (value, errors) -> errors.reject("c", null);
                    assertThrows(NullPointerException.class, () -> nullMessage.check("x"));
                }),
                test("an Errors used after its validation is over throws", () -> {
                    var kept = new Errors[1];
                    Validator<Object> leaky = (value, errors) -> kept[0] = errors;
                    leaky.check("x");
                    assertThrows(IllegalStateException.class, () -> kept[0].reject("late", "too late"));
                    assertThrows(IllegalStateException.class, () -> kept[0].at("x").reject("late", "too late"));
                }));
    }
}
