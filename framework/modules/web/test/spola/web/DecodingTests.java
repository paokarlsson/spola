package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/**
 * Strict percent-decoding to UTF-8, against the boundaries of RFC 3629 §4 (the UTF8-octets
 * grammar) and the malformed sequences of the Unicode Standard (Table 3-7, §3.9).
 */
final class DecodingTests {

    private DecodingTests() {
    }

    /**
     * One input.
     *
     * @param raw      the percent-encoded text
     * @param expected the decoded text, or null if it must be refused
     */
    record Case(String raw, String expected) {
    }

    static final List<Case> CASES = List.of(
            new Case("abc", "abc"),
            new Case("%41%62", "Ab"),
            new Case("%7F", "\u007f"),
            new Case("%C2%80", "\u0080"),
            new Case("%DF%BF", "߿"),
            new Case("%E0%A0%80", "ࠀ"),
            new Case("%ED%9F%BF", "퟿"),
            new Case("%EE%80%80", ""),
            new Case("%EF%BF%BF", "￿"),
            new Case("%F0%90%80%80", "𐀀"),
            new Case("%F4%8F%BF%BF", "􏿿"),
            new Case("%e2%82%ac", "€"),
            new Case("%252F", "%2F"),
            new Case("%", null),
            new Case("%4", null),
            new Case("%G0", null),
            new Case("%0G", null),
            new Case("%80", null),
            new Case("%BF", null),
            new Case("%C0%80", null),
            new Case("%C1%BF", null),
            new Case("%C2", null),
            new Case("%C2%41", null),
            new Case("%E0%80%80", null),
            new Case("%E0%9F%BF", null),
            new Case("%ED%A0%80", null),
            new Case("%ED%BF%BF", null),
            new Case("%F0%80%80%80", null),
            new Case("%F0%8F%BF%BF", null),
            new Case("%F4%90%80%80", null),
            new Case("%F5%80%80%80", null),
            new Case("%F8%88%80%80%80", null),
            new Case("%FE", null),
            new Case("%FF", null),
            new Case("a b", null),
            new Case("é", null));

    static Suite tests() {
        return suite("percent-decoding",
                parameterized("strict UTF-8", CASES, Case::raw, c -> assertEquals(c.expected(), Decoding.decode(c.raw(), false))),
                test("+ is a space only in the query", () -> {
                    assertEquals("a+b", Decoding.decode("a+b", false));
                    assertEquals("a b", Decoding.decode("a+b", true));
                    assertEquals("a+b", Decoding.decode("a%2Bb", true));
                    assertNull(Decoding.decode("%2", true));
                }));
    }
}
