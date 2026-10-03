package spola.security;

import static spola.security.Support.fast;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import spola.test.Body;
import spola.test.Suite;

/**
 * Criterion 9: neither passwords nor hashes appear in toString() of User, Identity and
 * AuthResult, or in the message of any exception the tests of criteria 4 to 7 provoke.
 */
final class SecretTests {

    private SecretTests() {
    }

    /** Markers: in passwords, and in the Base64 of stored hashes. */
    static final String PASSWORD_MARKER = "PWMARKER";
    static final String HASH_MARKER = "HASHMARKER";

    static Suite tests() {
        return suite("secrets are not echoed",
                test("toString of User, Identity, AuthResult, the store, the hasher and the authenticator", () -> {
                    var hasher = fast();
                    var password = "secret-" + PASSWORD_MARKER;
                    var hash = hasher.hash(password);
                    var store = new InMemoryUserStore();
                    var user = new User("ada", hash, Set.of("USER"), true);
                    store.add(user);
                    var authenticator = Authenticator.of(store, hasher, store::updatePassword);
                    var hashSalt = hash.split("\\$")[3];
                    var hashValue = hash.split("\\$")[4];
                    List<Object> objects = List.of(user, new Identity("ada", Set.of("USER")),
                            authenticator.authenticate("ada", password), authenticator.authenticate("ada", "x" + password),
                            new User("bob", hash, Set.of(), false), store, hasher, authenticator,
                            new User("carl", "$pbkdf2-sha256$i=1$" + HASH_MARKER + "AAAA$" + HASH_MARKER, Set.of(), true));
                    for (var object : objects) {
                        var text = object.toString();
                        for (var secret : List.of(PASSWORD_MARKER, HASH_MARKER, hash, hashSalt, hashValue, "pbkdf2")) {
                            assertFalse(text.contains(secret), object.getClass().getSimpleName() + ".toString() has a secret");
                        }
                    }
                }),
                test("no exception provoked by the tests of criteria 4 to 7 carries a marker", () -> {
                    var thrown = provoke();
                    assertTrue(thrown.size() >= 30, "provoked " + thrown.size());
                    for (var t : thrown) {
                        for (Throwable e = t; e != null; e = e.getCause()) {
                            var text = String.valueOf(e.getMessage()) + " " + e;
                            assertFalse(text.contains(PASSWORD_MARKER), "password marker in " + e.getClass().getName());
                            assertFalse(text.contains(HASH_MARKER), "hash marker in " + e.getClass().getName());
                        }
                        var trace = new StringWriter();
                        t.printStackTrace(new PrintWriter(trace));
                        assertFalse(trace.toString().contains(PASSWORD_MARKER));
                        assertFalse(trace.toString().contains(HASH_MARKER));
                    }
                }));
    }

    /** Runs every exception-provoking call of criteria 4 to 7, with markers in passwords and hashes. */
    static List<Throwable> provoke() {
        var hasher = fast();
        var salt = "AAAAAAAAAAAAAAAAAAAAAA";
        var hash = "A".repeat(43);
        var marked = List.of(
                "$pbkdf2$i=10000$" + salt + "$" + hash + HASH_MARKER,
                "$pbkdf2-sha512$i=10000$" + salt + "$" + HASH_MARKER + "A",
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER,
                "$pbkdf2-sha256$i=10000$" + salt + "$" + hash + "$" + HASH_MARKER,
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "AA==$" + hash,
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "-_AA$" + hash,
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "AAAB$" + hash,
                "$pbkdf2-sha256$i=010000$" + HASH_MARKER + "AAAAAAAAAAAA$" + hash,
                "$pbkdf2-sha256$i=0$" + HASH_MARKER + "AAAAAAAAAAAA$" + hash,
                "$pbkdf2-sha256$i=10000001$" + HASH_MARKER + "AAAAAAAAAAAA$" + hash,
                "$pbkdf2-sha256$i=" + HASH_MARKER + "$" + salt + "$" + hash,
                "$pbkdf2-sha256$i=4294977296$" + HASH_MARKER + "AAAAAAAAAAAA$" + hash,
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "$" + hash,
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "A".repeat(82) + "$" + hash,
                "$pbkdf2-sha256$i=10000$" + salt + "$" + HASH_MARKER + "AA",
                "$pbkdf2-sha256$i=10000$" + salt + "$" + HASH_MARKER + "A".repeat(82),
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "A".repeat(250),
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "\n$" + hash,
                HASH_MARKER);
        var password = "pw-" + PASSWORD_MARKER;
        var calls = new ArrayList<Body>();
        for (var encoded : marked) {
            calls.add(() -> hasher.matches(password, encoded));
            calls.add(() -> hasher.needsRehash(encoded));
        }
        // Criterion 4: refused passwords and settings.
        calls.add(() -> hasher.hash(""));
        calls.add(() -> hasher.hash(PASSWORD_MARKER.repeat(200)));
        calls.add(() -> hasher.hash(PASSWORD_MARKER + "\ud800"));
        calls.add(() -> hasher.hash(null));
        calls.add(() -> hasher.matches(null, HASH_MARKER));
        calls.add(() -> Pbkdf2PasswordHasher.of(9_999, 16, 32));
        calls.add(() -> Pbkdf2.deriveSha256(PASSWORD_MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[0], 1, 32));
        // Criterion 6: users and the store.
        var marker = HASH_MARKER;
        calls.add(() -> new User("", marker, Set.of(), true));
        calls.add(() -> new User("u".repeat(257), marker, Set.of(), true));
        calls.add(() -> new User("ada", marker, Set.of("bad role"), true));
        calls.add(() -> new User(null, marker, Set.of(), true));
        var store = new InMemoryUserStore();
        store.add(new User("ada", "$pbkdf2-sha256$i=0$" + HASH_MARKER + "AAAAAAAAAAAA$" + hash, Set.of(), true));
        calls.add(() -> store.add(new User("ada", marker, Set.of(), true)));
        calls.add(() -> store.updatePassword("nobody", marker));
        // Criterion 7: the authenticator.
        calls.add(() -> Authenticator.of(store, hasher).authenticate("ada", password));
        calls.add(() -> Authenticator.of(username -> null, hasher).authenticate("ada", password));
        var thrown = new ArrayList<Throwable>();
        for (var call : calls) {
            try {
                call.run();
                throw new AssertionError("call " + calls.indexOf(call) + " did not throw");
            } catch (RuntimeException e) {
                thrown.add(e);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        return thrown;
    }
}
