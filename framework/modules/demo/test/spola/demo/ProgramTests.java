package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import spola.test.Suite;

/** The whole program through its entry: settings file and order file in, summary out. */
final class ProgramTests {

    private ProgramTests() {
    }

    static final List<String> ORDERS = List.of(
            "apple;3",
            "pear;6",
            "kiwi;1",
            "plum;1",
            "apple;9",
            "",
            "# comments and blank lines are skipped",
            "pear;5",
            " apple ; 7 ");

    static final List<String> SUMMARY = List.of(
            "processed 3 orders, rejected 4",
            "processed:",
            "  line 1: apple x 3",
            "  line 8: pear x 5",
            "  line 9: apple x 7",
            "rejected:",
            "  line 2: pear x 6: insufficient stock (5 left)",
            "  line 3: kiwi x 1: unknown sku",
            "  line 4: plum x 1: sku is blocked",
            "  line 5: apple x 9: exceeds max quantity 8",
            "final stock:",
            "  apple: 0",
            "  pear: 0",
            "  plum: 2");

    /** The settings file of the standard shop. */
    static void standardShop(Shop shop) {
        shop.settings(
                "inventory.stock.apple=10",
                "inventory.stock.pear=5",
                "inventory.stock.plum=2",
                "rules.max.quantity=8",
                "rules.blocked=plum");
        shop.write("orders.txt", ORDERS.toArray(String[]::new));
    }

    static Suite tests() {
        return suite("the program",
                test("processes the order file and writes the summary: processed, rejected with reason, final stock", () -> {
                    try (var shop = Shop.create()) {
                        standardShop(shop);
                        var run = shop.run(Shop.environment());
                        assertEquals(InventoryApp.OK, run.exit(), run.toString());
                        assertEquals(SUMMARY, run.outLines(), run.toString());
                        assertTrue(run.log().contains("processing orders from"), run.toString());
                        assertTrue(run.log().contains("processed 3 orders, rejected 4"), run.toString());
                        assertTrue(!run.log().contains("error:"), run.toString());
                    }
                }),
                test("writes a signed audit trail next to the order file", () -> {
                    try (var shop = Shop.create()) {
                        standardShop(shop);
                        var run = shop.run(Shop.environment());
                        assertEquals(InventoryApp.OK, run.exit(), run.toString());
                        var audit = shop.read("orders.txt.audit").lines().toList();
                        assertEquals(List.of(
                                "accepted line=1 sku=apple quantity=3",
                                "rejected line=2 sku=pear quantity=6 reason=insufficient stock (5 left)",
                                "rejected line=3 sku=kiwi quantity=1 reason=unknown sku",
                                "rejected line=4 sku=plum quantity=1 reason=sku is blocked",
                                "rejected line=5 sku=apple quantity=9 reason=exceeds max quantity 8",
                                "accepted line=8 sku=pear quantity=5",
                                "accepted line=9 sku=apple quantity=7"), audit.subList(0, 7));
                        var signed = String.join("\n", audit.subList(0, 7)) + "\n";
                        var mac = Mac.getInstance("HmacSHA256");
                        mac.init(new SecretKeySpec(Shop.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                        var expected = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
                        assertEquals("closed entries=7 hmac-sha256=" + expected, audit.get(7));
                        assertEquals(8, audit.size());
                    }
                }),
                test("audit.file puts the audit trail elsewhere", () -> {
                    try (var shop = Shop.create()) {
                        standardShop(shop);
                        var run = shop.run(Shop.environment(), "--audit.file=" + shop.dir.resolve("trail.log"));
                        assertEquals(InventoryApp.OK, run.exit(), run.toString());
                        assertTrue(shop.read("trail.log").startsWith("accepted line=1 sku=apple quantity=3\n"));
                        assertTrue(!java.nio.file.Files.exists(shop.dir.resolve("orders.txt.audit")));
                    }
                }));
    }
}
