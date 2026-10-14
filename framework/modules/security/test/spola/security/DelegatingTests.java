package spola.security;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import spola.test.Body;
import spola.test.Suite;

/** Criterion 11: PasswordHasher.delegating. */
final class DelegatingTests {

    private DelegatingTests() {
    }

    static final BCryptPasswordHasher BCRYPT = BCryptPasswordHasher.of(4);
    static final Pbkdf2PasswordHasher PBKDF2 = Support.fast();
    static final String PASSWORD = "correct horse battery staple";
    static final String BCRYPT_HASH = BCryptVectorTests.LIBXCRYPT.get(10).expected();   // "hemligt", $2a$04$

    static PasswordHasher hasher() {
        return PasswordHasher.delegating("bcrypt", Map.of("bcrypt", BCRYPT, "pbkdf2", PBKDF2));
    }

    /** A stored string the delegating hasher refuses, and the text between its braces (if any). */
    record Bad(String label, String encoded, String between) {
        @Override
        public String toString() {
            return label;
        }
    }

    static final String ID_101 = "a".repeat(101);

    static final List<Bad> BAD = List.of(
            new Bad("a bare bcrypt hash without prefix", BCRYPT_HASH, ""),
            new Bad("the empty string", "", ""),
            new Bad("{ without }", "{bcrypt" + BCRYPT_HASH, ""),
            new Bad("only {", "{", ""),
            new Bad("} before {", "}bcrypt{" + BCRYPT_HASH, ""),
            new Bad("{}", "{}" + BCRYPT_HASH, ""),
            new Bad("{BCRYPT}", "{BCRYPT}" + BCRYPT_HASH, "BCRYPT"),
            new Bad("{Bcrypt}", "{Bcrypt}" + BCRYPT_HASH, "Bcrypt"),
            new Bad("{ bcrypt}", "{ bcrypt}" + BCRYPT_HASH, " bcrypt"),
            new Bad("{bcrypt }", "{bcrypt }" + BCRYPT_HASH, "bcrypt "),
            new Bad("an unknown id {scrypt}", "{scrypt}" + BCRYPT_HASH, "scrypt"),
            new Bad("{noop} as in Spring", "{noop}" + PASSWORD, "noop"),
            new Bad("{id} as in Spring's documentation", "{id}" + BCRYPT_HASH, "id"),
            new Bad("{ + a valid bcrypt hash + }", "{" + BCRYPT_HASH + "}", BCRYPT_HASH),
            new Bad("{ + a valid bcrypt hash + } + a hash", "{" + BCRYPT_HASH + "}" + BCRYPT_HASH, BCRYPT_HASH),
            new Bad("an id of 101 characters", "{" + ID_101 + "}" + BCRYPT_HASH, ID_101),
            new Bad("an id of 101 characters without }", "{" + ID_101 + BCRYPT_HASH, ""),
            new Bad("a space before {", " {bcrypt}" + BCRYPT_HASH, ""),
            new Bad("{{bcrypt}}", "{{bcrypt}}" + BCRYPT_HASH, "{bcrypt"));

    /** The message has neither the string, nor the text between the braces, nor the bcrypt hash. */
    static void checkMessage(IllegalArgumentException e, Bad bad) {
        var message = e.getMessage();
        assertTrue(message.startsWith("malformed stored password hash: "), message);
        assertTrue(message.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f), message);
        assertTrue(e.getCause() == null, "no cause");
        if (!bad.encoded().isEmpty()) {
            assertFalse(message.contains(bad.encoded()), "the message contains the string");
        }
        if (!bad.between().isEmpty()) {
            assertFalse(message.contains(bad.between()), "the message contains the text between the braces");
        }
        assertFalse(message.contains(BCRYPT_HASH.substring(7)), "the message contains the hash");
    }

    static Suite tests() {
        var hasher = hasher();
        return suite("PasswordHasher.delegating",
                test("null default id, map, id or hasher gives NullPointerException", () -> {
                    var withNullKey = new HashMap<String, PasswordHasher>();
                    withNullKey.put("bcrypt", BCRYPT);
                    withNullKey.put(null, PBKDF2);
                    var withNullValue = new HashMap<String, PasswordHasher>();
                    withNullValue.put("bcrypt", BCRYPT);
                    withNullValue.put("pbkdf2", null);
                    List<Body> calls = List.of(
                            () -> PasswordHasher.delegating(null, Map.of("bcrypt", BCRYPT)),
                            () -> PasswordHasher.delegating("bcrypt", null),
                            () -> PasswordHasher.delegating("bcrypt", withNullKey),
                            () -> PasswordHasher.delegating("bcrypt", withNullValue));
                    for (var call : calls) {
                        assertThrows(NullPointerException.class, call);
                    }
                }),
                test("an empty map, an unknown default id and bad ids give IllegalArgumentException naming the problem", () -> {
                    record Refused(String defaultId, String id, String problem) {
                    }
                    var cases = List.of(
                            new Refused("bcrypt", null, "must not be empty"),
                            new Refused("scrypt", "bcrypt", "defaultId must be one of the ids"),
                            new Refused("BCRYPT", "bcrypt", "defaultId must be one of the ids"),
                            new Refused("", "", "1 to 100 characters"),
                            new Refused("x", "a".repeat(101), "1 to 100 characters"),
                            new Refused("x", "{bcrypt", "must not contain { or }"),
                            new Refused("x", "bcrypt}", "must not contain { or }"),
                            new Refused("x", "{bcrypt}", "must not contain { or }"),
                            new Refused("x", "b crypt", "printable ASCII"),
                            new Refused("x", " bcrypt", "printable ASCII"),
                            new Refused("x", "bcrypt\t", "printable ASCII"),
                            new Refused("x", "bcrypt\n", "printable ASCII"),
                            new Refused("x", "bcrypt\u007f", "printable ASCII"),
                            new Refused("x", "bcrypt\u0000", "printable ASCII"),
                            new Refused("x", "bcrÿpt", "printable ASCII"),
                            new Refused("x", "é", "printable ASCII"),
                            new Refused("x", "бcrypt", "printable ASCII"));
                    for (var c : cases) {
                        var map = new HashMap<String, PasswordHasher>();
                        if (c.id() != null) {
                            map.put(c.id(), BCRYPT);
                            map.put("x", PBKDF2);
                        }
                        var e = assertThrows(IllegalArgumentException.class, () -> PasswordHasher.delegating(c.defaultId(), map));
                        assertTrue(e.getMessage().contains(c.problem()), "message for " + c + ": " + e.getMessage());
                    }
                }),
                test("ids of 1 and 100 characters, and any printable ASCII but braces, are accepted", () -> {
                    var longest = "a".repeat(100);
                    var odd = "!\"#$%&'()*+,-./0-9:;<=>?@A-Z[\\]^_`a-z|~";
                    var map = Map.<String, PasswordHasher>of("b", BCRYPT, longest, PBKDF2, odd, BCRYPT);
                    var delegating = PasswordHasher.delegating(longest, map);
                    var hash = delegating.hash(PASSWORD);
                    assertTrue(hash.startsWith("{" + longest + "}$pbkdf2-sha256$"), "prefix");
                    assertTrue(delegating.matches(PASSWORD, hash));
                    assertFalse(delegating.needsRehash(hash));
                    assertTrue(delegating.matches("hemligt", "{b}" + BCRYPT_HASH));
                    assertTrue(delegating.matches("hemligt", "{" + odd + "}" + BCRYPT_HASH));
                    assertTrue(delegating.needsRehash("{b}" + BCRYPT_HASH));
                }),
                test("a change to the given map afterwards is not seen", () -> {
                    var map = new HashMap<String, PasswordHasher>();
                    map.put("bcrypt", BCRYPT);
                    map.put("pbkdf2", PBKDF2);
                    var delegating = PasswordHasher.delegating("bcrypt", map);
                    var pbkdf2Hash = "{pbkdf2}" + PBKDF2.hash(PASSWORD);
                    map.remove("pbkdf2");
                    map.put("scrypt", BCRYPT);
                    map.put("bcrypt", PBKDF2);
                    assertTrue(delegating.matches(PASSWORD, pbkdf2Hash));
                    assertThrows(IllegalArgumentException.class, () -> delegating.matches("hemligt", "{scrypt}" + BCRYPT_HASH));
                    assertTrue(delegating.hash(PASSWORD).startsWith("{bcrypt}$2a$04$"), "still bcrypt");
                    assertEquals("DelegatingPasswordHasher[default=bcrypt, ids=[bcrypt, pbkdf2]]", delegating.toString());
                }),
                test("hash gives {default id} followed by the delegate's hash", () -> {
                    var hash = hasher.hash(PASSWORD);
                    assertTrue(Pattern.matches("\\{bcrypt\\}\\$2a\\$04\\$[./A-Za-z0-9]{53}", hash), "format");
                    assertTrue(BCRYPT.matches(PASSWORD, hash.substring("{bcrypt}".length())));
                    var pbkdf2Default = PasswordHasher.delegating("pbkdf2", Map.of("bcrypt", BCRYPT, "pbkdf2", PBKDF2));
                    var other = pbkdf2Default.hash(PASSWORD);
                    assertTrue(other.startsWith("{pbkdf2}$pbkdf2-sha256$i=10000$"), "format");
                    assertTrue(PBKDF2.matches(PASSWORD, other.substring("{pbkdf2}".length())));
                }),
                test("matches works for each id, and is false for a wrong password", () -> {
                    var bcryptHash = "{bcrypt}" + BCRYPT.hash(PASSWORD);
                    var pbkdf2Hash = "{pbkdf2}" + PBKDF2.hash(PASSWORD);
                    for (var stored : List.of(bcryptHash, pbkdf2Hash, hasher.hash(PASSWORD))) {
                        assertTrue(hasher.matches(PASSWORD, stored));
                        assertTrue(hasher.matches(new StringBuilder(PASSWORD), stored));
                        assertFalse(hasher.matches(PASSWORD + " ", stored));
                        assertFalse(hasher.matches("", stored));
                    }
                    assertTrue(hasher.matches("hemligt", "{bcrypt}" + BCRYPT_HASH));
                    // A password bcrypt refuses is false, as from bcrypt itself.
                    assertFalse(hasher.matches("x".repeat(73), bcryptHash));
                    assertTrue(hasher.matches("x".repeat(80), "{pbkdf2}" + PBKDF2.hash("x".repeat(80))));
                }),
                test("the delegate's exceptions from hash propagate; a delegate returning null gives NullPointerException", () -> {
                    var e = assertThrows(IllegalArgumentException.class, () -> hasher.hash("x".repeat(73)));
                    assertFalse(e.getMessage().contains("xxxxxxxx"));
                    assertThrows(NullPointerException.class, () -> hasher.hash(null));
                    var nulls = PasswordHasher.delegating("n", Map.of("n", new PasswordHasher() {
                        @Override
                        public String hash(CharSequence password) {
                            return null;
                        }

                        @Override
                        public boolean matches(CharSequence password, String encoded) {
                            return false;
                        }

                        @Override
                        public boolean needsRehash(String encoded) {
                            return false;
                        }
                    }));
                    assertThrows(NullPointerException.class, () -> nulls.hash(PASSWORD));
                }),
                parameterized("a malformed prefix throws IllegalArgumentException without the string or the id", BAD, bad -> {
                    checkMessage(assertThrows(IllegalArgumentException.class, () -> hasher.matches("hemligt", bad.encoded())), bad);
                    checkMessage(assertThrows(IllegalArgumentException.class, () -> hasher.matches("", bad.encoded())), bad);
                    checkMessage(assertThrows(IllegalArgumentException.class, () -> hasher.needsRehash(bad.encoded())), bad);
                }),
                test("{bcrypt} without a rest and {bcrypt} + a PBKDF2 string give the delegate's IllegalArgumentException", () -> {
                    var pbkdf2String = PBKDF2.hash(PASSWORD);
                    for (var rest : List.of("", pbkdf2String, "$2a$04$", BCRYPT_HASH + "\n")) {
                        var own = assertThrows(IllegalArgumentException.class, () -> BCRYPT.matches(PASSWORD, rest));
                        var delegated = assertThrows(IllegalArgumentException.class, () -> hasher.matches(PASSWORD, "{bcrypt}" + rest));
                        assertEquals(own.getMessage(), delegated.getMessage());
                        var fromRehash = assertThrows(IllegalArgumentException.class, () -> hasher.needsRehash("{bcrypt}" + rest));
                        assertEquals(own.getMessage(), fromRehash.getMessage());
                    }
                    var own = assertThrows(IllegalArgumentException.class, () -> PBKDF2.matches(PASSWORD, BCRYPT_HASH));
                    var delegated = assertThrows(IllegalArgumentException.class, () -> hasher.matches(PASSWORD, "{pbkdf2}" + BCRYPT_HASH));
                    assertEquals(own.getMessage(), delegated.getMessage());
                }),
                test("needsRehash with bcrypt of(10) as default", () -> {
                    var delegating = PasswordHasher.delegating("bcrypt", Map.of(
                            "bcrypt", BCryptPasswordHasher.of(10), "pbkdf2", PBKDF2));
                    var tail = BCRYPT_HASH.substring(7);
                    assertTrue(delegating.needsRehash("{bcrypt}$2a$04$" + tail));
                    assertTrue(delegating.needsRehash("{pbkdf2}" + PBKDF2.hash(PASSWORD)));
                    assertFalse(delegating.needsRehash("{bcrypt}$2a$10$" + tail));
                    assertFalse(delegating.needsRehash("{bcrypt}$2b$10$" + tail));
                    assertFalse(delegating.needsRehash("{bcrypt}$2y$10$" + tail));
                    assertFalse(delegating.needsRehash("{bcrypt}$2a$12$" + tail));
                    // The delegate is always asked, so a malformed rest throws also for another id.
                    var malformedRest = "{pbkdf2}$pbkdf2-sha256$i=0$AAAAAAAAAAAAAAAAAAAAAA$" + "A".repeat(43);
                    assertThrows(IllegalArgumentException.class, () -> delegating.needsRehash(malformedRest));
                    assertThrows(IllegalArgumentException.class, () -> delegating.needsRehash("{pbkdf2}" + BCRYPT_HASH));
                    assertThrows(IllegalArgumentException.class, () -> delegating.needsRehash("{bcrypt}$2a$03$" + tail));
                    assertThrows(NullPointerException.class, () -> delegating.needsRehash(null));
                }),
                test("needsRehash asks the delegate exactly once, also for another id", () -> {
                    var bcrypt = new Support.CountingHasher(BCRYPT);
                    var pbkdf2 = new Support.CountingHasher(PBKDF2);
                    var delegating = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", pbkdf2));
                    assertTrue(delegating.needsRehash("{pbkdf2}" + PBKDF2.hash(PASSWORD)));
                    assertEquals(1, pbkdf2.needsRehashCalls.get());
                    assertEquals(0, bcrypt.needsRehashCalls.get());
                    assertFalse(delegating.needsRehash("{bcrypt}" + BCRYPT_HASH));
                    assertEquals(1, bcrypt.needsRehashCalls.get());
                    assertTrue(delegating.matches("hemligt", "{bcrypt}" + BCRYPT_HASH));
                    assertEquals(1, bcrypt.matchesCalls.get());
                    assertEquals(0, pbkdf2.matchesCalls.get());
                }),
                test("null arguments give NullPointerException", () -> {
                    assertThrows(NullPointerException.class, () -> hasher.matches(null, "{bcrypt}" + BCRYPT_HASH));
                    assertThrows(NullPointerException.class, () -> hasher.matches(PASSWORD, null));
                    assertThrows(NullPointerException.class, () -> hasher.needsRehash(null));
                }),
                test("toString shows the default id and the sorted ids, never a hash", () -> {
                    assertEquals("DelegatingPasswordHasher[default=bcrypt, ids=[bcrypt, pbkdf2]]", hasher.toString());
                    var many = PasswordHasher.delegating("m", Map.of("z", BCRYPT, "a", PBKDF2, "m", BCRYPT, "B", PBKDF2));
                    assertEquals("DelegatingPasswordHasher[default=m, ids=[B, a, m, z]]", many.toString());
                    hasher.matches("hemligt", "{bcrypt}" + BCRYPT_HASH);
                    assertFalse(hasher.toString().contains("$2a$"));
                }));
    }
}
