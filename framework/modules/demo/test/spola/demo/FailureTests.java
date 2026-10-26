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
                test("an audit trail that cannot be opened: audit.file and the path are named, exit 74", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(Shop.environment(), "--audit.file=" + shop.dir.resolve("no-such-dir/trail.log"));
                        assertFailure(run, InventoryApp.IO_ERROR, "cannot open the audit trail", "no-such-dir",
                                "(audit.file)");
                        assertFalse(run.log().contains("lambda$"), run.toString());
                        assertEquals("", run.out());
                    }
                }),
                test("audit.file naming the order file: a configuration error, and the order file is left alone", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var before = shop.read("orders.txt");
                        for (var audit : List.of(shop.dir.resolve("orders.txt").toString(),
                                shop.dir.resolve("sub/../orders.txt").toString())) {
                            assertFailure(shop.run(Shop.environment(), "--audit.file=" + audit), InventoryApp.CONFIG_ERROR,
                                    "invalid configuration: audit.file is the order file (inventory.orders)", "orders.txt");
                            assertEquals(before, shop.read("orders.txt"));
                        }
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
                test("an sku with an invisible character: a broken line, and the character reaches no output", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        // ESC (cursor control), NEL, LS, PS, RLO (bidi override), BOM not at the start, NBSP, DEL
                        for (var invisible : List.of("\u001b[1A", "\u0085", "\u2028", "\u2029", "\u202e", "\ufeff",
                                "\u00a0", "\u007f")) {
                            shop.write("orders.txt", "pear;1", "ev" + invisible + "il;1");
                            var run = shop.run(Shop.environment());
                            var shown = String.format("\\u%04x", (int) invisible.charAt(0));
                            assertFailure(run, InventoryApp.DATA_ERROR,
                                    "error: broken order file: orders.txt line 2: the sku may only hold visible characters",
                                    "got 'ev" + shown);
                            var audit = shop.read("orders.txt.audit");
                            assertTrue(audit.startsWith("accepted line=1 sku=pear quantity=1\nclosed entries=1 "), audit);
                            for (var output : List.of(run.out(), run.log(), audit)) {
                                assertFalse(output.contains(invisible.substring(0, 1)), "raw " + shown + " in\n" + output);
                            }
                        }
                    }
                }),
                test("an order file that is not UTF-8 (Windows-1252): a broken file with a line, exit 65", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        // "pear;1", then "p\u00e4r;1" with the \u00e4 as the single Windows-1252 byte 0xE4
                        var bytes = new byte[] {'p', 'e', 'a', 'r', ';', '1', '\n', 'p', (byte) 0xE4, 'r', ';', '1', '\n'};
                        java.nio.file.Files.write(shop.dir.resolve("orders.txt"), bytes);
                        var run = shop.run(Shop.environment());
                        assertFailure(run, InventoryApp.DATA_ERROR,
                                "error: broken order file: orders.txt line 2: not valid UTF-8");
                        assertFalse(run.all().contains("MalformedInputException"), run.toString());
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
