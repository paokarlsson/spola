package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/**
 * The errors a user (or the developer wiring the program) meets: one message that names the
 * key, the component or the line, an exit code other than 0, and no stack trace.
 */
final class FailureTests {

    private FailureTests() {
    }

    /** Checks the exit code, that the message has every expected part, and that there is no stack trace. */
    static void assertFailure(Shop.Run run, int exit, String... expected) {
        assertEquals(exit, run.exit(), run.toString());
        assertTrue(run.log().contains("error: "), run.toString());
        for (var part : expected) {
            assertTrue(run.log().contains(part), "expected '" + part + "' in\n" + run);
        }
        assertFalse(run.all().contains("\tat "), "a stack trace:\n" + run);
    }

    static Suite tests() {
        return suite("errors",
                test("missing configuration: the missing key is named", () -> {
                    try (var shop = Shop.create()) {
                        shop.write("inventory.properties", "inventory.stock.apple=1");
                        assertFailure(shop.run(Shop.environment()), InventoryApp.CONFIG_ERROR,
                                "invalid configuration", "missing key 'inventory.orders'");
                        ProgramTests.standardShop(shop);
                        assertFailure(shop.run(java.util.Map.of()), InventoryApp.CONFIG_ERROR,
                                "missing key 'audit.secret'");
                    }
                }),
                test("missing configuration file: the file is named", () -> {
                    try (var shop = Shop.create()) {
                        assertFailure(Shop.Run.of(List.of("--config.file=" + shop.dir.resolve("nowhere.properties")),
                                Shop.environment()), InventoryApp.CONFIG_ERROR, "nowhere.properties");
                    }
                }),
                test("invalid values: every invalid key is named, in one report", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        assertFailure(shop.run(Shop.environment(), "--rules.max.quantity=many"), InventoryApp.CONFIG_ERROR,
                                "invalid value for 'rules.max.quantity'", "got 'many'");
                        assertFailure(shop.run(Shop.environment(), "--rules.max.quantity=0",
                                        "--inventory.stock.pear=-1", "--logging.level.root=LOUD"),
                                InventoryApp.CONFIG_ERROR, "has 3 problems",
                                "invalid value for 'rules.max.quantity'",
                                "invalid value for 'inventory.stock.pear'",
                                "invalid value for 'logging.level.root'", "expected TRACE, DEBUG, INFO, WARN, ERROR, OFF or ALL");
                        assertFailure(shop.run(Shop.environment(), "--logging.level.spola..demo=DEBUG"),
                                InventoryApp.CONFIG_ERROR, "logging.level", "spola..demo");
                        assertFailure(shop.run(Shop.environment(), "--rules.max.quantity"),
                                InventoryApp.CONFIG_ERROR, "'--rules.max.quantity' has no '=value'");
                    }
                }),
                test("a wiring error: the component and its missing dependency are named", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = Shop.Run.of(streams -> InventoryApp.run(List.of(shop.settingsArgument()),
                                Shop.environment(), streams.out(), streams.log(),
                                (app, summary) -> List.of(InventoryWiring.settings(app, summary),
                                        InventoryWiring.STOCK, InventoryWiring.PROCESSING)));
                        assertFailure(run, InventoryApp.SOFTWARE_ERROR, "wiring error",
                                "spola.demo.OrderProcessor", "spola.demo.OrderCheck[QUANTITY]",
                                "spola.demo.OrderCheck[BLOCKED]", "which is not bound");
                        assertEquals("", run.out());
                    }
                }),
                test("a component that cannot be created: the chain is named", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(Shop.environment(), "--audit.file=" + shop.dir.resolve("no-such-dir/trail.log"));
                        assertEquals(InventoryApp.SOFTWARE_ERROR, run.exit(), run.toString());
                        assertTrue(run.log().contains("cannot resolve spola.demo.OrderProcessor -> spola.demo.AuditTrail"),
                                run.toString());
                        assertFalse(run.all().contains("\tat "), run.toString());
                    }
                }),
                test("a broken order line: the file and the line number are named", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        for (var broken : List.of("apple", "apple;", "apple;0", "apple;-1", "apple;1;2", ";3", "apple;1.5",
                                "apple;99999999999")) {
                            shop.write("orders.txt", "pear;1", "# fine", broken);
                            assertFailure(shop.run(Shop.environment()), InventoryApp.DATA_ERROR,
                                    "error: broken order file: orders.txt line 3: expected 'sku;quantity'", "got '" + broken + "'");
                        }
                    }
                }),
                test("a missing order file: the file is named", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(Shop.environment(), "--inventory.orders=" + shop.dir.resolve("none.txt"),
                                "--audit.file=" + shop.dir.resolve("trail.log"));
                        assertEquals(InventoryApp.IO_ERROR, run.exit(), run.toString());
                        assertTrue(run.log().contains("cannot read the order file"), run.toString());
                        assertTrue(run.log().contains("none.txt"), run.toString());
                        assertFalse(run.all().contains("\tat "), run.toString());
                    }
                }));
    }
}
