package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import spola.test.Suite;

/**
 * The parser against the grammar of RFC 8259 and the UTF-8 rules of RFC 3629, case by case.
 * Every case names the rule it exercises. Accepted documents are parsed through all four entry
 * points (String, Reader, byte[], InputStream), must give the same tree, the expected compact
 * output, and survive a round trip through both writers. Rejected documents must give a
 * {@link JsonParseException} from every entry point that can carry them, never another
 * exception or an Error.
 */
final class ConformanceTests {

    private ConformanceTests() {
    }

    /** A document that must be accepted, the compact text it is written back as, and its rule. */
    record Accept(String json, String compact, String rule) {
        @Override
        public String toString() {
            return rule + ": " + Inputs.show(json);
        }
    }

    /** A document that must be rejected (as text, or as raw bytes when {@code bytes} is set). */
    record Reject(String json, byte[] bytes, String rule) {
        @Override
        public String toString() {
            if (bytes == null) {
                return rule + ": " + Inputs.show(json);
            }
            var hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02X ", b & 0xFF));
            }
            return rule + ": bytes " + hex.toString().strip();
        }

        List<Inputs.Entry> entries() {
            return bytes == null ? Inputs.entries(json) : Inputs.entries(bytes);
        }
    }

    private static Accept accept(String rule, String json, String compact) {
        return new Accept(json, compact, rule);
    }

    private static Accept accept(String rule, String json) {
        return new Accept(json, json, rule);
    }

    private static Reject reject(String rule, String json) {
        return new Reject(json, null, rule);
    }

    private static Reject bytes(String rule, int... values) {
        var bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return new Reject(null, bytes, rule);
    }

    static final List<Accept> ACCEPTED = List.of(
            // RFC 8259 §2: a JSON text is a serialized value; any value may stand at the top level
            accept("RFC 8259 §2 top-level object", "{}"),
            accept("RFC 8259 §2 top-level array", "[]"),
            accept("RFC 8259 §2 top-level string", "\"Hello world!\""),
            accept("RFC 8259 §2 top-level number", "42"),
            accept("RFC 8259 §2 top-level true", "true"),
            accept("RFC 8259 §2 top-level false", "false"),
            accept("RFC 8259 §2 top-level null", "null"),
            // RFC 8259 §2: insignificant whitespace (space, tab, LF, CR) around structural characters
            accept("RFC 8259 §2 whitespace around the value", " \t\n\r[1]\r\n \t", "[1]"),
            accept("RFC 8259 §2 whitespace around name separator", "{ \"a\" : 1 }", "{\"a\":1}"),
            accept("RFC 8259 §2 whitespace around value separator", "[1 , 2 ,3 ]", "[1,2,3]"),
            accept("RFC 8259 §2 CRLF line breaks", "{\r\n\"a\":\r\n1\r\n}", "{\"a\":1}"),
            // RFC 8259 §4: objects
            accept("RFC 8259 §4 empty name", "{\"\":1}"),
            accept("RFC 8259 §4 names are case-sensitive", "{\"a\":1,\"A\":2,\"b\":3}"),
            accept("RFC 8259 §4 nested objects", "{\"a\":{\"b\":{\"c\":{}}}}"),
            accept("RFC 8259 §4 member values of every type",
                    "{\"o\":{},\"a\":[],\"s\":\"x\",\"n\":-1.5,\"t\":true,\"f\":false,\"z\":null}"),
            accept("RFC 8259 §4 name with escaped control character", "{\"a\\u0000b\":1}"),
            // RFC 8259 §5: arrays
            accept("RFC 8259 §5 elements of every type", "[1,\"a\",true,false,null,{},[]]"),
            accept("RFC 8259 §5 nested arrays", "[[[[]]],[[]]]"),
            accept("RFC 8259 §5 elements need not have the same type", "[{\"a\":[1]},[{}],\"\",0]"),
            // RFC 8259 §6: numbers
            accept("RFC 8259 §6 zero", "0"),
            accept("RFC 8259 §6 negative zero", "-0"),
            accept("RFC 8259 §6 negative integer", "-1"),
            accept("RFC 8259 §6 integer beyond long", "123456789012345678901234567890"),
            accept("RFC 8259 §6 fraction", "1.5"),
            accept("RFC 8259 §6 fraction keeps trailing zeros", "2.50"),
            accept("RFC 8259 §6 negative zero fraction", "-0.0"),
            accept("RFC 8259 §6 exponent", "1e10"),
            accept("RFC 8259 §6 upper-case exponent", "1E10"),
            accept("RFC 8259 §6 exponent with plus", "1e+10"),
            accept("RFC 8259 §6 negative exponent", "1e-10"),
            accept("RFC 8259 §6 fraction and exponent", "-1.5E-3"),
            accept("RFC 8259 §6 exponent may have leading zeros", "1e007"),
            accept("RFC 8259 §6 zero with exponent", "0e0"),
            accept("RFC 8259 §6 huge exponent kept as text", "1e1000000000"),
            // RFC 8259 §7: strings
            accept("RFC 8259 §7 empty string", "\"\""),
            accept("RFC 8259 §7 two-character escapes", "\"\\\"\\\\\\/\\b\\f\\n\\r\\t\"", "\"\\\"\\\\/\\b\\f\\n\\r\\t\""),
            accept("RFC 8259 §7 escaped solidus", "\"\\/\"", "\"/\""),
            accept("RFC 8259 §7 \\u escape of ASCII", "\"\\u0041\"", "\"A\""),
            accept("RFC 8259 §7 \\u escape of Latin-1", "\"\\u00e9\"", "\"\u00e9\""),
            accept("RFC 8259 §7 \\u escape with upper-case hex", "\"\\u00E9\\u20AC\"", "\"\u00e9\u20ac\""),
            accept("RFC 8259 §7 escaped surrogate pair (G clef)", "\"\\uD834\\uDD1E\"", "\"\uD834\uDD1E\""),
            accept("RFC 8259 §7 escaped surrogate pair, lower-case hex", "\"\\ud83d\\ude00\"", "\"\uD83D\uDE00\""),
            accept("RFC 8259 §7 escaped U+0000", "\"\\u0000\""),
            accept("RFC 8259 §7 escaped U+001F", "\"\\u001F\"", "\"\\u001f\""),
            accept("RFC 8259 §7 raw DEL is allowed", "\"\u007F\"", "\"\\u007f\""),
            accept("RFC 8259 §7 raw U+2028 and U+2029 are allowed", "\"\u2028\u2029\"", "\"\\u2028\\u2029\""),
            accept("RFC 8259 §7 single quote inside a string", "\"it's\""),
            accept("RFC 8259 §7 raw two-byte character", "\"\u00e9\""),
            accept("RFC 8259 §7 raw three-byte character", "\"\u20ac\""),
            accept("RFC 8259 §7 raw four-byte character", "\"\uD834\uDD1E\""),
            accept("RFC 8259 §7 raw U+FEFF inside a string", "\"\uFEFF\""),
            accept("RFC 8259 §7 escaped U+FEFF inside a string", "\"\\uFEFF\"", "\"\uFEFF\""),
            accept("RFC 3629 §4 highest code point U+10FFFF", "\"\uDBFF\uDFFF\""),
            accept("RFC 3629 §4 highest BMP code point U+FFFF", "\"\uFFFF\""),
            accept("RFC 3629 §4 U+E000 right after the surrogates", "\"\uE000\""),
            accept("RFC 3629 §4 U+D7FF right before the surrogates", "\"\uD7FF\""),
            // RFC 8259 §8.1: a parser may ignore a byte order mark at the start
            accept("RFC 8259 §8.1 byte order mark at the start", "\uFEFF{\"a\":1}", "{\"a\":1}"));

    static final List<Reject> REJECTED = List.of(
            // RFC 8259 §5 / §4: no trailing or empty elements
            reject("RFC 8259 §5 trailing comma in array", "[1,]"),
            reject("RFC 8259 §4 trailing comma in object", "{\"a\":1,}"),
            reject("RFC 8259 §5 empty element", "[1,,2]"),
            reject("RFC 8259 §5 leading comma", "[,1]"),
            reject("RFC 8259 §4 only a comma", "{,}"),
            // RFC 8259 §2: no comments (only whitespace is insignificant)
            reject("RFC 8259 §2 line comment", "// note\n1"),
            reject("RFC 8259 §2 block comment", "/* note */ 1"),
            reject("RFC 8259 §2 comment inside array", "[1 /* note */]"),
            reject("RFC 8259 §2 hash comment", "# note\n1"),
            // RFC 8259 §7: strings use quotation marks
            reject("RFC 8259 §7 single-quoted string", "'a'"),
            reject("RFC 8259 §4 single-quoted name", "{'a':1}"),
            reject("RFC 8259 §4 unquoted name", "{a:1}"),
            // RFC 8259 §6: numbers
            reject("RFC 8259 §6 leading zero", "01"),
            reject("RFC 8259 §6 leading zero after minus", "-01"),
            reject("RFC 8259 §6 double zero", "00"),
            reject("RFC 8259 §6 plus sign", "+1"),
            reject("RFC 8259 §6 fraction without integer part", ".5"),
            reject("RFC 8259 §6 decimal point without digits", "1."),
            reject("RFC 8259 §6 exponent without digits", "1e"),
            reject("RFC 8259 §6 exponent sign without digits", "1e+"),
            reject("RFC 8259 §6 minus alone", "-"),
            reject("RFC 8259 §6 NaN", "NaN"),
            reject("RFC 8259 §6 Infinity", "Infinity"),
            reject("RFC 8259 §6 negative Infinity", "-Infinity"),
            reject("RFC 8259 §6 hexadecimal number", "0x10"),
            reject("RFC 8259 §6 two decimal points", "1.5.2"),
            reject("RFC 8259 §6 minus before a space", "- 1"),
            // RFC 8259 §7: control characters must be escaped
            reject("RFC 8259 §7 raw U+0000 in string", "\"a\u0000b\""),
            reject("RFC 8259 §7 raw tab in string", "\"a\tb\""),
            reject("RFC 8259 §7 raw line feed in string", "\"a\nb\""),
            reject("RFC 8259 §7 raw carriage return in string", "\"a\rb\""),
            reject("RFC 8259 §7 raw U+001F in string", "\"a\u001Fb\""),
            reject("RFC 8259 §7 raw control character in a name", "{\"a\u0001\":1}"),
            // RFC 8259 §7: escapes
            reject("RFC 8259 §7 invalid escape \\x", "\"\\x41\""),
            reject("RFC 8259 §7 short \\u escape", "\"\\u12\""),
            reject("RFC 8259 §7 \\u escape with a non-hex digit", "\"\\u12G4\""),
            reject("RFC 8259 §7 upper-case \\U", "\"\\U0041\""),
            reject("RFC 8259 §7 escaped single quote", "\"\\'\""),
            reject("RFC 8259 §7 backslash at the end of the input", "\"\\"),
            reject("RFC 8259 §7 \\u escape with full-width digits", "\"\\u\uFF11\uFF12\uFF13\uFF14\""),
            // RFC 8259 §7 and §8.2: a string is Unicode text; surrogates come in pairs
            reject("RFC 8259 §7 lone escaped high surrogate", "\"\\ud800\""),
            reject("RFC 8259 §7 lone escaped low surrogate", "\"\\udc00\""),
            reject("RFC 8259 §7 escaped high surrogate followed by a letter", "\"\\ud800\\u0041\""),
            reject("RFC 8259 §7 escaped surrogates in the wrong order", "\"\\udc00\\ud800\""),
            reject("RFC 8259 §7 escaped high surrogate at the end of a name", "{\"\\ud83d\":1}"),
            reject("RFC 8259 §8.2 raw lone high surrogate (text input)", "\"\uD800\""),
            reject("RFC 8259 §8.2 raw lone low surrogate (text input)", "\"\uDC00\""),
            reject("RFC 8259 §7 escaped high surrogate with raw low surrogate", "\"\\ud834\uDD1E\""),
            // unterminated values
            reject("RFC 8259 §7 unterminated string", "\"abc"),
            reject("RFC 8259 §4 unterminated object", "{\"a\":1"),
            reject("RFC 8259 §5 unterminated array", "[1,2"),
            reject("RFC 8259 §4 object ends after a name", "{\"a\""),
            reject("RFC 8259 §4 object ends after the colon", "{\"a\":"),
            reject("RFC 8259 §5 only an opening bracket", "["),
            reject("RFC 8259 §5 mismatched brackets", "[1}"),
            reject("RFC 8259 §4 mismatched braces", "{\"a\":1]"),
            // RFC 8259 §2: exactly one value
            reject("RFC 8259 §2 two top-level values", "1 2"),
            reject("RFC 8259 §2 two top-level objects", "{}{}"),
            reject("RFC 8259 §2 garbage after the value", "[]x"),
            reject("RFC 8259 §2 empty input", ""),
            reject("RFC 8259 §2 whitespace only", " \t\r\n"),
            reject("RFC 8259 §2 form feed is not whitespace", "\f1"),
            reject("RFC 8259 §2 no-break space is not whitespace", "\u00A01"),
            reject("RFC 8259 §2 line separator is not whitespace", "\u20281"),
            // RFC 8259 §4: structure of members (and duplicates, which Spola rejects)
            reject("RFC 8259 §4 duplicate name", "{\"a\":1,\"a\":2}"),
            reject("RFC 8259 §4 duplicate name after decoding escapes", "{\"a\":1,\"\\u0061\":2}"),
            reject("RFC 8259 §4 duplicate name in a nested object", "[{\"x\":{\"b\":1,\"b\":1}}]"),
            reject("RFC 8259 §4 number as name", "{1:2}"),
            reject("RFC 8259 §4 missing colon", "{\"a\" 1}"),
            reject("RFC 8259 §4 missing value", "{\"a\":}"),
            reject("RFC 8259 §5 missing comma", "[1 2]"),
            // RFC 8259 §3: literals are lower-case and complete
            reject("RFC 8259 §3 truncated true", "tru"),
            reject("RFC 8259 §3 capitalized True", "True"),
            reject("RFC 8259 §3 truncated null", "nul"),
            reject("RFC 8259 §3 undefined is not a literal", "undefined"),
            reject("RFC 8259 §3 literal followed by letters", "[truex]"),
            // RFC 8259 §8.1: a byte order mark only at the very start
            reject("RFC 8259 §8.1 byte order mark after the value", "1 \uFEFF"),
            reject("RFC 8259 §8.1 byte order mark inside an array", "[\uFEFF1]"),
            reject("RFC 8259 §8.1 two byte order marks", "\uFEFF\uFEFF1"),
            // RFC 3629 §4 and RFC 8259 §8.1: byte input must be strict UTF-8
            bytes("RFC 3629 §4 invalid start byte 0xFF", '"', 0xFF, '"'),
            bytes("RFC 3629 §4 invalid start byte 0xFE", '"', 0xFE, '"'),
            bytes("RFC 3629 §4 lone continuation byte", '"', 0x80, '"'),
            bytes("RFC 3629 §4 sequence cut short by a quote", '"', 0xC3, '"'),
            bytes("RFC 3629 §4 sequence cut short by the end", '"', 0xE2, 0x82),
            bytes("RFC 3629 §4 four-byte sequence cut short", '"', 0xF0, 0x9F, 0x98, '"'),
            bytes("RFC 3629 §4 overlong two-byte encoding C0 AF", '"', 0xC0, 0xAF, '"'),
            bytes("RFC 3629 §4 overlong two-byte encoding C1 BF", '"', 0xC1, 0xBF, '"'),
            bytes("RFC 3629 §4 overlong three-byte encoding", '"', 0xE0, 0x80, 0xAF, '"'),
            bytes("RFC 3629 §4 overlong four-byte encoding", '"', 0xF0, 0x80, 0x80, 0xAF, '"'),
            bytes("RFC 3629 §4 encoded high surrogate ED A0 80", '"', 0xED, 0xA0, 0x80, '"'),
            bytes("RFC 3629 §4 encoded low surrogate ED BF BF", '"', 0xED, 0xBF, 0xBF, '"'),
            bytes("RFC 3629 §4 code point above U+10FFFF (F4 90 80 80)", '"', 0xF4, 0x90, 0x80, 0x80, '"'),
            bytes("RFC 3629 §4 start byte F5", '"', 0xF5, 0x80, 0x80, 0x80, '"'),
            bytes("RFC 8259 §8.1 UTF-16 is not accepted", 0xFE, 0xFF, 0x00, '1'),
            bytes("RFC 3629 §4 invalid byte outside a string", '[', 0xFF, ']'),
            bytes("RFC 3629 §4 invalid byte after a byte order mark", 0xEF, 0xBB, 0xBF, 0xC0, 0x80),
            bytes("RFC 3629 §4 broken byte order mark", 0xEF, 0xBB, '1'));

    static Suite tests() {
        return suite("conformance",
                test("the tables are large enough", () -> {
                    assertTrue(ACCEPTED.size() >= 40, "accepted: " + ACCEPTED.size());
                    assertTrue(REJECTED.size() >= 60, "rejected: " + REJECTED.size());
                }),
                parameterized("accepted", ACCEPTED, Accept::toString, c -> {
                    var value = Inputs.parseAll(c.json());
                    assertEquals(c.compact(), Json.write(value));
                }),
                parameterized("round trip parse(write(v)) and parse(writePretty(v))", ACCEPTED, Accept::toString, c -> {
                    var value = Json.parse(c.json());
                    assertEquals(value, Json.parse(Json.write(value)));
                    assertEquals(value, Json.parse(Json.writePretty(value)));
                    assertEquals(Json.write(value), Json.write(Json.parse(Json.writePretty(value))));
                    var utf8 = new ByteArrayOutputStream();
                    try {
                        Json.writeUtf8(value, utf8);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    assertEquals(value, Json.parse(utf8.toByteArray()));
                }),
                parameterized("rejected", REJECTED, Reject::toString,
                        c -> Inputs.rejectAll(c.entries(), JsonLimits.DEFAULT)));
    }
}
