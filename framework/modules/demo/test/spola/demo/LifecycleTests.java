package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/**
 * The audit trail is {@code AutoCloseable}; the container closes it when the program ends,
 * after success and after a failure. Closing writes the trail's last line and logs it, before
 * the program reports its result.
 */
final class LifecycleTests {

    private LifecycleTests() {
    }

    static Suite tests() {
        return suite("closing",
                test("after a successful run the audit trail is closed", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(Shop.environment());
                        assertEquals(InventoryApp.OK, run.exit(), run.toString());
                        var audit = shop.read("orders.txt.audit").lines().toList();
                        assertTrue(audit.getLast().startsWith("closed entries=7 hmac-sha256="), audit.toString());
                        assertTrue(run.log().contains("orders.txt.audit closed after 7 entries"), run.toString());
                    }
                }),
                test("when a broken line stops the processing the audit trail is still closed, before the error", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        shop.write("orders.txt", "apple;1", "pear;1", "plum;two", "apple;1");
                        var run = shop.run(Shop.environment());
                        assertEquals(InventoryApp.DATA_ERROR, run.exit(), run.toString());
                        var audit = shop.read("orders.txt.audit").lines().toList();
                        assertEquals(List.of("accepted line=1 sku=apple quantity=1", "accepted line=2 sku=pear quantity=1"),
                                audit.subList(0, 2));
                        assertTrue(audit.get(2).startsWith("closed entries=2 hmac-sha256="), audit.toString());
                        assertEquals(3, audit.size());
                        var closed = run.log().indexOf("orders.txt.audit closed after 2 entries");
                        var error = run.log().indexOf("error: broken order file");
                        assertTrue(closed >= 0 && error > closed, run.toString());
                        assertEquals("", run.out(), "no summary after a failure");
                    }
                }),
                test("when the order file cannot be read the audit trail is closed too", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        shop.write("orders.txt", "apple;1");
                        java.nio.file.Files.delete(shop.dir.resolve("orders.txt"));
                        var run = shop.run(Shop.environment(), "--audit.file=" + shop.dir.resolve("trail.log"));
                        assertEquals(InventoryApp.IO_ERROR, run.exit(), run.toString());
                        assertTrue(shop.read("trail.log").startsWith("closed entries=0 hmac-sha256="));
                        assertTrue(run.log().contains("closed after 0 entries"), run.toString());
                    }
                }));
    }
}
