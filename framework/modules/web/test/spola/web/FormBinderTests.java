package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;
import spola.validation.Validated;
import spola.validation.Validator;

/** Criteria 5, 6 and 8: FormBinder and FormFields without a server. */
final class FormBinderTests {

    private FormBinderTests() {
    }

    enum Color { RED, GREEN }

    static final Param<Color> COLOR = Param.enumOf(Color.class);

    /** A form from an encoded body, parsed exactly as readForm parses one. */
    static FormData form(String body) {
        return new FormData(Decoding.parseQuery(body, "The form body"));
    }

    static Problem.FieldError error(String path, String message, String code) {
        return new Problem.FieldError(path, message, Optional.of(code));
    }

    /**
     * One row of the table: a form, a binder, and either the exact value or the exact errors.
     *
     * @param name   what the row shows
     * @param body   the encoded form body
     * @param binder the binder
     * @param value  the expected value (when {@code errors} is empty)
     * @param errors the expected errors, in order
     */
    record Case(String name, String body, FormBinder<?> binder, Object value, List<Problem.FieldError> errors) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Case ok(String name, String body, FormBinder<?> binder, Object value) {
        return new Case(name, body, binder, value, List.of());
    }

    static Case fails(String name, String body, FormBinder<?> binder, Problem.FieldError... errors) {
        return new Case(name, body, binder, null, List.of(errors));
    }

    record Pair(Object a, Object b) {
    }

    static final Problem.FieldError SINGLE_A = error("$.a", "must have a single value", "single");

    static final List<Case> CASES = List.of(
            // required
            ok("required STRING present", "email=ada%40example.com", f -> f.required("email", Param.STRING),
                    "ada@example.com"),
            fails("required missing", "other=1", f -> f.required("email", Param.STRING),
                    error("$.email", "is required", "required")),
            fails("required INT of the wrong type", "age=old", f -> f.required("age", Param.INT),
                    error("$.age", "expected int", "type")),
            ok("required INT present", "age=36", f -> f.required("age", Param.INT), 36),
            // optional
            ok("optional missing", "x=1", f -> f.optional("age", Param.INT), Optional.empty()),
            ok("optional INT empty", "age=", f -> f.optional("age", Param.INT), Optional.empty()),
            ok("optional INT present", "age=7", f -> f.optional("age", Param.INT), Optional.of(7)),
            ok("optional STRING empty is a value", "nick=", f -> f.optional("nick", Param.STRING), Optional.of("")),
            fails("optional of the wrong type", "age=x", f -> f.optional("age", Param.INT),
                    error("$.age", "expected int", "type")),
            // empty values
            fails("empty INT counts as missing", "n=", f -> f.required("n", Param.INT),
                    error("$.n", "is required", "required")),
            fails("empty LONG counts as missing", "n=", f -> f.required("n", Param.LONG),
                    error("$.n", "is required", "required")),
            fails("empty UUID counts as missing", "n=", f -> f.required("n", Param.UUID),
                    error("$.n", "is required", "required")),
            fails("empty LOCAL_DATE counts as missing", "n", f -> f.required("n", Param.LOCAL_DATE),
                    error("$.n", "is required", "required")),
            fails("empty BIG_DECIMAL counts as missing", "n=", f -> f.required("n", Param.BIG_DECIMAL),
                    error("$.n", "is required", "required")),
            ok("empty STRING is the value \"\"", "s=", f -> f.required("s", Param.STRING), ""),
            // more than one value
            fails("two values for required", "a=1&a=1", f -> f.required("a", Param.INT), SINGLE_A),
            fails("two values for optional", "a=1&a=2", f -> f.optional("a", Param.STRING), SINGLE_A),
            fails("two values for flag", "a=on&a=on", f -> f.flag("a"), SINGLE_A),
            fails("two empty values for optional", "a=&a=", f -> f.optional("a", Param.INT), SINGLE_A),
            // all
            ok("all with no value", "x=1", f -> f.all("n", Param.INT), List.of()),
            ok("all with one value", "n=5", f -> f.all("n", Param.INT), List.of(5)),
            ok("all with several values, in order", "n=3&x=0&n=1&n=2", f -> f.all("n", Param.INT), List.of(3, 1, 2)),
            fails("all with errors at index 0 and 2", "n=x&n=2&n=1.5&n=4", f -> f.all("n", Param.INT),
                    error("$.n[0]", "expected int", "type"), error("$.n[2]", "expected int", "type")),
            ok("all leaves out empty INT values", "n=1&n=&n=3", f -> f.all("n", Param.INT), List.of(1, 3)),
            fails("all keeps the position after an empty value", "n=&n=x", f -> f.all("n", Param.INT),
                    error("$.n[1]", "expected int", "type")),
            ok("all keeps empty STRING values", "s=a&s=&s=b", f -> f.all("s", Param.STRING), List.of("a", "", "b")),
            // flag
            ok("flag on", "t=on", f -> f.flag("t"), true),
            ok("flag true", "t=true", f -> f.flag("t"), true),
            ok("flag 1", "t=1", f -> f.flag("t"), true),
            ok("flag false", "t=false", f -> f.flag("t"), false),
            ok("flag 0", "t=0", f -> f.flag("t"), false),
            ok("flag missing", "x=on", f -> f.flag("t"), false),
            ok("flag empty", "t=", f -> f.flag("t"), false),
            fails("flag yes", "t=yes", f -> f.flag("t"), error("$.t", "expected boolean", "type")),
            fails("flag ON (case-sensitive)", "t=ON", f -> f.flag("t"), error("$.t", "expected boolean", "type")),
            // enumOf and other Params
            ok("enumOf", "c=GREEN", f -> f.required("c", COLOR), Color.GREEN),
            fails("enumOf with an unknown name", "c=green", f -> f.required("c", COLOR),
                    error("$.c", "expected Color", "type")),
            ok("LOCAL_DATE", "d=2024-02-29", f -> f.required("d", Param.LOCAL_DATE), LocalDate.of(2024, 2, 29)),
            fails("LOCAL_DATE that is no date", "d=2023-02-29", f -> f.required("d", Param.LOCAL_DATE),
                    error("$.d", "expected date (yyyy-MM-dd)", "type")),
            ok("BIG_DECIMAL", "p=12.50", f -> f.required("p", Param.BIG_DECIMAL), new BigDecimal("12.50")),
            ok("UUID", "u=123e4567-e89b-12d3-a456-426614174000", f -> f.required("u", Param.UUID),
                    UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
            fails("LONG out of range", "n=9223372036854775808", f -> f.required("n", Param.LONG),
                    error("$.n", "expected long", "type")),
            fails("an own Param whose parser throws", "o=A-1", f -> f.required("o", Param.of("order id", text -> {
                throw new IllegalArgumentException("no order " + text);
            })), error("$.o", "expected order id", "type")),
            // names
            ok("a name that is not an identifier", "first+name=Ada", f -> f.required("first name", Param.STRING), "Ada"),
            fails("a missing name that is not an identifier", "x=1", f -> f.required("first name", Param.STRING),
                    error("$[\"first name\"]", "is required", "required")),
            fails("a name with a control character, escaped", "a%01b=x", f -> f.required("a\u0001b", Param.INT),
                    error("$[\"a\\u0001b\"]", "expected int", "type")),
            // unknown fields and order
            ok("unknown fields are ignored", "_csrf=t0k3n&email=a&submit=Send&age=x",
                    f -> f.required("email", Param.STRING), "a"),
            fails("errors follow the binder's order, not the form's", "b=x&a=y",
                    f -> new Pair(f.required("a", Param.INT), f.required("b", Param.INT)),
                    error("$.a", "expected int", "type"), error("$.b", "expected int", "type")),
            fails("the other order of the binder", "b=x&a=y",
                    f -> new Pair(f.required("b", Param.INT), f.required("a", Param.INT)),
                    error("$.b", "expected int", "type"), error("$.a", "expected int", "type")),
            fails("every kind of error in one binding", "email=&age=x&age=y&t=maybe&n=1&n=z",
                    f -> List.of(String.valueOf(f.required("email", Param.INT)), f.optional("age", Param.INT),
                            f.flag("t"), f.all("n", Param.INT), String.valueOf(f.required("gone", Param.STRING))),
                    error("$.email", "is required", "required"), error("$.age", "must have a single value", "single"),
                    error("$.t", "expected boolean", "type"), error("$.n[1]", "expected int", "type"),
                    error("$.gone", "is required", "required")),
            ok("the SignupApp binder", "email=a%40b.se&password=pw&interests=x&interests=y&terms=on&age=",
                    SignupApp.SIGNUP, new SignupApp.Signup("a@b.se", "pw", Optional.empty(), List.of("x", "y"), true)));

    static Suite tests() {
        return suite("FormBinder and FormFields",
                parameterized("the binding table", CASES, c -> {
                    var data = form(c.body());
                    if (c.errors().isEmpty()) {
                        assertEquals(c.value(), c.binder().bind(data));
                    } else {
                        var e = assertThrows(WebException.class, () -> c.binder().bind(data));
                        assertEquals(400, e.status());
                        assertEquals(c.errors(), e.errors());
                        int n = c.errors().size();
                        assertEquals("Request body has " + n + (n == 1 ? " problem." : " problems."),
                                e.detail().orElseThrow());
                    }
                }),
                test("the table has at least 35 rows", () -> assertTrue(CASES.size() >= 35, "rows: " + CASES.size())),
                limits(),
                paths());
    }

    record Required(String email) {
        Required {
            Objects.requireNonNull(email, "email");
        }
    }

    static Suite limits() {
        return suite("the limit of 100 and the exception rule",
                test("1 000 invalid values: exactly the first 100 errors and every one counted", () -> {
                    var body = new StringBuilder();
                    for (int i = 0; i < 1_000; i++) {
                        body.append(i == 0 ? "" : "&").append("n=x").append(i);
                    }
                    FormBinder<List<Integer>> binder = f -> f.all("n", Param.INT);
                    var e = assertThrows(WebException.class, () -> binder.bind(form(body.toString())));
                    assertEquals("Request body has 1000 problems. The first 100 are listed.", e.detail().orElseThrow());
                    var expected = new ArrayList<Problem.FieldError>();
                    for (int i = 0; i < 100; i++) {
                        expected.add(error("$.n[" + i + "]", "expected int", "type"));
                    }
                    assertEquals(expected, e.errors());
                    var problem = e.problem("/x").toJson();
                    assertEquals(100, problem.array("errors").values().size());
                }),
                test("101 errors and 100 errors: the detail says so only when some were left out", () -> {
                    FormBinder<List<Integer>> binder = f -> f.all("n", Param.INT);
                    var e101 = assertThrows(WebException.class, () -> binder.bind(form("n=x&".repeat(100) + "n=x")));
                    assertEquals("Request body has 101 problems. The first 100 are listed.", e101.detail().orElseThrow());
                    var e100 = assertThrows(WebException.class, () -> binder.bind(form("n=x&".repeat(99) + "n=x")));
                    assertEquals("Request body has 100 problems.", e100.detail().orElseThrow());
                    assertEquals(100, e100.errors().size());
                }),
                test("a record constructor that throws NullPointerException after a missing required: 400", () -> {
                    FormBinder<Required> binder = f -> new Required(f.required("email", Param.STRING));
                    var e = assertThrows(WebException.class, () -> binder.bind(form("x=1")));
                    assertEquals(400, e.status());
                    assertEquals(List.of(error("$.email", "is required", "required")), e.errors());
                    assertEquals("Request body has 1 problem.", e.detail().orElseThrow());
                    assertEquals(null, e.getCause());
                }),
                test("any RuntimeException after collected errors, a WebException too, gives the 400", () -> {
                    FormBinder<Object> binder = f -> {
                        f.required("a", Param.INT);
                        throw new WebException(409, "conflict");
                    };
                    var e = assertThrows(WebException.class, () -> binder.bind(form("")));
                    assertEquals(400, e.status());
                    assertEquals(List.of(error("$.a", "is required", "required")), e.errors());
                }),
                test("a binder that throws without collected errors passes the same instance", () -> {
                    var boom = new IllegalStateException("the binder's own bug");
                    FormBinder<Object> binder = f -> {
                        f.required("a", Param.INT);
                        throw boom;
                    };
                    assertSame(boom, assertThrows(IllegalStateException.class, () -> binder.bind(form("a=1"))));
                    var web = new WebException(409, "conflict");
                    FormBinder<Object> conflict = f -> {
                        throw web;
                    };
                    assertSame(web, assertThrows(WebException.class, () -> conflict.bind(form("a=1"))));
                }),
                test("an Error passes even after collected errors", () -> {
                    var error = new AssertionError("an Error");
                    FormBinder<Object> binder = f -> {
                        f.required("a", Param.INT);
                        throw error;
                    };
                    assertSame(error, assertThrows(AssertionError.class, () -> binder.bind(form(""))));
                }),
                test("a FormFields kept and used after its binding: IllegalStateException", () -> {
                    var saved = new AtomicReference<FormFields>();
                    FormBinder<Integer> binder = f -> {
                        saved.set(f);
                        return f.required("a", Param.INT);
                    };
                    assertEquals(1, binder.bind(form("a=1")));
                    var fields = saved.get();
                    assertThrows(IllegalStateException.class, () -> fields.required("a", Param.INT));
                    assertThrows(IllegalStateException.class, () -> fields.optional("a", Param.INT));
                    assertThrows(IllegalStateException.class, () -> fields.all("a", Param.INT));
                    assertThrows(IllegalStateException.class, () -> fields.flag("a"));
                    FormBinder<Integer> failing = f -> {
                        saved.set(f);
                        return f.required("a", Param.INT);
                    };
                    assertThrows(WebException.class, () -> failing.bind(form("a=x")));
                    var afterFailure = saved.get();
                    assertThrows(IllegalStateException.class, () -> afterFailure.flag("a"));
                }),
                test("null arguments and a parser that returns null", () -> {
                    assertThrows(NullPointerException.class, () -> ((FormBinder<Object>) f -> f.required(null,
                            Param.INT)).bind(form("a=1")));
                    assertThrows(NullPointerException.class, () -> ((FormBinder<Object>) f -> f.all("a", null))
                            .bind(form("a=1")));
                    assertThrows(NullPointerException.class, () -> ((FormBinder<Object>) f -> f.flag("a"))
                            .bind((FormData) null));
                    var nulls = Param.of("nothing", text -> null);
                    assertThrows(IllegalStateException.class, () -> ((FormBinder<Object>) f -> f.required("a", nulls))
                            .bind(form("a=1")));
                }));
    }

    /** The path validation gives for a member {@code name}, and below it {@code index} if not negative. */
    static String validationPath(String name, int index) {
        Validator<Object> validator = (value, errors) -> {
            var at = errors.at(name);
            (index < 0 ? at : at.atIndex(index)).reject("x", "y");
        };
        var invalid = (Validated.Invalid<Object>) validator.check(new Object());
        return invalid.violations().getFirst().path();
    }

    static final List<String> NAMES = List.of("email", "_x$1", "first name", "a\"b\\c", "a\u0001b\u007fc", "prénom",
            "名前", "field_" + "x".repeat(50), "a very long name with spaces that goes on and on and on",
            "", "1st", "a.b", "items[0].qty");

    static Suite paths() {
        return suite("paths are those of spola.validation",
                parameterized("a missing field has the path of Errors.at", NAMES, name -> {
                    FormBinder<String> binder = f -> f.required(name, Param.STRING);
                    var e = assertThrows(WebException.class, () -> binder.bind(new FormData(Map.of())));
                    assertEquals(validationPath(name, -1), e.errors().getFirst().path());
                }),
                parameterized("an element has the path of Errors.at(name).atIndex(i)", NAMES, name -> {
                    var fields = new LinkedHashMap<String, List<String>>();
                    fields.put(name, List.of("1", "2", "3", "x"));
                    FormBinder<List<Integer>> binder = f -> f.all(name, Param.INT);
                    var e = assertThrows(WebException.class, () -> binder.bind(new FormData(Map.copyOf(fields))));
                    assertEquals(validationPath(name, 3), e.errors().getFirst().path());
                }),
                test("the expected renderings, spelled out", () -> {
                    assertEquals("$.email", validationPath("email", -1));
                    assertEquals("$[\"first name\"]", validationPath("first name", -1));
                    assertEquals("$[\"a\\\"b\\\\c\"]", validationPath("a\"b\\c", -1));
                    assertEquals("$[\"a\\u0001b\\u007fc\"]", validationPath("a\u0001b\u007fc", -1));
                    assertEquals("$[\"pr\\u00e9nom\"]", validationPath("prénom", -1));
                    assertEquals("$.field_" + "x".repeat(50), validationPath("field_" + "x".repeat(50), -1));
                    assertEquals("$.interests[2]", validationPath("interests", 2));
                    assertEquals("$.interests[2]", FormFields.path("interests", 2));
                }));
    }
}
