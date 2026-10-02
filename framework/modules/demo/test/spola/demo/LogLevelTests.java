package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import spola.test.Suite;

/** {@code logging.level.*} from the configuration decides what is logged, through {@code Logging.configure}. */
final class LogLevelTests {

    private LogLevelTests() {
    }

    static final String STOCK_DEBUG = "spola.demo.Stock : took 3 x apple, 7 left";
    static final String PROCESSOR_DEBUG = "spola.demo.OrderProcessor : rejected line 2: pear x 6: insufficient stock (5 left)";
    static final String INFO = "spola.demo.OrderProcessor : processed 3 orders, rejected 4";

    /** Whether a log line at {@code level} ends with {@code text}. */
    static boolean logged(Shop.Run run, String level, String text) {
        var padded = (level + "     ").substring(0, 5) + " [";
        return run.log().lines().anyMatch(line -> line.contains(padded) && line.endsWith(text));
    }

    static Shop.Run run(java.util.Map<String, String> environment, String... args) throws Exception {
        try (var shop = Shop.create()) {
            ProgramTests.standardShop(shop);
            var run = shop.run(environment, args);
            assertEquals(InventoryApp.OK, run.exit(), run.toString());
            assertEquals(ProgramTests.SUMMARY, run.outLines(), "log levels do not change the summary");
            return run;
        }
    }

    static Suite tests() {
        return suite("log levels",
                test("by default (root INFO) there are info lines and no debug lines", () -> {
                    var run = run(Shop.environment());
                    assertTrue(logged(run, "INFO", INFO), run.toString());
                    assertFalse(run.log().contains("DEBUG"), run.toString());
                }),
                test("logging.level.spola.demo=DEBUG as an argument gives the debug lines", () -> {
                    var run = run(Shop.environment(), "--logging.level.spola.demo=DEBUG");
                    assertTrue(logged(run, "DEBUG", STOCK_DEBUG), run.toString());
                    assertTrue(logged(run, "DEBUG", PROCESSOR_DEBUG), run.toString());
                }),
                test("a level for one class leaves the others at the root level", () -> {
                    var run = run(Shop.environment(), "--logging.level.spola.demo.Stock=debug");
                    assertTrue(logged(run, "DEBUG", STOCK_DEBUG), run.toString());
                    assertFalse(logged(run, "DEBUG", PROCESSOR_DEBUG), run.toString());
                }),
                test("the level can come from the environment and from a profile file", () -> {
                    var fromEnvironment = run(Shop.environment("INVENTORY_LOGGING_LEVEL_SPOLA_DEMO", "DEBUG"));
                    assertTrue(logged(fromEnvironment, "DEBUG", STOCK_DEBUG), fromEnvironment.toString());
                    try (var shop = Shop.create()) {
                        ProgramTests.standardShop(shop);
                        shop.write("inventory-dev.properties", "logging.level.spola.demo=DEBUG");
                        var plain = shop.run(Shop.environment());
                        assertFalse(plain.log().contains("DEBUG"), plain.toString());
                        var dev = shop.run(Shop.environment(), "--profiles=dev");
                        assertTrue(logged(dev, "DEBUG", STOCK_DEBUG), dev.toString());
                    }
                }),
                test("logging.level.root=WARN turns the info lines off", () -> {
                    var run = run(Shop.environment(), "--logging.level.root=WARN");
                    assertEquals("", run.log(), run.toString());
                }),
                test("logging is reset when the run ends: later runs do not inherit levels or the stream", () -> {
                    run(Shop.environment(), "--logging.level.spola.demo=DEBUG");
                    var after = run(Shop.environment());
                    assertFalse(after.log().contains("DEBUG"), after.toString());
                }));
    }
}
