package spola.security.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import spola.http.Response;
import spola.test.Suite;
import spola.web.ResponseCookie;
import spola.web.WebRequest;

/**
 * Criterion 7: what a wrong setup gives. {@code csrf(...)} twice and a cookie function that
 * turns {@code HttpOnly} on, returns {@code null}, gives {@code SameSite=None} without
 * {@code Secure}, returns another cookie or sets {@code Max-Age=0}: an
 * {@link IllegalArgumentException} from {@code build()}, one line per problem. Also the names,
 * {@code null} arguments, {@code toString()}, and {@code logout} after the response.
 */
final class CsrfSetupTests {

    private CsrfSetupTests() {
    }

    /** The message of the {@link IllegalArgumentException} that {@code build()} throws, recorded. */
    static String problems(WebSecurity.Builder builder) {
        var e = CsrfSupport.thrown(assertThrows(IllegalArgumentException.class, builder::build));
        return e.getMessage();
    }

    /** {@code build()} with only {@code csrf(Csrf.cookie().withCookie(function))}. */
    static String problems(UnaryOperator<ResponseCookie.Builder> function) {
        return problems(WebSecurity.builder().csrf(Csrf.cookie().withCookie(function)));
    }

    static Suite tests() {
        return suite("a wrong setup",
                test("the fixed names", () -> {
                    assertEquals("XSRF-TOKEN", Csrf.COOKIE_NAME);
                    assertEquals("X-XSRF-TOKEN", Csrf.HEADER_NAME);
                    assertEquals("Csrf[cookie XSRF-TOKEN, header X-XSRF-TOKEN]", CsrfSupport.text(Csrf.cookie()));
                }),
                test("csrf(...) twice: one line in build()'s IllegalArgumentException", () -> {
                    assertEquals("csrf(...) is called 2 times; call it once",
                            problems(WebSecurity.builder().csrf(Csrf.cookie()).csrf(Csrf.cookie())));
                    var sessions = CsrfExample.sessions(false, InstantSource.system());
                    var message = problems(WebSecurity.builder()
                            .sessions(sessions).sessions(sessions)
                            .csrf(Csrf.cookie()).csrf(Csrf.cookie().withCookie(c -> c.secure(true))).csrf(Csrf.cookie())
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .rule(RequestMatcher.exactly("/x"), AccessRule.permitAll()));
                    assertEquals(List.of("rule 2 comes after anyRequest() in rule 1 and can never match",
                            "sessions(...) is called 2 times; call it once, with the one Sessions of the router",
                            "csrf(...) is called 3 times; call it once"), List.of(message.split("\n")));
                    // once is fine, with and without sessions
                    assertEquals("WebSecurity[0 mechanisms, 4 rules, sessions, csrf]",
                            CsrfSupport.text(CsrfExample.security(sessions)));
                    assertEquals("WebSecurity[0 mechanisms, 0 rules, csrf]",
                            CsrfSupport.text(WebSecurity.builder().csrf(Csrf.cookie()).build()));
                }),
                test("a cookie function that turns HttpOnly on: IllegalArgumentException from build()", () -> {
                    assertEquals("csrf(...): the cookie function turns HttpOnly on, so the application's JavaScript"
                            + " (Angular) cannot read XSRF-TOKEN; leave httpOnly off", problems(c -> c.httpOnly(true)));
                }),
                test("a cookie function that returns null: IllegalArgumentException from build()", () -> {
                    assertEquals("csrf(...): the cookie function returned null; return the builder it gets",
                            problems(c -> null));
                }),
                test("a cookie function that gives SameSite=None without Secure: IllegalArgumentException from build()",
                        () -> {
                    assertEquals("csrf(...): the cookie function gives an invalid cookie: cookie \"XSRF-TOKEN\":"
                            + " SameSite=None needs Secure", problems(c -> c.sameSite(ResponseCookie.SameSite.NONE)));
                    // with Secure it is fine
                    WebSecurity.builder()
                            .csrf(Csrf.cookie().withCookie(c -> c.sameSite(ResponseCookie.SameSite.NONE).secure(true)))
                            .build();
                    assertTrue(problems(c -> c.path("no slash")).startsWith(
                            "csrf(...): the cookie function gives an invalid cookie: cookie \"XSRF-TOKEN\": the path"));
                }),
                test("a cookie function that returns another cookie or sets Max-Age=0: IllegalArgumentException", () -> {
                    assertEquals("csrf(...): the cookie function must return the builder it gets, with attributes"
                            + " changed (it returned one for the cookie \"OTHER\")",
                            problems(c -> ResponseCookie.of("OTHER", "x").httpOnly(false)));
                    assertEquals("csrf(...): the cookie function must return the builder it gets, with attributes"
                            + " changed (it returned one for the cookie \"XSRF-TOKEN\")",
                            problems(c -> ResponseCookie.of(Csrf.COOKIE_NAME, "fixed").httpOnly(false)));
                    assertEquals("csrf(...): the cookie function sets Max-Age=0, which removes the cookie at once; leave"
                            + " maxAge out for a session cookie", problems(c -> c.maxAge(Duration.ZERO)));
                    // every problem at once
                    assertEquals(2, problems(c -> ResponseCookie.of("OTHER", "x")).split("\n").length);
                }),
                test("an exception from the cookie function passes unchanged; null arguments fail at once", () -> {
                    var failure = new IllegalStateException("the function failed");
                    var thrown = assertThrows(IllegalStateException.class, () -> WebSecurity.builder()
                            .csrf(Csrf.cookie().withCookie(c -> {
                                throw failure;
                            })).build());
                    assertTrue(thrown == failure);
                    assertThrows(NullPointerException.class, () -> WebSecurity.builder().csrf(null));
                    assertThrows(NullPointerException.class, () -> Csrf.cookie().withCookie(null));
                }),
                test("the function runs once, in build(), and its attributes are used for every token", () -> {
                    var calls = new AtomicInteger();
                    var security = WebSecurity.builder()
                            .csrf(Csrf.cookie().withCookie(c -> {
                                calls.incrementAndGet();
                                return c.secure(true).path("/app").domain("example.com").maxAge(Duration.ofHours(1))
                                        .sameSite(ResponseCookie.SameSite.STRICT);
                            }))
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    assertEquals(1, calls.get());
                    var router = Support.router().filter(security.filter()).get("/x", r -> Response.text(200, "x"))
                            .build();
                    try (var server = Support.serve(router)) {
                        for (int i = 0; i < 3; i++) {
                            var cookies = CsrfSupport.xsrfCookies(CsrfSupport.raw(server, "GET", "/x", null));
                            assertEquals(1, cookies.size());
                            var token = CsrfSupport.valueOf(cookies.getFirst());
                            assertEquals("XSRF-TOKEN=" + token + "; Path=/app; Domain=example.com; Max-Age=3600; Secure;"
                                    + " SameSite=Strict", cookies.getFirst());
                        }
                    }
                    assertEquals(1, calls.get());
                }),
                test("logout after the response was built: IllegalStateException", () -> {
                    var sessions = CsrfExample.sessions(false, InstantSource.system());
                    var security = WebSecurity.builder()
                            .sessions(sessions)
                            .csrf(Csrf.cookie())
                            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                            .build();
                    var kept = new AtomicReference<WebRequest>();
                    var router = Support.router()
                            .filter(security.filter())
                            .get("/keep", request -> {
                                kept.set(request);
                                return Response.text(200, "kept");
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        assertEquals(200, CsrfSupport.raw(server, "GET", "/keep", null).status());
                    }
                    var e = CsrfSupport.thrown(assertThrows(IllegalStateException.class,
                            () -> WebSecurity.logout(kept.get())));
                    assertEquals("WebSecurity.logout was called after the response of the request was built; call it"
                            + " while the request is handled", e.getMessage());
                }));
    }
}
