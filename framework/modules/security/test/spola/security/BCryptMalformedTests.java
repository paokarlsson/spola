package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import spola.test.Suite;

/**
 * Criterion 9: malformed stored bcrypt hashes give IllegalArgumentException without the string,
 * from matches (whatever the password) and from needsRehash.
 */
final class BCryptMalformedTests {

    private BCryptMalformedTests() {
    }

    /** A malformed stored hash and what is wrong with it. */
    record Case(String label, String encoded) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** A published vector (jBCrypt, "", cost 06): valid, so each case fails for its own reason. */
    static final String GOOD = "$2a$06$DCq7YPn5Rq63x1Lad4cll.TV4S6ytwfsfvkgY8jIucDrjc8deX1s.";
    static final String TAIL = GOOD.substring(7);

    /** GOOD with the character at {@code index} replaced. */
    static String replace(int index, String replacement) {
        return GOOD.substring(0, index) + replacement + GOOD.substring(index + 1);
    }

    /** 10 000 characters: refused by the length check before anything else is read. */
    static final Case TEN_THOUSAND = new Case("10 000 characters", GOOD + ".".repeat(10_000 - 60));

    static final List<Case> CASES = List.of(
            new Case("the empty string", ""),
            new Case("59 characters", GOOD.substring(0, 59)),
            new Case("61 characters", GOOD + "."),
            new Case("a trailing \\n (61 characters)", GOOD + "\n"),
            new Case("a \\n as the last of 60 characters", GOOD.substring(0, 59) + "\n"),
            TEN_THOUSAND,
            new Case("$2$ (no version letter)", "$2$06$" + TAIL + "."),
            new Case("$2x$", "$2x$06$" + TAIL),
            new Case("$2c$", "$2c$06$" + TAIL),
            new Case("$2A$ (upper case)", "$2A$06$" + TAIL),
            new Case("$2Y$ (upper case)", "$2Y$06$" + TAIL),
            new Case("$3a$", "$3a$06$" + TAIL),
            new Case("$1a$", "$1a$06$" + TAIL),
            new Case("no leading $ (60 characters)", "x2a$06$" + TAIL),
            new Case("no leading $ (59 characters)", GOOD.substring(1)),
            new Case("no $ after the version", "$2a" + "x06$" + TAIL),
            new Case("cost 00", "$2a$00$" + TAIL),
            new Case("cost 03", "$2a$03$" + TAIL),
            new Case("cost 32", "$2a$32$" + TAIL),
            new Case("cost 99", "$2a$99$" + TAIL),
            new Case("cost with a leading space", "$2a$ 6$" + TAIL),
            new Case("cost with a trailing space", "$2a$6 $" + TAIL),
            new Case("cost with +", "$2a$+6$" + TAIL),
            new Case("cost with -", "$2a$-6$" + TAIL),
            new Case("cost with one digit", "$2a$6$" + TAIL + "."),
            new Case("cost with three digits", "$2a$006$" + TAIL.substring(1)),
            new Case("cost with an Arabic-Indic digit", "$2a$0٦$" + TAIL),
            new Case("cost in full-width digits", "$2a$０６$" + TAIL),
            new Case("another character than $ after the cost", "$2a$06." + TAIL),
            new Case("+ in the salt", replace(10, "+")),
            new Case("= in the salt", replace(11, "=")),
            new Case("- in the salt", replace(12, "-")),
            new Case("_ in the hash", replace(40, "_")),
            new Case("a space in the hash", replace(41, " ")),
            new Case("U+0000 in the hash", replace(42, "\u0000")),
            new Case("é in the salt", replace(13, "é")),
            new Case("$ in the salt", replace(14, "$")),
            new Case("a \\n in the hash", replace(50, "\n")),
            new Case("non-canonical salt (last character / instead of .)",
                    "$2a$06$DCq7YPn5Rq63x1Lad4cll/TV4S6ytwfsfvkgY8jIucDrjc8deX1s."),
            new Case("non-canonical salt (last character P instead of .)", replace(28, "P")),
            new Case("non-canonical hash (last character / instead of .)",
                    "$2a$06$DCq7YPn5Rq63x1Lad4cll.TV4S6ytwfsfvkgY8jIucDrjc8deX1s/"),
            new Case("non-canonical hash (last character X instead of W)",
                    "$2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeX"),
            new Case("{bcrypt} before a valid hash", "{bcrypt}" + GOOD),
            new Case("a PBKDF2 string from Pbkdf2PasswordHasher", Support.fast().hash("pw")));

    static Suite tests() {
        var hasher = BCryptPasswordHasher.of(4);
        return suite("malformed stored bcrypt hashes",
                test("the base strings are valid, so each case fails for its own reason", () -> {
                    assertFalse(hasher.matches("", GOOD));
                    assertFalse(hasher.matches("x", GOOD));
                    assertFalse(hasher.needsRehash(GOOD));
                    assertFalse(hasher.needsRehash("$2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW"));
                    assertTrue(hasher.matches("U*U", "$2a$05$CCCCCCCCCCCCCCCCCCCCC.E5YPO9kmyuRGyh0XouQYb4YMJKvyOeW"));
                    // Upper and lower bounds of the cost are accepted.
                    assertFalse(hasher.needsRehash("$2a$04$" + TAIL));
                    BCryptHasherTests.withoutBcrypt("needsRehash of a cost-31 hash",
                            () -> assertFalse(hasher.needsRehash("$2a$31$" + TAIL)));
                    assertTrue(CASES.size() >= 30, "cases: " + CASES.size());
                    assertEquals(10_000, TEN_THOUSAND.encoded().length());
                    assertTrue(CASES.contains(TEN_THOUSAND));
                }),
                parameterized("matches and needsRehash throw IllegalArgumentException without the string", CASES, c -> {
                    var thrown = new ArrayList<IllegalArgumentException>();
                    thrown.add(assertThrows(IllegalArgumentException.class, () -> hasher.matches("pw", c.encoded())));
                    thrown.add(assertThrows(IllegalArgumentException.class, () -> hasher.needsRehash(c.encoded())));
                    // The hash is parsed first: also a password that matches would refuse throws.
                    for (var refused : List.of("", "x".repeat(73), "a\u0000a", "\ud800", "€".repeat(25))) {
                        thrown.add(assertThrows(IllegalArgumentException.class, () -> hasher.matches(refused, c.encoded())));
                    }
                    for (var e : thrown) {
                        checkMessage(e, c.encoded());
                    }
                }),
                test("null gives NullPointerException in matches and needsRehash", () -> {
                    assertThrows(NullPointerException.class, () -> hasher.matches("pw", null));
                    assertThrows(NullPointerException.class, () -> hasher.matches("", null));
                    assertThrows(NullPointerException.class, () -> hasher.needsRehash(null));
                }));
    }

    /**
     * The message is short printable ASCII starting like Pbkdf2PasswordHasher's, has no cause,
     * and contains neither the string nor any 8 characters in a row of it.
     */
    static void checkMessage(IllegalArgumentException e, String encoded) {
        MalformedHashTests.checkMessage(e, encoded);
        var message = e.getMessage();
        var window = 8;
        for (int i = 0; i + window <= Math.min(encoded.length(), 200); i++) {
            var part = encoded.substring(i, i + window);
            assertFalse(message.contains(part), "the message contains a part of the string");
        }
    }
}
