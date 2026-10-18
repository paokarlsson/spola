package spola.security.web;

import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.security.Authenticator;
import spola.security.BCryptPasswordHasher;
import spola.security.InMemoryUserStore;
import spola.security.PasswordHasher;
import spola.security.User;
import spola.test.Suite;

/**
 * 15c, criterion 4 (L2), through HTTP: a known user whose stored hash cannot be parsed gets the
 * same 401 as an unknown user, not a 500 that would tell that the account exists.
 *
 * <p>The authenticator logs one {@code WARNING} line per such request on the
 * {@code System.Logger} {@code spola.security}; this module cannot reach the package-private
 * factory that takes a logger, so the lines go to the build's output. The line itself is checked
 * in {@code spola.security}'s {@code MalformedLoginTests}.
 */
final class StoredHashTests {

    private StoredHashTests() {
    }

    static Suite tests() {
        return suite("a malformed stored hash through HttpBasic",
                test("a known user with a malformed or unknown stored hash gets the 401 of an unknown user, not 500", () -> {
                    var bcrypt = BCryptPasswordHasher.of(4);
                    var hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", bcrypt, "pbkdf2", Support.HASHER));
                    var truncated = "{bcrypt}" + bcrypt.hash("zPwd-legacy").substring(0, 59);
                    var stored = List.of("{noop}x", "{sha256}" + "9e3fbb0bf41e6d07".repeat(5), truncated, "");
                    var store = new InMemoryUserStore();
                    for (int i = 0; i < stored.size(); i++) {
                        store.add(new User("zUsrLegacy" + i, stored.get(i), Set.of("USER"), true));
                    }
                    store.add(new User("zUsrOk", hasher.hash("zPwd-ok"), Set.of("USER"), true));
                    var security = WebSecurity.builder()
                            .authentication(HttpBasic.of(Authenticator.of(store, hasher), "app"))
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .build();
                    var handled = new AtomicInteger();
                    var router = Support.router()
                            .filter(security.filter())
                            .get("/closed", request -> {
                                handled.incrementAndGet();
                                return Response.text(200, "handled");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var unknown = Support.send(server.port(), "GET", "/closed", basic("zUsrNobody", "zPwd-x"));
                        assertEquals(401, unknown.status(), String.valueOf(unknown));
                        for (int i = 0; i < stored.size(); i++) {
                            var reply = Support.send(server.port(), "GET", "/closed", basic("zUsrLegacy" + i, "zPwd-x"));
                            assertEquals(401, reply.status(), "user " + i + ": " + reply);
                            assertEquals(unknown.withoutDate(), reply.withoutDate(), "user " + i);
                        }
                        // {noop}x is not a password of "x" either.
                        var noop = Support.send(server.port(), "GET", "/closed", basic("zUsrLegacy0", "x"));
                        assertEquals(unknown.withoutDate(), noop.withoutDate());
                        assertEquals(0, handled.get());
                        assertEquals(200, Support.send(server.port(), "GET", "/closed", basic("zUsrOk", "zPwd-ok")).status());
                        assertEquals(1, handled.get());
                    }
                }));
    }
}
