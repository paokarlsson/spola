package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import spola.test.Suite;

/** The audit secret never appears in clear text in the summary, the log or an error message. */
final class SecretTests {

    private SecretTests() {
    }

    static void assertHidden(String secret, Shop.Run run) {
        assertFalse(run.all().contains(secret), "the secret is visible:\n" + run);
    }

    static Suite tests() {
        return suite("the sensitive key",
                test("a successful run with every log level on: masked in the configuration dump and the settings", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(Shop.environment(), "--logging.level.root=TRACE");
                        assertEquals(InventoryApp.OK, run.exit(), run.toString());
                        assertHidden(Shop.SECRET, run);
                        assertTrue(run.log().contains("config: audit.secret = ******  [environment (prefix INVENTORY_)]"),
                                run.toString());
                        assertTrue(run.log().contains("secret=***"), run.toString());
                        assertFalse(shop.read("orders.txt.audit").contains(Shop.SECRET));
                    }
                }),
                test("given as an argument, and copied into another key with a placeholder", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(java.util.Map.of(), "--audit.secret=" + Shop.SECRET,
                                "--audit.note=${audit.secret}", "--logging.level.root=DEBUG");
                        assertEquals(InventoryApp.OK, run.exit(), run.toString());
                        assertHidden(Shop.SECRET, run);
                        assertTrue(run.log().contains("config: audit.note = ******"), run.toString());
                    }
                }),
                test("an invalid secret: the error names the key, not the value", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var tooShort = "short-secret-1";
                        var run = shop.run(Shop.environment("INVENTORY_AUDIT_SECRET", tooShort));
                        assertEquals(InventoryApp.CONFIG_ERROR, run.exit(), run.toString());
                        assertTrue(run.log().contains("invalid value for 'audit.secret'"), run.toString());
                        assertHidden(tooShort, run);
                    }
                }),
                test("the secret copied into a value that fails to convert is masked in the error", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        var run = shop.run(Shop.environment(), "--rules.max.quantity=${audit.secret}");
                        assertEquals(InventoryApp.CONFIG_ERROR, run.exit(), run.toString());
                        assertTrue(run.log().contains("invalid value for 'rules.max.quantity'"), run.toString());
                        assertHidden(Shop.SECRET, run);
                    }
                }),
                test("a failing run with every log level on", () -> {
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        shop.write("orders.txt", "apple;1", "broken");
                        var run = shop.run(Shop.environment(), "--logging.level.root=TRACE");
                        assertEquals(InventoryApp.DATA_ERROR, run.exit(), run.toString());
                        assertHidden(Shop.SECRET, run);
                    }
                }));
    }
}
