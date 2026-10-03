package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.matches;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import spola.test.Suite;

/** Criterion 12: no raw control characters in paths and exception messages; no value is carried. */
final class SafetyTests {

    private SafetyTests() {
    }

    /** True if the text has a character outside printable ASCII. */
    static boolean hasUnprintable(String text) {
        return text.chars().anyMatch(c -> c < 0x20 || c >= 0x7F);
    }

    static final List<String> NASTY = List.of("line\nFAKE LOG", "esc\u001b[31mred", "crlf\r\nX: y", "ls ps ",
            "bidi‮evil", "nul\u0000");

    record Login(String user, String password) {
    }

    static Suite tests() {
        return suite("safe text",
                test("messages and keys with \\n, ESC and raw line breaks are escaped in getMessage() and in paths", () -> {
                    for (var nasty : NASTY) {
                        Validator<Object> validator = (value, errors) -> {
                            errors.reject("x", nasty);
                            errors.atKey(nasty).reject("y", "plain");
                            errors.at(nasty).atKey(nasty).reject("z", nasty);
                        };
                        var e = assertThrows(ValidationException.class, () -> validator.require("v"));
                        assertFalse(hasUnprintable(e.getMessage()), "getMessage(): " + e.getMessage());
                        for (var violation : e.violations()) {
                            assertFalse(hasUnprintable(violation.path()), "path: " + violation.path());
                            assertFalse(violation.message().chars().anyMatch(c -> c < 0x20 || c == 0x7F
                                    || c == 0x2028 || c == 0x2029 || c == 0x202E), "message: " + violation.message());
                        }
                    }
                }),
                test("the escapes are exact", () -> {
                    Validator<Object> validator = (value, errors) -> errors.atKey("a\nb\u001b").reject("x", "m\r\n\u001b[2J");
                    var e = assertThrows(ValidationException.class, () -> validator.require("v"));
                    assertEquals(List.of(new Violation("$[\"a\\u000ab\\u001b\"]", "x", "m\\u000d\\u000a\\u001b[2J")),
                            e.violations());
                    assertEquals("1 violation: $[\"a\\u000ab\\u001b\"]: m\\u000d\\u000a\\u001b[2J", e.getMessage());
                }),
                test("letters of any script stay in a Violation's message, and are escaped in getMessage()", () -> {
                    Validator<Object> validator = (value, errors) -> errors.reject("x", "får inte vara tom 😀");
                    var e = assertThrows(ValidationException.class, () -> validator.require("v"));
                    assertEquals("får inte vara tom 😀", e.violations().getFirst().message());
                    assertEquals("1 violation: $: f\\u00e5r inte vara tom \\ud83d\\ude00", e.getMessage());
                }),
                test("an unpaired surrogate in a message is escaped", () -> {
                    Validator<Object> validator = (value, errors) -> errors.reject("x", "a\uD800b\uDC00");
                    assertEquals("a\\ud800b\\udc00",
                            ((Validated.Invalid<Object>) validator.check("v")).violations().getFirst().message());
                }),
                test("a Violation has exactly path, code and message: no field for the value", () -> {
                    var components = Arrays.stream(Violation.class.getRecordComponents())
                            .map(c -> c.getType().getSimpleName() + " " + c.getName()).toList();
                    assertEquals(List.of("String path", "String code", "String message"), components);
                }),
                test("a rejected password appears nowhere in the result or the exception", () -> {
                    var secret = "hunter2-Secret!";
                    var login = Validator.<Login>builder()
                            .field("password", Login::password, matches(Pattern.compile("[a-z]+")))
                            .build();
                    var result = login.check(new Login("ada", secret));
                    assertFalse(result.toString().contains(secret), result.toString());
                    var e = assertThrows(ValidationException.class, () -> login.require(new Login("ada", secret)));
                    assertFalse(e.getMessage().contains(secret), e.getMessage());
                    assertFalse(e.violations().toString().contains(secret));
                    assertTrue(e.getMessage().contains("$.password: must match the required format"), e.getMessage());
                }));
    }
}
