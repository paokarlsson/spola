package spola.validation;

import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.notNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import spola.test.Suite;

/** Criterion 10: exceptions from application code propagate unchanged; wrong parameters fail early. */
final class UserCodeTests {

    private UserCodeTests() {
    }

    record Thing(String name) {
    }

    static final RuntimeException BOOM = new IllegalStateException("boom");

    /** The same exception instance comes out of both check and require. */
    static <T> void propagates(Validator<T> validator, T value) {
        assertSame(BOOM, assertThrows(IllegalStateException.class, () -> validator.check(value)));
        assertSame(BOOM, assertThrows(IllegalStateException.class, () -> validator.require(value)));
    }

    static Suite tests() {
        Function<Thing, String> throwingGetter = t -> {
            throw BOOM;
        };
        return suite("application code and parameters",
                test("an exception from a getter propagates unchanged from check and require", () -> {
                    propagates(Validator.<Thing>builder().field("name", throwingGetter, notBlank()).build(), new Thing("a"));
                    propagates(Validator.<Thing>builder().nested("name", throwingGetter, Validator.of(notNull())).build(),
                            new Thing("a"));
                }),
                test("an exception from a predicate propagates unchanged, also after earlier violations", () -> {
                    var throwing = Constraint.<String>of("x", s -> {
                        throw BOOM;
                    }, "x");
                    propagates(Validator.<Thing>builder()
                            .field("name", Thing::name, notBlank(), throwing).build(), new Thing(" "));
                    propagates(Validator.<Thing>builder().rule(t -> {
                        throw BOOM;
                    }, "r", "r").build(), new Thing("a"));
                    propagates(Validator.<Thing>builder().when(t -> {
                        throw BOOM;
                    }, Validator.of(notNull())).build(), new Thing("a"));
                }),
                test("an exception from a validator of your own propagates unchanged", () -> {
                    Validator<Thing> own = (value, errors) -> {
                        errors.reject("first", "first");
                        throw BOOM;
                    };
                    propagates(own, new Thing("a"));
                    propagates(Validator.<Thing>builder().include(own).build(), new Thing("a"));
                    propagates(Validator.<List<Thing>>builder().eachNested("all", l -> l, own).build(),
                            List.of(new Thing("a")));
                }),
                test("errors (not only exceptions) propagate too", () -> {
                    var error = new StackOverflowError("deep");
                    Validator<Thing> own = (value, errors) -> {
                        throw error;
                    };
                    assertSame(error, assertThrows(StackOverflowError.class, () -> own.check(new Thing("a"))));
                }),
                test("wrong parameters to Constraints throw IllegalArgumentException when made", () -> {
                    assertThrows(IllegalArgumentException.class, () -> Constraints.length(5, 1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.length(-1, 1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.minLength(-1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.maxLength(-1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.matches(null));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.size(2, 1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.mapSize(-1, 1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.range(5, 1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.digits(-1, 0));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.digits(0, -1));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.decimalMin(null, true));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.decimalMax(null, false));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.oneOf(null));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.oneOf(List.of()));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.oneOf(java.util.Arrays.asList("a", null)));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.past(null));
                    assertThrows(IllegalArgumentException.class, () -> Constraints.futureOrPresent(null));
                    Constraints.decimalMin(BigDecimal.ONE, true); // fine
                }),
                test("wrong arguments to the builder throw when the step is added", () -> {
                    var builder = Validator.<Thing>builder();
                    assertThrows(IllegalArgumentException.class, () -> builder.field("", Thing::name, notBlank()));
                    assertThrows(NullPointerException.class, () -> builder.field(null, Thing::name, notBlank()));
                    assertThrows(NullPointerException.class, () -> builder.field("name", null, notBlank()));
                    assertThrows(NullPointerException.class, () -> builder.field("name", Thing::name, notBlank(), null));
                    assertThrows(NullPointerException.class,
                            () -> builder.field("name", Thing::name, (Constraint<Object>[]) null));
                    assertThrows(NullPointerException.class, () -> builder.nested("name", Thing::name, null));
                    assertThrows(IllegalArgumentException.class, () -> builder.nested("", Thing::name, Validator.of()));
                    assertThrows(NullPointerException.class, () -> builder.eachNested("all", null, Validator.of()));
                    assertThrows(NullPointerException.class, () -> builder.each("all", t -> List.of(), (Constraint<Object>) null));
                    assertThrows(NullPointerException.class, () -> builder.optional("o", null, notNull()));
                    assertThrows(NullPointerException.class, () -> builder.rule(null, "c", "m"));
                    assertThrows(IllegalArgumentException.class, () -> builder.rule(t -> true, "", "m"));
                    assertThrows(NullPointerException.class, () -> builder.rule(t -> true, "c", null));
                    assertThrows(IllegalArgumentException.class, () -> builder.rule("", t -> true, "c", "m"));
                    assertThrows(NullPointerException.class, () -> builder.include(null));
                    assertThrows(NullPointerException.class, () -> builder.when(null, Validator.of()));
                    assertThrows(NullPointerException.class, () -> builder.when(t -> true, null));
                    assertThrows(NullPointerException.class, () -> Validator.of((Constraint<Object>) null));
                    assertThrows(NullPointerException.class, () -> Validator.lazy(null));
                    var lazy = Validator.<Thing>lazy(() -> null);
                    assertThrows(NullPointerException.class, () -> lazy.check(new Thing("a")));
                }),
                test("a supplier of the current time that returns null fails the check", () -> {
                    var past = Validator.of(Constraints.<java.time.LocalDate>past(() -> null));
                    assertThrows(NullPointerException.class, () -> past.check(java.time.LocalDate.MIN));
                }));
    }
}
