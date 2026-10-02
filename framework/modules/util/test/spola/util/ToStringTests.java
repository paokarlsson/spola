package spola.util;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** {@link ToString}: the same form as a record, secrets never shown. */
final class ToStringTests {

    private ToStringTests() {
    }

    record Address(String city, int zip) {
    }

    record Customer(String name, int age, double score, boolean active, char grade, Object missing,
                    List<String> tags, Address address) {
    }

    record Empty() {
    }

    record User(String name, String password) {
        @Override
        public String toString() {
            return ToString.of("User").add("name", name).secret("password", password).toString();
        }
    }

    /** A secret that counts how often its {@code toString()} is called. */
    static final class Token {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String toString() {
            calls.incrementAndGet();
            return "token-value";
        }
    }

    static Suite tests() {
        return suite("toString",
                test("plain fields give exactly a record's toString", () -> {
                    var c = new Customer("ada", 36, 1.5, true, 'A', null, List.of("x", "y"), new Address("Lund", 22100));
                    var built = ToString.of("Customer")
                            .add("name", c.name()).add("age", c.age()).add("score", c.score())
                            .add("active", c.active()).add("grade", c.grade()).add("missing", c.missing())
                            .add("tags", c.tags()).add("address", c.address())
                            .toString();
                    assertEquals(c.toString(), built);
                    assertEquals(new Empty().toString(), ToString.of("Empty").toString());
                }),
                test("a secret is masked: not its value, not its length, and its toString is not called", () -> {
                    var user = new User("ada", "hunter2");
                    assertEquals("User[name=ada, password=***]", user.toString());
                    assertFalse(user.toString().contains("hunter2"));
                    assertEquals(new User("ada", "x").toString(), new User("ada", "a-much-longer-password").toString());
                    var token = new Token();
                    var text = ToString.of("Session").secret("token", token).toString();
                    assertEquals("Session[token=***]", text);
                    assertEquals(0, token.calls.get());
                }),
                test("a null secret is written as null", () -> {
                    assertEquals("User[name=ada, password=null]", new User("ada", null).toString());
                }));
    }
}
