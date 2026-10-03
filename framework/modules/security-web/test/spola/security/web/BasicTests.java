package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_IDENTITY;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.BOB;
import static spola.security.web.Support.BOB_IDENTITY;
import static spola.security.web.Support.BOB_PASSWORD;
import static spola.security.web.Support.EVE;
import static spola.security.web.Support.EVE_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import spola.http.Response;
import spola.security.Authenticator;
import spola.test.Suite;
import spola.web.Router;
import spola.web.WebHandler;

/** Criterion 3: Basic through a real server, 401 and 403, and requests no rule matches. */
final class BasicTests {

    private BasicTests() {
    }

    static final String CHALLENGE = "Basic realm=\"app\", charset=\"UTF-8\"";
    static final String PROBLEM_401 = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}";
    static final String PROBLEM_403 = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403}";

    /**
     * A small application: {@code /public} for everyone, {@code /admin} for ADMIN, {@code /user}
     * for any authenticated user, and {@code /other} which no rule matches. Its handler records
     * what it saw and answers {@code handled} (never a name).
     */
    static final class App {
        final List<Support.Seen> seen = new ArrayList<>();
        final WebSecurity security;
        final Router router;

        App(Authenticator authenticator) {
            security = WebSecurity.builder()
                    .authentication(HttpBasic.of(authenticator, "app"))
                    .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
                    .rule(RequestMatcher.under("/admin"), AccessRule.hasRole("ADMIN"))
                    .rule(RequestMatcher.under("/user"), AccessRule.authenticated())
                    .build();
            WebHandler handler = request -> {
                var identity = WebSecurity.identity(request);
                var required = identity.isPresent() ? Optional.of(WebSecurity.requireIdentity(request))
                        : Optional.<spola.security.Identity>empty();
                synchronized (seen) {
                    seen.add(new Support.Seen(identity, required));
                }
                return Response.text(200, "handled");
            };
            router = Support.router()
                    .filter(security.filter())
                    .get("/public/hello", handler)
                    .get("/admin/panel", handler)
                    .get("/user/me", handler)
                    .get("/other", handler)
                    .build();
        }

        int handled() {
            synchronized (seen) {
                return seen.size();
            }
        }

        Support.Seen last() {
            synchronized (seen) {
                return seen.getLast();
            }
        }
    }

    /** Asserts the exact 401 of {@code realm="app"}. */
    static void assert401(Support.Reply reply) {
        assertEquals(401, reply.status(), String.valueOf(reply));
        assertEquals("application/problem+json", reply.header("Content-Type"));
        assertEquals(List.of(CHALLENGE), reply.all("WWW-Authenticate"));
        assertEquals("no-store", reply.header("Cache-Control"));
        assertEquals(PROBLEM_401, reply.body());
    }

    /** Asserts the exact 403. */
    static void assert403(Support.Reply reply) {
        assertEquals(403, reply.status(), String.valueOf(reply));
        assertEquals("application/problem+json", reply.header("Content-Type"));
        assertNull(reply.header("WWW-Authenticate"));
        assertEquals("no-store", reply.header("Cache-Control"));
        assertEquals(PROBLEM_403, reply.body());
    }

    static Suite tests() {
        return suite("HTTP Basic through a server",
                test("right credentials reach the handler, which sees exactly the identity", () -> {
                    var app = new App(Support.authenticator());
                    try (var server = Support.serve(app.router)) {
                        var reply = Support.send(server.port(), "GET", "/user/me", basic(ADA, ADA_PASSWORD));
                        assertEquals(200, reply.status(), String.valueOf(reply));
                        assertEquals("handled", reply.body());
                        assertEquals(new Support.Seen(Optional.of(ADA_IDENTITY), Optional.of(ADA_IDENTITY)), app.last());
                        reply = Support.send(server.port(), "GET", "/user/me", basic(BOB, BOB_PASSWORD));
                        assertEquals(200, reply.status(), String.valueOf(reply));
                        assertEquals(new Support.Seen(Optional.of(BOB_IDENTITY), Optional.of(BOB_IDENTITY)), app.last());
                    }
                }),
                test("the same through java.net.http.HttpClient", () -> {
                    var app = new App(Support.authenticator());
                    try (var server = Support.serve(app.router);
                            var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                        var uri = URI.create("http://127.0.0.1:" + server.port() + "/admin/panel");
                        Support.recordSent(basic(ADA, ADA_PASSWORD));
                        var ok = client.send(HttpRequest.newBuilder(uri).header("Authorization", basic(ADA, ADA_PASSWORD))
                                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, ok.statusCode());
                        assertEquals("handled", ok.body());
                        assertEquals(Optional.of(ADA_IDENTITY), app.last().identity());
                        var anonymous = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(401, anonymous.statusCode());
                        assertEquals(List.of(CHALLENGE), anonymous.headers().allValues("WWW-Authenticate"));
                        assertEquals(PROBLEM_401, anonymous.body());
                    }
                }),
                test("an anonymous request to a permitAll path reaches the handler with an empty identity", () -> {
                    var app = new App(Support.authenticator());
                    try (var server = Support.serve(app.router)) {
                        var reply = Support.send(server.port(), "GET", "/public/hello");
                        assertEquals(200, reply.status(), String.valueOf(reply));
                        assertEquals(new Support.Seen(Optional.empty(), Optional.empty()), app.last());
                    }
                }),
                test("an anonymous request to a protected path: 401, problem+json, the challenge, no-store; no handler",
                        () -> {
                            var app = new App(Support.authenticator());
                            try (var server = Support.serve(app.router)) {
                                assert401(Support.send(server.port(), "GET", "/user/me"));
                                assert401(Support.send(server.port(), "GET", "/admin/panel"));
                                assertEquals(0, app.handled());
                            }
                        }),
                test("wrong password, unknown user and disabled account: byte for byte the same 401, also on permitAll",
                        () -> {
                            var app = new App(Support.authenticator());
                            try (var server = Support.serve(app.router)) {
                                var replies = new ArrayList<String>();
                                for (var path : List.of("/user/me", "/public/hello", "/admin/panel")) {
                                    for (var authorization : List.of(basic(ADA, "zPwd-wrong"), basic("zUsrNobody", ADA_PASSWORD),
                                            basic(EVE, EVE_PASSWORD), basic(EVE, "zPwd-wrong"))) {
                                        var reply = Support.send(server.port(), "GET", path, authorization);
                                        assert401(reply);
                                        replies.add(reply.withoutDate());
                                    }
                                }
                                var anonymous = Support.send(server.port(), "GET", "/user/me").withoutDate();
                                for (var reply : replies) {
                                    assertEquals(anonymous, reply);
                                }
                                assertEquals(0, app.handled());
                            }
                        }),
                test("authenticated without the role: 403 without WWW-Authenticate; with the role: the handler", () -> {
                    var app = new App(Support.authenticator());
                    try (var server = Support.serve(app.router)) {
                        assert403(Support.send(server.port(), "GET", "/admin/panel", basic(BOB, BOB_PASSWORD)));
                        assertEquals(0, app.handled());
                        var reply = Support.send(server.port(), "GET", "/admin/panel", basic(ADA, ADA_PASSWORD));
                        assertEquals(200, reply.status());
                        assertEquals(Optional.of(ADA_IDENTITY), app.last().identity());
                    }
                }),
                test("a request no rule matches is denied: 401 anonymous, 403 authenticated", () -> {
                    var app = new App(Support.authenticator());
                    try (var server = Support.serve(app.router)) {
                        assert401(Support.send(server.port(), "GET", "/other"));
                        assert403(Support.send(server.port(), "GET", "/other", basic(ADA, ADA_PASSWORD)));
                        assertEquals(0, app.handled());
                    }
                }),
                test("the realm: 1 to 100 printable ASCII characters without \" and \\", () -> {
                    var authenticator = Support.authenticator();
                    for (var realm : List.of("", "x".repeat(101), "a\"b", "a\\b", "a\nb", "a\u0000", "a\u007f", "räksmörgås",
                            "a\tb")) {
                        assertThrows(IllegalArgumentException.class, ()  -> HttpBasic.of(authenticator, realm));
                    }
                    assertThrows(NullPointerException.class, () -> HttpBasic.of(authenticator, null));
                    assertThrows(NullPointerException.class, () -> HttpBasic.of(null, "app"));
                    assertEquals("Basic realm=\"My App (v2)\", charset=\"UTF-8\"",
                            HttpBasic.of(authenticator, "My App (v2)").challenge());
                    assertEquals("x".repeat(100), HttpBasic.of(authenticator, "x".repeat(100)).realm());
                    var message = assertThrows(IllegalArgumentException.class,
                            () -> HttpBasic.of(authenticator, "a\r\nb")).getMessage();
                    assertTrue(!message.contains("\r") && !message.contains("\n"), message);
                }),
                test("without HttpBasic there is no WWW-Authenticate; with two there are two, in order", () -> {
                    var none = WebSecurity.builder().rule(RequestMatcher.anyRequest(), AccessRule.authenticated()).build();
                    var two = WebSecurity.builder()
                            .authentication(HttpBasic.of(Support.authenticator(), "first"))
                            .authentication(request -> new AuthOutcome.None())
                            .authentication(HttpBasic.of(Support.authenticator(), "second"))
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated()).build();
                    try (var a = Support.serve(Support.router().filter(none.filter()).build());
                            var b = Support.serve(Support.router().filter(two.filter()).build())) {
                        var reply = Support.send(a.port(), "GET", "/x");
                        assertEquals(401, reply.status());
                        assertEquals(List.of(), reply.all("WWW-Authenticate"));
                        assertEquals("no-store", reply.header("Cache-Control"));
                        reply = Support.send(b.port(), "GET", "/x");
                        assertEquals(List.of("Basic realm=\"first\", charset=\"UTF-8\"",
                                "Basic realm=\"second\", charset=\"UTF-8\""), reply.all("WWW-Authenticate"));
                    }
                }));
    }
}
