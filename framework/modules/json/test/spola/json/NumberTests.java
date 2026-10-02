package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import spola.test.Suite;

/** {@link JsonNumber}: the text is kept, conversions are exact or throw, equality is numeric. */
final class NumberTests {

    private NumberTests() {
    }

    private static JsonNumber number(String text) {
        return (JsonNumber) Json.parse(text);
    }

    static Suite tests() {
        return suite("numbers",
                test("the text is kept exactly and written back", () -> {
                    assertEquals("1.10", number("1.10").text());
                    assertEquals("1.10", Json.write(number("1.10")));
                    assertEquals("[1.10,1E+2,-0,0.000]", Json.write(Json.parse("[1.10, 1E+2, -0, 0.000]")));
                    assertEquals("1.10", Json.write(JsonNumber.of(new BigDecimal("1.10"))));
                }),
                test("intValueExact throws for 1.5, 2147483648 and 1e400", () -> {
                    assertThrows(ArithmeticException.class, () -> number("1.5").intValueExact());
                    assertThrows(ArithmeticException.class, () -> number("2147483648").intValueExact());
                    assertThrows(ArithmeticException.class, () -> number("1e400").intValueExact());
                    assertThrows(ArithmeticException.class, () -> number("-2147483649").intValueExact());
                    assertThrows(ArithmeticException.class, () -> number("1e-400").intValueExact());
                    assertThrows(ArithmeticException.class, () -> number("1e99999999999999999999").intValueExact());
                    var e = assertThrows(ArithmeticException.class, () -> number("1.5").intValueExact());
                    assertTrue(e.getMessage().contains("1.5"), e.getMessage());
                }),
                test("intValueExact accepts integers in any notation", () -> {
                    assertEquals(2147483647, number("2147483647").intValueExact());
                    assertEquals(-2147483648, number("-2147483648").intValueExact());
                    assertEquals(1, number("1.0").intValueExact());
                    assertEquals(100, number("1e2").intValueExact());
                    assertEquals(120, number("1.2E+2").intValueExact());
                    assertEquals(0, number("-0").intValueExact());
                    assertEquals(0, number("0e-500").intValueExact());
                    assertEquals(1, number("10e-1").intValueExact());
                }),
                test("longValueExact at and beyond the range of long", () -> {
                    assertEquals(Long.MAX_VALUE, number("9223372036854775807").longValueExact());
                    assertEquals(Long.MIN_VALUE, number("-9223372036854775808").longValueExact());
                    assertThrows(ArithmeticException.class, () -> number("9223372036854775808").longValueExact());
                    assertThrows(ArithmeticException.class, () -> number("-9223372036854775809").longValueExact());
                    assertThrows(ArithmeticException.class, () -> number("92233720368547758070e-1").intValueExact());
                    assertEquals(Long.MAX_VALUE, number("92233720368547758070e-1").longValueExact());
                    assertThrows(ArithmeticException.class, () -> number("0.5").longValueExact());
                }),
                test("bigDecimalValue of 1e1000000000 returns without computing the digits", () -> {
                    long start = System.nanoTime();
                    var value = number("1e1000000000").bigDecimalValue();
                    assertEquals(BigInteger.ONE, value.unscaledValue());
                    assertEquals(-1_000_000_000, value.scale());
                    assertTrue(System.nanoTime() - start < 1_000_000_000L, "took too long");
                    assertEquals(new BigDecimal("1.10"), number("1.10").bigDecimalValue());
                    assertEquals(2, number("1.10").bigDecimalValue().scale());
                    // a scale beyond int is beyond BigDecimal
                    assertThrows(ArithmeticException.class, () -> number("1e99999999999").bigDecimalValue());
                }),
                test("bigIntegerValueExact of 1e1000000000 throws within 1 second", () -> {
                    long start = System.nanoTime();
                    var e = assertThrows(ArithmeticException.class, () -> number("1e1000000000").bigIntegerValueExact());
                    long elapsed = System.nanoTime() - start;
                    assertTrue(elapsed < 1_000_000_000L, "took " + elapsed / 1_000_000 + " ms");
                    assertTrue(e.getMessage().contains("10000 digits"), e.getMessage());
                    assertThrows(ArithmeticException.class, () -> number("1e99999999999999999999").bigIntegerValueExact());
                    assertThrows(ArithmeticException.class, () -> number("1.5").bigIntegerValueExact());
                }),
                test("bigIntegerValueExact up to MAX_EXACT_DIGITS digits", () -> {
                    assertEquals(new BigInteger("123456789012345678901234567890"),
                            number("123456789012345678901234567890").bigIntegerValueExact());
                    assertEquals(BigInteger.TEN.pow(JsonNumber.MAX_EXACT_DIGITS - 1),
                            number("1e" + (JsonNumber.MAX_EXACT_DIGITS - 1)).bigIntegerValueExact());
                    assertThrows(ArithmeticException.class,
                            () -> number("1e" + JsonNumber.MAX_EXACT_DIGITS).bigIntegerValueExact());
                    assertEquals(BigInteger.valueOf(-1200), number("-1.2e3").bigIntegerValueExact());
                    assertEquals(BigInteger.ZERO, number("0e99999999999999").bigIntegerValueExact());
                }),
                test("doubleValue rounds like Double.parseDouble", () -> {
                    assertEquals(0.1, number("0.1").doubleValue(), 0.0);
                    assertEquals(Double.POSITIVE_INFINITY, number("1e400").doubleValue(), 0.0);
                    assertEquals(Double.NEGATIVE_INFINITY, number("-1e99999999999999999999").doubleValue(), 0.0);
                    assertEquals(0.0, number("1e-400").doubleValue(), 0.0);
                }),
                test("1, 1.0, 1e0 and 10e-1 are equal with equal hash codes", () -> {
                    var ones = List.of(number("1"), number("1.0"), number("1e0"), number("10e-1"), number("0.1e1"),
                            number("100E-2"), JsonNumber.of(1), JsonNumber.of(1.0), JsonNumber.of(new BigDecimal("1.000")));
                    for (var a : ones) {
                        for (var b : ones) {
                            assertEquals(a, b);
                            assertEquals(a.hashCode(), b.hashCode(), a + " and " + b);
                        }
                    }
                    assertEquals(number("0"), number("-0"));
                    assertEquals(number("0").hashCode(), number("-0.000e5").hashCode());
                    assertEquals(number("1e1000000000"), number("10e999999999"));
                    assertEquals(number("1e1000000000").hashCode(), number("10e999999999").hashCode());
                    assertNotEquals(number("1"), number("-1"));
                    assertNotEquals(number("1"), number("1.0000000000000000001"));
                    assertNotEquals(number("1e1000000000"), number("1e1000000001"));
                    assertNotEquals(number("0.1"), number("1"));
                    // numeric equality reaches into objects and arrays
                    assertEquals(Json.parse("{\"a\":[1,2.50]}"), Json.parse("{\"a\":[1.0,25e-1]}"));
                    assertEquals(Json.parse("{\"a\":[1,2.50]}").hashCode(), Json.parse("{\"a\":[1.0,25e-1]}").hashCode());
                }),
                test("comparing numbers with huge exponents is fast", () -> {
                    var exponent = "9".repeat(900);
                    long start = System.nanoTime();
                    var a = number("1e" + exponent);
                    var b = number("10e" + exponent);
                    assertNotEquals(a, b);
                    assertEquals(a, number("1.0e" + exponent));
                    assertTrue(System.nanoTime() - start < 1_000_000_000L, "took too long");
                }),
                test("the factories", () -> {
                    assertEquals("42", JsonNumber.of(42).text());
                    assertEquals("-9223372036854775808", JsonNumber.of(Long.MIN_VALUE).text());
                    assertEquals("0.1", JsonNumber.of(0.1).text());
                    assertEquals("1.0E10", JsonNumber.of(1e10).text());
                    assertEquals("-0.0", JsonNumber.of(-0.0).text());
                    assertEquals("1E+3", JsonNumber.of(new BigDecimal("1E+3")).text());
                    assertEquals("123456789012345678901234567890",
                            JsonNumber.of(new BigInteger("123456789012345678901234567890")).text());
                    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Double.NaN));
                    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Double.POSITIVE_INFINITY));
                    assertThrows(IllegalArgumentException.class, () -> JsonNumber.of(Double.NEGATIVE_INFINITY));
                    // every factory's text is valid JSON that parses back to an equal number
                    for (var n : List.of(JsonNumber.of(Double.MIN_VALUE), JsonNumber.of(-Double.MAX_VALUE),
                            JsonNumber.of(new BigDecimal("-0.00E-7")), JsonNumber.of(1e-5))) {
                        assertEquals(n, Json.parse(n.text()));
                        assertEquals(n.text(), Json.write(Json.parse(n.text())));
                    }
                }));
    }
}
