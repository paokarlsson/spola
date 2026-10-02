package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import spola.test.Suite;

/** Parts of the program on their own: the order line format and the audit trail's HMAC. */
final class PartsTests {

    private PartsTests() {
    }

    static String hmac(String key, String data) {
        var mac = AuditTrail.mac(key);
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    static Suite tests() {
        return suite("parts",
                test("the audit HMAC matches RFC 4231 test cases 1 and 2 (HMAC-SHA-256)", () -> {
                    // Test case 1: key = 20 bytes of 0x0b, data = "Hi There".
                    assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                            hmac("\u000b".repeat(20), "Hi There"));
                    // Test case 2: key = "Jefe", data = "what do ya want for nothing?".
                    assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                            hmac("Jefe", "what do ya want for nothing?"));
                }),
                test("an order line is sku;quantity, whitespace around the parts allowed", () -> {
                    assertEquals(new OrderLine(4, "apple", 3), OrderLine.parse("o.txt", 4, "apple;3"));
                    assertEquals(new OrderLine(1, "A-100", 12), OrderLine.parse("o.txt", 1, "  A-100 ;\t12 "));
                    assertEquals("line 4: apple x 3", new OrderLine(4, "apple", 3).toString());
                }),
                test("OrderLine and Summary.Rejection have generated builders and with-methods (spola.codegen)", () -> {
                    var order = OrderLineWith.builder().line(4).sku("apple").quantity(3).build();
                    assertEquals(new OrderLine(4, "apple", 3), order);
                    assertEquals(new OrderLine(4, "apple", 5), order.withQuantity(5));
                    assertEquals(new OrderLine(9, "apple", 3), order.withLine(9));
                    assertEquals(order, order.toBuilder().build());
                    // The compact constructor runs for with-methods and builders alike.
                    assertThrows(IllegalArgumentException.class, () -> order.withQuantity(0));
                    assertThrows(NullPointerException.class, () -> order.toBuilder().sku(null).build());
                    var rejection = RejectionWith.builder().order(order).reason("unknown sku").build();
                    assertEquals(new Summary.Rejection(order, "unknown sku"), rejection);
                    assertEquals(new Summary.Rejection(order, "blocked"), rejection.withReason("blocked"));
                }),
                test("a broken line names the file and the line, and shows the line escaped and shortened", () -> {
                    var failure = assertThrows(OrderFileException.class, () -> OrderLine.parse("o.txt", 7, "apple;x"));
                    assertEquals("o.txt line 7: expected 'sku;quantity' with a whole quantity of at least 1, got 'apple;x'",
                            failure.getMessage());
                    assertEquals(7, failure.line());
                    var escaped = assertThrows(OrderFileException.class, () -> OrderLine.parse("o.txt", 1, "a\u0000b "));
                    assertTrue(escaped.getMessage().endsWith("got 'a\\u0000b\\u2028'"), escaped.getMessage());
                    var longLine = assertThrows(OrderFileException.class, () -> OrderLine.parse("o.txt", 1, "x".repeat(100)));
                    assertTrue(longLine.getMessage().endsWith("got '" + "x".repeat(60) + "...'"), longLine.getMessage());
                }));
    }
}
