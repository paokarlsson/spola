package spola.di;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNotEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import spola.test.Suite;

/** Keys and qualifiers. */
final class KeyTests {

    private KeyTests() {
    }

    enum Region { EU, US }

    record Zone(String id) {
    }

    interface Gateway {
    }

    static Suite tests() {
        return suite("keys",
                test("equal when type and qualifier are equal", () -> {
                    assertEquals(Key.of(Gateway.class), Key.of(Gateway.class));
                    assertEquals(Key.of(Gateway.class, Region.EU), Key.of(Gateway.class, Region.EU));
                    assertEquals(Key.of(Gateway.class, new Zone("a")), Key.of(Gateway.class, new Zone("a")));
                    assertEquals(Key.of(Gateway.class), new Key<>(Gateway.class, null));
                }),
                test("different qualifiers or none make different keys", () -> {
                    assertNotEquals(Key.of(Gateway.class, Region.EU), Key.of(Gateway.class, Region.US));
                    assertNotEquals(Key.of(Gateway.class), Key.of(Gateway.class, Region.EU));
                    assertNotEquals(Key.of(Gateway.class, "EU"), Key.of(Gateway.class, Region.EU));
                }),
                test("toString shows the canonical name and the qualifier", () -> {
                    assertEquals("spola.di.KeyTests.Gateway", Key.of(Gateway.class).toString());
                    assertEquals("spola.di.KeyTests.Gateway[EU]", Key.of(Gateway.class, Region.EU).toString());
                    assertEquals("spola.di.KeyTests.Gateway[\"eu\"]", Key.of(Gateway.class, "eu").toString());
                    assertEquals("spola.di.KeyTests.Gateway -> java.lang.String",
                            Key.chain(List.of(Key.of(Gateway.class), Key.of(String.class))));
                }),
                test("primitive types, array qualifiers and null are refused", () -> {
                    assertThrows(IllegalArgumentException.class, () -> Key.of(int.class));
                    assertThrows(IllegalArgumentException.class, () -> Key.of(void.class));
                    assertThrows(IllegalArgumentException.class, () -> Key.of(Gateway.class, new String[] {"eu"}));
                    assertThrows(NullPointerException.class, () -> Key.of(Gateway.class, null));
                    assertThrows(NullPointerException.class, () -> Key.of(null));
                }),
                test("dependencies keep order and drop duplicates", () -> {
                    var deps = Dependencies.of(Gateway.class).and(Key.of(Gateway.class, Region.EU)).and(Gateway.class);
                    assertEquals(List.of(Key.of(Gateway.class), Key.of(Gateway.class, Region.EU)), deps.keys());
                    assertEquals(List.of(), Dependencies.none().keys());
                }));
    }
}
