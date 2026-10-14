package spola.security;

import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import spola.security.Support.RecordingLogger;
import spola.test.Body;
import spola.test.Suite;

/**
 * Criterion 14: no password and no stored hash in toString() of BCryptPasswordHasher and the
 * delegating hasher, or in the message of any exception that the tests of criteria 7 to 12
 * provoke, also the text between the braces of criterion 11.
 */
final class BCryptSecretTests {

    private BCryptSecretTests() {
    }

    /** Markers: in passwords, and in stored hashes (letters only, so they fit bcrypt's alphabet). */
    static final String PASSWORD_MARKER = "PWMARKER";
    static final String HASH_MARKER = "HASHMARKER";

    /** A 53-character tail with the marker in it, made canonical. */
    static final String MARKED_TAIL = "HASHMARKER" + "A".repeat(11) + "." + "HASHMARKER" + "B".repeat(20) + ".";

    static Suite tests() {
        return suite("bcrypt and delegating: secrets are not echoed",
                test("toString of the hashers and of an authenticator using them", () -> {
                    var bcrypt = BCryptPasswordHasher.of(4);
                    var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", Support.fast()));
                    var password = "secret-" + PASSWORD_MARKER;
                    var stored = hasher.hash(password);
                    assertTrue(hasher.matches(password, stored));
                    var users = new InMemoryUserStore();
                    users.add(new User("ada", stored, Set.of("USER"), true));
                    var authenticator = Authenticator.of(users, hasher, users.upgrader(hasher));
                    authenticator.authenticate("ada", password);
                    for (var object : List.<Object>of(bcrypt, hasher, BCryptPasswordHasher.defaults(), authenticator)) {
                        var text = object.toString();
                        for (var secret : List.of(PASSWORD_MARKER, stored, stored.substring(15), "$2a$", "secret")) {
                            assertFalse(text.contains(secret), object.getClass().getSimpleName() + ".toString() has a secret");
                        }
                    }
                }),
                test("no exception provoked by the tests of criteria 7 to 12 carries a marker", () -> {
                    var thrown = provoke();
                    assertTrue(thrown.size() >= 40, "provoked " + thrown.size());
                    for (var t : thrown) {
                        for (Throwable e = t; e != null; e = e.getCause()) {
                            var text = e.getMessage() + " " + e;
                            assertFalse(text.contains(PASSWORD_MARKER), "password marker in " + e.getClass().getName());
                            assertFalse(text.contains(HASH_MARKER), "hash marker in " + e.getClass().getName());
                        }
                        var trace = new StringWriter();
                        t.printStackTrace(new PrintWriter(trace));
                        assertFalse(trace.toString().contains(PASSWORD_MARKER));
                        assertFalse(trace.toString().contains(HASH_MARKER));
                    }
                }),
                test("the log line of a failed upgrade has no marker", () -> {
                    var pbkdf2 = Support.fast();
                    var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", BCryptPasswordHasher.of(4), "pbkdf2", pbkdf2));
                    var password = PASSWORD_MARKER.repeat(10);
                    var users = new InMemoryUserStore();
                    users.add(new User("ada", "{pbkdf2}" + pbkdf2.hash(password), Set.of(), true));
                    var log = new RecordingLogger();
                    var authenticator = Authenticator.of(users, hasher, users.upgrader(hasher), log);
                    assertTrue(authenticator.authenticate("ada", password) instanceof AuthResult.Authenticated);
                    assertTrue(log.entries().size() == 1, "one log line");
                    for (var entry : log.entries()) {
                        assertFalse(entry.message().contains(PASSWORD_MARKER));
                        assertFalse(entry.message().contains(HASH_MARKER));
                    }
                }));
    }

    /** Runs every exception-provoking call of criteria 7 to 12, with markers in passwords and hashes. */
    static List<Throwable> provoke() {
        var bcrypt = BCryptPasswordHasher.of(4);
        var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", Support.fast()));
        var valid = "$2a$04$" + MARKED_TAIL;
        var password = "pw-" + PASSWORD_MARKER;
        var marked = List.of(
                "", HASH_MARKER,
                valid.substring(0, 59),
                valid + "\n",
                valid + HASH_MARKER.repeat(1000),
                "$2x$04$" + MARKED_TAIL,
                "$2A$04$" + MARKED_TAIL,
                "$3a$04$" + MARKED_TAIL,
                "x2a$04$" + MARKED_TAIL,
                "$2a$03$" + MARKED_TAIL,
                "$2a$32$" + MARKED_TAIL,
                "$2a$ 4$" + MARKED_TAIL,
                "$2a$04." + MARKED_TAIL,
                "$2a$04$" + MARKED_TAIL.replace('A', '+'),
                "$2a$04$" + MARKED_TAIL.replace('B', '_'),
                "$2a$04$" + MARKED_TAIL.substring(0, 21) + "/" + MARKED_TAIL.substring(22),
                "$2a$04$" + MARKED_TAIL.substring(0, 52) + "/",
                "{bcrypt}" + valid,
                "$pbkdf2-sha256$i=10000$" + HASH_MARKER + "AAAAAAAAAAAA$" + "A".repeat(43));
        var calls = new ArrayList<Body>();
        // Criterion 9: malformed stored hashes, with an accepted and a refused password.
        for (var encoded : marked) {
            calls.add(() -> bcrypt.matches(password, encoded));
            calls.add(() -> bcrypt.matches(password.repeat(20), encoded));
            calls.add(() -> bcrypt.needsRehash(encoded));
        }
        // Criteria 7 and 8: refused costs and passwords.
        calls.add(() -> BCryptPasswordHasher.of(3));
        calls.add(() -> BCryptPasswordHasher.of(Integer.MAX_VALUE));
        calls.add(() -> bcrypt.hash(""));
        calls.add(() -> bcrypt.hash(PASSWORD_MARKER.repeat(10)));
        calls.add(() -> bcrypt.hash(PASSWORD_MARKER + "\u0000" + PASSWORD_MARKER));
        calls.add(() -> bcrypt.hash(PASSWORD_MARKER + "\ud800"));
        calls.add(() -> bcrypt.hash("€".repeat(24) + PASSWORD_MARKER));
        calls.add(() -> bcrypt.hash(null));
        calls.add(() -> bcrypt.matches(null, valid));
        // Criterion 11: creating and using the delegating hasher; markers also between the braces.
        var markedId = new HashMap<String, PasswordHasher>();
        markedId.put("bad " + HASH_MARKER, bcrypt);
        calls.add(() -> PasswordHasher.delegating("bcrypt", markedId));
        calls.add(() -> PasswordHasher.delegating(HASH_MARKER, Map.of("bcrypt", bcrypt)));
        calls.add(() -> PasswordHasher.delegating("x", Map.of("x", bcrypt, "{" + HASH_MARKER + "}", bcrypt)));
        calls.add(() -> PasswordHasher.delegating("x", Map.of("x", bcrypt, HASH_MARKER.repeat(11), bcrypt)));
        var delegated = List.of(
                valid,
                "{" + HASH_MARKER + "}" + valid,
                "{" + HASH_MARKER,
                "{" + valid + "}",
                "{" + valid + "}" + valid,
                "{ " + HASH_MARKER + "}" + valid,
                "{" + HASH_MARKER.repeat(11) + "}" + valid,
                "{}" + valid + HASH_MARKER,
                "{bcrypt}" + HASH_MARKER,
                "{bcrypt}" + valid + HASH_MARKER,
                "{pbkdf2}" + valid,
                "{pbkdf2}$pbkdf2-sha256$i=0$" + HASH_MARKER + "AAAAAAAAAAAA$" + "A".repeat(43));
        for (var encoded : delegated) {
            calls.add(() -> hasher.matches(password, encoded));
            calls.add(() -> hasher.needsRehash(encoded));
        }
        calls.add(() -> hasher.hash(PASSWORD_MARKER.repeat(10)));
        // Criterion 12: the authenticator lets a malformed stored hash through.
        var users = new InMemoryUserStore();
        users.add(new User("ada", "{" + HASH_MARKER + "}" + valid, Set.of(), true));
        users.add(new User("bob", "{bcrypt}" + valid.substring(0, 59), Set.of(), true));
        var authenticator = Authenticator.of(users, hasher, users.upgrader(hasher));
        calls.add(() -> authenticator.authenticate("ada", password));
        calls.add(() -> authenticator.authenticate("bob", password));
        calls.add(() -> users.upgrader(hasher).upgrade("ada", "{bcrypt}" + valid));
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
