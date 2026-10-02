package spola.demo;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import spola.test.Suite;

/**
 * The priority order of the configuration from end to end, the one the README of
 * {@code spola.config} recommends: defaults in code, file, profile file, environment
 * (prefix {@code INVENTORY_}), arguments. {@code rules.max.quantity} is set in all five; the
 * rejection reason in the summary shows which value won.
 */
final class PriorityTests {

    private PriorityTests() {
    }

    /** Which sources set {@code rules.max.quantity}; the default in code always does (100). */
    record Layers(boolean file, boolean profile, boolean environment, boolean argument) {
    }

    static String reasonWith(Layers layers) throws Exception {
        try (var shop = Shop.create()) {
            var settings = new ArrayList<>(List.of("inventory.stock.apple=1000", "profiles=dev"));
            if (layers.file()) {
                settings.add("rules.max.quantity=50");
            }
            shop.settings(settings.toArray(String[]::new));
            if (layers.profile()) {
                shop.write("inventory-dev.properties", "rules.max.quantity=20");
            }
            shop.write("orders.txt", "apple;200");
            Map<String, String> environment = layers.environment()
                    ? Shop.environment("INVENTORY_RULES_MAX_QUANTITY", "10")
                    : Shop.environment();
            var run = layers.argument()
                    ? shop.run(environment, "--rules.max.quantity=3")
                    : shop.run(environment);
            assertEquals(InventoryApp.OK, run.exit(), run.toString());
            return run.outLines().get(3);
        }
    }

    static Suite tests() {
        return suite("priority order",
                test("set in default, file, profile, environment and argument: the argument wins", () ->
                        assertEquals("  line 1: apple x 200: exceeds max quantity 3",
                                reasonWith(new Layers(true, true, true, true)))),
                test("without the argument the environment wins", () ->
                        assertEquals("  line 1: apple x 200: exceeds max quantity 10",
                                reasonWith(new Layers(true, true, true, false)))),
                test("without argument and environment the profile file wins", () ->
                        assertEquals("  line 1: apple x 200: exceeds max quantity 20",
                                reasonWith(new Layers(true, true, false, false)))),
                test("with only the file and the default the file wins", () ->
                        assertEquals("  line 1: apple x 200: exceeds max quantity 50",
                                reasonWith(new Layers(true, false, false, false)))),
                test("with nothing else the default in code applies", () ->
                        assertEquals("  line 1: apple x 200: exceeds max quantity 100",
                                reasonWith(new Layers(false, false, false, false)))),
                test("the argument also wins over the environment alone, and the environment over the file", () -> {
                    assertEquals("  line 1: apple x 200: exceeds max quantity 3",
                            reasonWith(new Layers(false, false, true, true)));
                    assertEquals("  line 1: apple x 200: exceeds max quantity 10",
                            reasonWith(new Layers(true, false, true, false)));
                }),
                test("the profile can be activated from the environment or an argument too", () -> {
                    try (var shop = Shop.create()) {
                        shop.settings("inventory.stock.apple=1000", "rules.max.quantity=50");
                        shop.write("inventory-dev.properties", "rules.max.quantity=20");
                        shop.write("orders.txt", "apple;200");
                        assertEquals("  line 1: apple x 200: exceeds max quantity 50",
                                shop.run(Shop.environment()).outLines().get(3));
                        assertEquals("  line 1: apple x 200: exceeds max quantity 20",
                                shop.run(Shop.environment("INVENTORY_PROFILES", "dev")).outLines().get(3));
                        assertEquals("  line 1: apple x 200: exceeds max quantity 20",
                                shop.run(Shop.environment(), "--profiles=dev").outLines().get(3));
                    }
                }));
    }
}
