package spola.security.web;

import static spola.security.web.CsrfSupport.cookie;
import static spola.security.web.CsrfSupport.header;
import static spola.security.web.CsrfSupport.raw;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.HttpServer;
import spola.http.Response;
import spola.test.Suite;
import spola.web.Router;

/**
 * Criterion 5: a table of forged and malformed requests for {@code POST}, {@code PUT},
 * {@code PATCH} and {@code DELETE}, each of which must get 403 without the handler or the
 * {@code Authenticator} running (every request carries correct Basic credentials, so a request
 * that got through would reach both). Also that {@code GET}, {@code HEAD}, {@code OPTIONS} and
 * {@code TRACE} pass without a header and get what the router and the rules give, and that a
 * header matching the second of two {@code XSRF-TOKEN} values is accepted.
 */
final class CsrfCheckTests {

    private CsrfCheckTests() {
    }

    /** The methods every case of the table is sent with. */
    static final List<String> CHANGING = List.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * One forged or malformed request.
     *
     * @param name    what is wrong with it
     * @param methods the methods it is sent with
     * @param target  the request target
     * @param body    the body, or {@code null}
     * @param headers the header lines besides {@code Authorization}
     */
    record Case(String name, List<String> methods, String target, String body, List<String> headers) {
        Case(String name, String... headers) {
            this(name, CHANGING, "/x", null, List.of(headers));
        }

        static Case method(String method, String... headers) {
            return new Case(method + " without a header", List.of(method), "/x", null, List.of(headers));
        }
    }

    /** The token with the character at {@code index} replaced by {@code c}. */
    static String replace(String token, int index, char c) {
        return token.substring(0, index) + c + token.substring(index + 1);
    }

    /** The token with the case of every letter swapped. */
    static String swapCase(String token) {
        var out = new StringBuilder();
        for (char c : token.toCharArray()) {
            out.append(Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c));
        }
        return out.toString();
    }

    /** The token with the case of its first letter swapped. */
    static String swapFirstLetter(String token) {
        for (int i = 0; i < token.length(); i++) {
            if (Character.isLetter(token.charAt(i))) {
                return replace(token, i, swapCase(token.substring(i, i + 1)).charAt(0));
            }
        }
        throw new AssertionError("a token without letters");
    }

    static List<Case> cases() {
        var t = CsrfSupport.madeUp();
        var other = CsrfSupport.madeUp();
        char last = t.charAt(42) == 'A' ? 'B' : 'A';
        var dotted = replace(t, 5, '.');
        return List.of(
                new Case("no header", cookie(t)),
                new Case("no cookie", header(t)),
                new Case("neither cookie nor header"),
                new Case("an empty header", cookie(t), "X-XSRF-TOKEN:"),
                new Case("header and cookie differ in the last character", cookie(t), header(replace(t, 42, last))),
                new Case("header and cookie both well-formed but different", cookie(t), header(other)),
                new Case("42 characters in both", cookie(t.substring(1)), header(t.substring(1))),
                new Case("44 characters in both", cookie(t + "A"), header(t + "A")),
                new Case("standard Base64 '+' in both", cookie(replace(t, 7, '+')), header(replace(t, 7, '+'))),
                new Case("standard Base64 '/' in both", cookie(replace(t, 7, '/')), header(replace(t, 7, '/'))),
                new Case("'=' in place of a character in both", cookie(replace(t, 42, '=')), header(replace(t, 42, '='))),
                new Case("padded with '=' to 44 characters in both", cookie(t + "="), header(t + "=")),
                new Case("the header in quotes", cookie(t), header("\"" + t + "\"")),
                new Case("cookie and header in quotes", cookie("\"" + t + "\""), header("\"" + t + "\"")),
                new Case("the cookie in quotes", cookie("\"" + t + "\""), header(t)),
                new Case("the header with the case of every letter swapped", cookie(t), header(swapCase(t))),
                new Case("the header with the case of one letter swapped", cookie(t), header(swapFirstLetter(t))),
                new Case("two X-XSRF-TOKEN fields with the token", cookie(t), header(t), header(t)),
                new Case("two X-XSRF-TOKEN fields, one with the token", cookie(t), header(other), header(t)),
                new Case("two tokens in one field", cookie(t), header(t + ", " + t)),
                new Case("a header that matches a malformed cookie", cookie(dotted), header(dotted)),
                new Case("a header that matches the session cookie", "Cookie: SESSION=" + t, header(t)),
                new Case("the cookie name in lower case", "Cookie: xsrf-token=" + t, header(t)),
                new Case("the token under another header (X-CSRF-TOKEN)", cookie(t), "X-CSRF-TOKEN: " + t),
                new Case("a trailing ';' in the header", cookie(t), header(t + ";")),
                new Case("a non-ASCII character in the header", cookie(t), header(replace(t, 20, 'é'))),
                new Case("_csrf as a form field instead of the header", CHANGING, "/x", "_csrf=" + t,
                        List.of(cookie(t), "Content-Type: application/x-www-form-urlencoded")),
                Case.method("PURGE", cookie(t)),
                Case.method("post", cookie(t)),
                Case.method("get", cookie(t)));
    }

    /** {@code /x} for every method of the table, counting the handler; and {@code GET /x}. */
    static Router router(WebSecurity security, AtomicInteger handled) {
        var routes = Support.router().filter(security.filter());
        for (var method : List.of("POST", "PUT", "PATCH", "DELETE", "PURGE", "post", "get")) {
            routes.route(method, "/x", request -> {
                handled.incrementAndGet();
                return Response.text(200, "changed by " + request.method());
            });
        }
        return routes
                .get("/x", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "x");
                })
                .get("/private", request -> {
                    handled.incrementAndGet();
                    return Response.text(200, "private");
                })
                .build();
    }

    /** Basic and CSRF protection; {@code /private} needs a login, the rest is open. */
    static WebSecurity security(Support.Counting counting) {
        return WebSecurity.builder()
                .authentication(HttpBasic.of(counting.authenticator, "app"))
                .csrf(Csrf.cookie())
                .rule(RequestMatcher.exactly("/private"), AccessRule.authenticated())
                .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                .build();
    }

    /** Ada's correct credentials, recorded for the leak tests. */
    static String ada() {
        return CsrfCookieTests.credentials(Support.ADA, Support.ADA_PASSWORD);
    }

    static Support.Reply send(HttpServer server, String method, String target, String body, List<String> headers)
            throws Exception {
        var all = new ArrayList<>(headers);
        all.add(ada());
        return raw(server, method, target, body, all.toArray(String[]::new));
    }

    static Suite tests() {
        var cases = cases();
        return suite("the check",
                test("the table has at least 20 cases", () -> assertTrue(cases.size() >= 20, cases.size() + " cases")),
                parameterized("403 without the handler or the Authenticator", cases, Case::name, c -> {
                    var counting = new Support.Counting();
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(security(counting), handled))) {
                        for (var method : c.methods()) {
                            var reply = send(server, method, c.target(), c.body(), c.headers());
                            assertEquals(403, reply.status(), method);
                            assertEquals(BasicTests.PROBLEM_403, reply.body(), method);
                            assertEquals("application/problem+json", reply.header("Content-Type"));
                            assertEquals(List.of("no-store"), reply.all("Cache-Control"));
                            assertEquals(List.of(), reply.all("WWW-Authenticate"));
                        }
                        assertEquals(0, handled.get(), "the handler never ran");
                        assertEquals(0, counting.finds.get(), "the UserStore was never asked");
                        assertEquals(0, counting.calls(), "the Authenticator never ran");
                    }
                }),
                test("with a matching header every method of the table reaches the Authenticator and the handler",
                        () -> {
                    var counting = new Support.Counting();
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(security(counting), handled))) {
                        var token = CsrfSupport.madeUp();
                        var methods = List.of("POST", "PUT", "PATCH", "DELETE", "PURGE", "post", "get");
                        for (var method : methods) {
                            var reply = send(server, method, "/x", null, List.of(cookie(token), header(token)));
                            assertEquals(200, reply.status(), method);
                            assertEquals("changed by " + method, reply.body());
                        }
                        // the header name is not case-sensitive (RFC 9110), the token is
                        var lower = send(server, "POST", "/x", null, List.of(cookie(token), "x-xsrf-token: " + token));
                        assertEquals(200, lower.status());
                        assertEquals(methods.size() + 1, handled.get());
                        assertEquals(methods.size() + 1, counting.calls());
                    }
                }),
                test("GET, HEAD, OPTIONS and TRACE pass without a header and get what the router and the rules give",
                        () -> {
                    var counting = new Support.Counting();
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(security(counting), handled))) {
                        var get = raw(server, "GET", "/x", null);
                        assertEquals(200, get.status());
                        assertEquals("x", get.body());
                        var head = raw(server, "HEAD", "/x", null);
                        assertEquals(200, head.status());
                        assertEquals("", head.body());
                        var options = raw(server, "OPTIONS", "/x", null);
                        assertEquals(204, options.status(), "the router's automatic OPTIONS");
                        assertTrue(options.header("Allow").contains("POST"), options.header("Allow"));
                        var trace = raw(server, "TRACE", "/x", null);
                        assertEquals(405, trace.status(), "no TRACE route: the router's 405");
                        assertEquals(2, handled.get(), "GET and HEAD reached the handler");
                        // the rules decide: anonymous 401, with credentials the handler
                        for (var method : List.of("GET", "HEAD", "OPTIONS", "TRACE")) {
                            var anonymous = raw(server, method, "/private", null);
                            assertEquals(401, anonymous.status(), method);
                        }
                        var tokenless = raw(server, "GET", "/private", null, ada());
                        assertEquals(200, tokenless.status());
                        assertEquals("private", tokenless.body());
                        assertEquals(1, counting.calls());
                        // every one of them was a first response
                        assertTrue(CsrfSupport.xsrfCookies(tokenless).size() == 1);
                    }
                }),
                test("a header that matches the first or the second of two XSRF-TOKEN values is accepted", () -> {
                    var counting = new Support.Counting();
                    var handled = new AtomicInteger();
                    try (var server = Support.serve(router(security(counting), handled))) {
                        var first = CsrfSupport.madeUp();
                        var second = CsrfSupport.madeUp();
                        var both = "Cookie: XSRF-TOKEN=" + first + "; XSRF-TOKEN=" + second;
                        var matchesSecond = send(server, "POST", "/x", null, List.of(both, header(second)));
                        assertEquals(200, matchesSecond.status());
                        assertEquals(List.of(), matchesSecond.all("Set-Cookie"));
                        var matchesFirst = send(server, "PUT", "/x", null, List.of(both, header(first)));
                        assertEquals(200, matchesFirst.status());
                        // two Cookie fields, and a malformed value before the well-formed one
                        var twoFields = send(server, "DELETE", "/x", null, List.of(cookie("\"" + first + "\""),
                                cookie(second), header(second)));
                        assertEquals(200, twoFields.status());
                        var neither = send(server, "PATCH", "/x", null, List.of(both, header(CsrfSupport.madeUp())));
                        assertEquals(403, neither.status());
                        assertEquals(3, handled.get());
                        assertEquals(3, counting.calls());
                    }
                }));
    }
}
