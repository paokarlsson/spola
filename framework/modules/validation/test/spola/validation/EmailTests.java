package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/** Criterion 4: {@code email()}'s definition, address by address. */
final class EmailTests {

    private EmailTests() {
    }

    static final String LOCAL_64 = "l".repeat(64);
    static final String LABEL_63 = "d".repeat(63);
    /** A domain of exactly 253 characters: 63 + 1 + 63 + 1 + 63 + 1 + 61. */
    static final String DOMAIN_253 = LABEL_63 + "." + LABEL_63 + "." + LABEL_63 + "." + "e".repeat(61);

    static final List<String> VALID = List.of(
            "a@b",                                   // no dot in the domain, as in Hibernate Validator
            "first.last+tag@example.co.uk",
            "user@example.com",
            "A.B.C@EXAMPLE.COM",
            "!#$%&'*+-/=?^_`{|}~@example.org",       // every atext symbol
            "o'brien@example.ie",
            "a_b@c.d",
            "1234@123.45",                           // all-digit labels are LDH
            "x@a-b.c",                               // hyphen inside a label
            "x@a--b.c",
            "x@a.b.c.d.e.f",
            LOCAL_64 + "@x.se",                       // local part of 64
            "x@" + LABEL_63 + ".se",                  // label of 63
            "x@" + DOMAIN_253,                        // domain of 253
            "a.b.c@d",
            "{x}@y.z");

    static final List<String> INVALID = List.of(
            "",
            "plain",
            "a@b@c",                                  // two @
            "a@@b",                                   // two @ in a row
            ".a@b.c",                                 // dot first in the local part
            "a.@b.c",                                 // dot last in the local part
            "a..b@c.d",                               // two dots in the local part
            "l" + LOCAL_64 + "@x.se",                 // local part of 65
            "x@d" + LABEL_63 + ".se",                 // label of 64
            "x@-b.c",                                 // hyphen first in a label
            "x@b-.c",                                 // hyphen last in a label
            "a b@c.d",                                // a space
            " a@b.c",                                 // a leading space
            "\"a b\"@c.d",                            // a quoted local part
            "\"ab\"@c.d",                             // quoted, even without a space
            "a@[127.0.0.1]",                          // an IP literal
            "jörg@example.de",                        // non-ASCII in the local part
            "a@exämple.de",                           // non-ASCII in the domain
            "@b.c",                                   // empty local part
            "a@",                                     // empty domain
            "a@b..c",                                 // empty label
            "a@.b",
            "a@b.",                                   // trailing dot
            "a@b_c.d",                                // underscore is not LDH
            "a(comment)@b.c",
            "x@" + DOMAIN_253 + "e",                  // domain of 254
            "a@b\n");

    static Suite tests() {
        var email = Constraints.email();
        return suite("email()",
                test("the table has at least 15 valid and 15 invalid addresses", () -> {
                    assertTrue(VALID.size() >= 15, VALID.size() + " valid");
                    assertTrue(INVALID.size() >= 15, INVALID.size() + " invalid");
                    assertEquals(253, DOMAIN_253.length());
                    assertEquals(65, ("l" + LOCAL_64).length());
                }),
                parameterized("valid", VALID, address -> {
                    assertTrue(email.accepts(address), address + " should be valid");
                    assertEquals(new Validated.Valid<>(address), Validator.of(email).check(address));
                }),
                parameterized("invalid", INVALID, address -> {
                    assertEquals(new Validated.Invalid<String>(List.of(new Violation("$", "email",
                            "must be a well-formed email address")), 1), Validator.<String>of(email).check(address));
                }),
                test("a megabyte of text is rejected without a scan", () -> {
                    assertEquals(false, email.accepts("a".repeat(1_000_000) + "@b.c"));
                }));
    }
}
