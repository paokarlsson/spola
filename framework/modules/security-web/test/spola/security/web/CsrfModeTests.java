package spola.security.web;

import static spola.security.web.CsrfSupport.cookie;
import static spola.security.web.CsrfSupport.header;
import static spola.security.web.CsrfSupport.raw;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.test.Suite;

/**
 * Criterion 6: the protection without sessions (only {@link HttpBasic}), and that
 * {@code login} and {@code logout} without {@code csrf(...)} set no {@code XSRF-TOKEN}.
 */
final class CsrfModeTests {

    private CsrfModeTests() {
    }

    static Suite tests() {
        return suite("with and without the protection",
                test("only HttpBasic, no sessions: a POST with the right credentials but no header gets 403 without"
                        + " the Authenticator, with the header the handler's response", () -> {
                    var counting = new Support.Counting();
                    var handled = new AtomicInteger();
                    var security = WebSecurity.builder()
                            .authentication(HttpBasic.of(counting.authenticator, "app"))
                            .csrf(Csrf.cookie())
                            .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                            .build();
                    var router = Support.router()
                            .filter(security.filter())
                            .post("/orders", request -> {
                                handled.incrementAndGet();
                                return Response.text(201, "created by "
                                        + (WebSecurity.requireIdentity(request).hasRole("ADMIN") ? "an admin" : "a user"));
                            })
                            .build();
                    try (var server = Support.serve(router)) {
                        var ada = CsrfCheckTests.ada();
                        var token = CsrfSupport.madeUp();
                        var withoutHeader = raw(server, "POST", "/orders", null, ada, cookie(token));
                        assertEquals(403, withoutHeader.status());
                        assertEquals(BasicTests.PROBLEM_403, withoutHeader.body());
                        assertEquals(List.of(), withoutHeader.all("WWW-Authenticate"));
                        assertEquals(0, counting.calls(), "the Authenticator never ran");
                        assertEquals(0, handled.get());
                        // without any cookie: 403 and the first cookie
                        var first = raw(server, "POST", "/orders", null, ada);
                        assertEquals(403, first.status());
                        var issued = CsrfSupport.issued(first);
                        assertEquals(0, counting.calls());

                        var withHeader = raw(server, "POST", "/orders", null, ada, cookie(issued), header(issued));
                        assertEquals(201, withHeader.status());
                        assertEquals("created by an admin", withHeader.body());
                        assertEquals(List.of(), withHeader.all("Set-Cookie"), "no session and no new token");
                        assertEquals(1, counting.calls());
                        assertEquals(1, handled.get());
                        // wrong credentials with the header: the mechanism's 401, after the check
                        var wrong = raw(server, "POST", "/orders", null,
                                CsrfCookieTests.credentials(Support.ADA, "zPwdAda-wrong"), cookie(issued), header(issued));
                        assertEquals(401, wrong.status());
                        assertEquals(2, counting.calls());
                        assertEquals(1, handled.get());
                        assertEquals("WebSecurity[1 mechanisms, 1 rules, csrf]", CsrfSupport.text(security));
                    }
                }),
                test("login and logout without csrf(...) set no XSRF-TOKEN", () -> {
                    var sessions = LoginExample.sessions(false, InstantSource.system());
                    var router = LoginExample.router(Support.router(), LoginExample.security(sessions),
                            LoginSupport.authenticator());
                    try (var server = Support.serve(router); var browser = new CsrfSupport.Browser(server)) {
                        var health = browser.get("/actuator/health");
                        assertEquals(List.of(), health.headers().allValues("Set-Cookie"));
                        var login = browser.postWithoutHeader("/api/auth/login",
                                LoginSupport.loginJson(LoginSupport.ANNA, LoginSupport.ANNA_PASSWORD));
                        assertEquals(200, login.statusCode());
                        assertEquals(1, CsrfSupport.sessionCookies(login).size());
                        assertEquals(List.of(), CsrfSupport.xsrfCookies(login));
                        var logout = browser.postWithoutHeader("/api/auth/logout", null);
                        assertEquals(200, logout.statusCode());
                        assertEquals(List.of(LoginFlowTests.REMOVAL), logout.headers().allValues("Set-Cookie"));
                        assertFalse(browser.xsrf().isPresent(), "the CookieManager never got an XSRF-TOKEN");
                        // also not with an XSRF-TOKEN cookie of the client's own
                        var token = CsrfSupport.madeUp();
                        var raw = raw(server, "POST", "/api/auth/login",
                                LoginSupport.loginJson(LoginSupport.ANNA, LoginSupport.ANNA_PASSWORD),
                                "Content-Type: application/json", cookie(token));
                        assertEquals(200, raw.status());
                        assertEquals(List.of(), CsrfSupport.xsrfCookies(raw));
                    }
                }));
    }
}
