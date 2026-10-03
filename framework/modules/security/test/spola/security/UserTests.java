package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import spola.test.Suite;

/** Criterion 6: User, InMemoryUserStore and Identity. */
final class UserTests {

    private UserTests() {
    }

    static final String HASH = "$pbkdf2-sha256$i=10000$SECRETMARKERhashAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    static final List<String> BAD_ROLES = List.of("", "has space", "tab\there", "line\n", "r".repeat(101), "\u00e9t\u00e9",
            "ROLE\u202e", "\u0000");

    static Suite tests() {
        return suite("User, InMemoryUserStore and Identity",
                test("User: null components give NullPointerException", () -> {
                    assertThrows(NullPointerException.class, () -> new User(null, HASH, Set.of(), true));
                    assertThrows(NullPointerException.class, () -> new User("ada", null, Set.of(), true));
                    assertThrows(NullPointerException.class, () -> new User("ada", HASH, null, true));
                    assertThrows(NullPointerException.class, () -> new User("ada", HASH, new HashSet<>(Arrays.asList("A", null)), true));
                }),
                test("User: an empty or too long username gives IllegalArgumentException; 1 and 256 work", () -> {
                    assertThrows(IllegalArgumentException.class, () -> new User("", HASH, Set.of(), true));
                    assertThrows(IllegalArgumentException.class, () -> new User("u".repeat(257), HASH, Set.of(), true));
                    assertEquals(1, new User("u", HASH, Set.of(), true).username().length());
                    assertEquals(256, new User("u".repeat(256), HASH, Set.of(), true).username().length());
                }),
                test("User and Identity: invalid roles give IllegalArgumentException; 100 characters work", () -> {
                    for (var role : BAD_ROLES) {
                        assertThrows(IllegalArgumentException.class, () -> new User("ada", HASH, Set.of(role), true));
                        assertThrows(IllegalArgumentException.class, () -> new Identity("ada", Set.of(role)));
                    }
                    var longest = "R".repeat(100);
                    assertEquals(Set.of(longest, "!~"), new User("ada", HASH, Set.of(longest, "!~"), true).roles());
                }),
                test("User: roles cannot be changed, not even through the original set", () -> {
                    var original = new HashSet<>(Set.of("USER"));
                    var user = new User("ada", HASH, original, true);
                    original.add("ADMIN");
                    original.remove("USER");
                    assertEquals(Set.of("USER"), user.roles());
                    assertThrows(UnsupportedOperationException.class, () -> user.roles().add("ADMIN"));
                    assertThrows(UnsupportedOperationException.class, () -> user.roles().clear());
                    assertThrows(UnsupportedOperationException.class, () -> user.roles().iterator().remove());
                    assertEquals(Set.of("USER"), user.roles());
                }),
                test("User: toString has the username, roles and flag, but not the hash", () -> {
                    var user = new User("ada", HASH, Set.of("USER", "ADMIN"), false);
                    assertEquals("User[username=ada, roles=[ADMIN, USER], enabled=false]", user.toString());
                    assertFalse(user.toString().contains("SECRETMARKER"));
                    assertEquals(HASH, user.passwordHash());
                }),
                test("User and Identity: toString escapes control and bidi characters in the name", () -> {
                    var user = new User("a\nb\u202ec\\d\ud800", HASH, Set.of(), true);
                    assertEquals("User[username=a\\u000ab\\u202ec\\\\d\\ud800, roles=[], enabled=true]", user.toString());
                    assertEquals("Identity[name=\\u0000\ud83d\ude00, roles=[A]]", new Identity("\u0000\ud83d\ude00", Set.of("A")).toString());
                }),
                test("InMemoryUserStore: add and find; a duplicate gives IllegalArgumentException", () -> {
                    var store = new InMemoryUserStore();
                    var ada = new User("ada", HASH, Set.of("USER"), true);
                    store.add(ada);
                    assertEquals(Optional.of(ada), store.find("ada"));
                    var duplicate = assertThrows(IllegalArgumentException.class,
                            () -> store.add(new User("ada", "other", Set.of(), false)));
                    assertFalse(duplicate.getMessage().contains("ada"));
                    assertEquals(Optional.of(ada), store.find("ada"));
                    assertThrows(NullPointerException.class, () -> store.add(null));
                    assertThrows(NullPointerException.class, () -> store.find(null));
                    assertEquals("InMemoryUserStore[users=1]", store.toString());
                }),
                test("InMemoryUserStore: find is case-sensitive and does not normalise", () -> {
                    var store = new InMemoryUserStore();
                    store.add(new User("Ada", HASH, Set.of(), true));
                    store.add(new User("\u00e9", HASH, Set.of(), true));
                    assertTrue(store.find("Ada").isPresent());
                    assertEquals(Optional.empty(), store.find("ada"));
                    assertEquals(Optional.empty(), store.find("ADA"));
                    assertEquals(Optional.empty(), store.find(" Ada"));
                    assertEquals(Optional.empty(), store.find("e\u0301"));
                    store.add(new User("ada", HASH, Set.of(), true));
                    assertEquals("ada", store.find("ada").orElseThrow().username());
                }),
                test("InMemoryUserStore: updatePassword replaces the hash and keeps the rest", () -> {
                    var store = new InMemoryUserStore();
                    store.add(new User("ada", HASH, Set.of("USER"), false));
                    store.updatePassword("ada", "new-hash");
                    assertEquals(new User("ada", "new-hash", Set.of("USER"), false), store.find("ada").orElseThrow());
                    assertThrows(IllegalArgumentException.class, () -> store.updatePassword("bob", "x"));
                    assertThrows(IllegalArgumentException.class, () -> store.updatePassword("Ada", "x"));
                    assertThrows(NullPointerException.class, () -> store.updatePassword(null, "x"));
                    assertThrows(NullPointerException.class, () -> store.updatePassword("ada", null));
                    assertEquals("new-hash", store.find("ada").orElseThrow().passwordHash());
                    assertEquals(Optional.empty(), store.find("bob"));
                }),
                test("Identity: checks, unmodifiable roles and hasRole", () -> {
                    assertThrows(NullPointerException.class, () -> new Identity(null, Set.of()));
                    assertThrows(NullPointerException.class, () -> new Identity("ada", null));
                    assertThrows(IllegalArgumentException.class, () -> new Identity("", Set.of()));
                    assertThrows(IllegalArgumentException.class, () -> new Identity("u".repeat(257), Set.of()));
                    var original = new HashSet<>(Set.of("USER", "ADMIN"));
                    var identity = new Identity("ada", original);
                    original.clear();
                    assertEquals(Set.of("USER", "ADMIN"), identity.roles());
                    assertThrows(UnsupportedOperationException.class, () -> identity.roles().add("ROOT"));
                    assertTrue(identity.hasRole("ADMIN"));
                    assertFalse(identity.hasRole("admin"));
                    assertFalse(identity.hasRole("ROLE_ADMIN"));
                    assertThrows(NullPointerException.class, () -> identity.hasRole(null));
                    assertEquals("Identity[name=ada, roles=[ADMIN, USER]]", identity.toString());
                }),
                test("AuthResult: components are required", () -> {
                    assertThrows(NullPointerException.class, () -> new AuthResult.Authenticated(null));
                    assertThrows(NullPointerException.class, () -> new AuthResult.Failed(null));
                }));
    }
}
