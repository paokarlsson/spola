package spola.security.web;

import static spola.security.web.CsrfSupport.header;
import static spola.security.web.CsrfSupport.raw;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.HttpServer;
import spola.test.Suite;
import spola.web.ForwardedHeaders;
import spola.web.Sessions;
import spola.web.TrustedProxies;

/**
 * 13b, criterion 8: 200 virtual threads run the flow of 11c behind {@code ForwardedHeaders} at
 * the same time, each request with {@code X-Forwarded-Proto: https} or {@code http} (alternating
 * within each thread and between threads). Every response's {@code Set-Cookie} has
 * {@code Secure} exactly when its own request was {@code https}, and is otherwise exactly the
 * line of 11a and 11c: the decision is made per request, without shared state.
 */
final class SchemeConcurrencyTests {

    private SchemeConcurrencyTests() {
    }

    static final int THREADS = 200;

    static String proto(boolean https) {
        return https ? "X-Forwarded-Proto: https" : "X-Forwarded-Proto: http";
    }

    static String xsrf(boolean https) {
        return https ? SchemeFlowTests.SECURE_XSRF : CsrfSupport.XSRF_COOKIE;
    }

    static String session(boolean https) {
        return https ? SchemeFlowTests.SECURE_SESSION : LoginFlowTests.SESSION_COOKIE;
    }

    static String removal(boolean https) {
        return https ? SchemeFlowTests.SECURE_REMOVAL : LoginFlowTests.REMOVAL;
    }

    /**
     * The flow for client {@code thread}: request {@code k} is {@code https} when
     * {@code thread + k} is even. Counts the {@code Set-Cookie} lines with and without
     * {@code Secure}.
     */
    static void flow(HttpServer server, int thread, AtomicInteger secure, AtomicInteger plain) throws Exception {
        var name = LoginSupport.NAMES.get(thread % LoginSupport.NAMES.size());
        int k = thread;

        boolean https = k++ % 2 == 0;
        var health = raw(server, "GET", "/actuator/health", null, proto(https));
        assertEquals(200, health.status());
        assertEquals(List.of(xsrf(https)), SchemeCookieTests.lines(health));
        count(health, https, secure, plain);
        var first = CsrfSupport.valueOf(CsrfSupport.xsrfCookies(health).getFirst());

        https = k++ % 2 == 0;
        var login = raw(server, "POST", "/api/auth/login", LoginSupport.loginJson(name, LoginSupport.PASSWORDS.get(name)),
                proto(https), "Content-Type: application/json", CsrfSupport.cookie(first), header(first));
        assertEquals(200, login.status(), login.body());
        assertEquals(List.of(session(https), xsrf(https)), SchemeCookieTests.lines(login));
        count(login, https, secure, plain);
        var id = CsrfSupport.valueOf(CsrfSupport.sessionCookies(login).getFirst());
        var second = CsrfSupport.valueOf(CsrfSupport.xsrfCookies(login).getFirst());

        https = k++ % 2 == 0;
        var me = raw(server, "GET", "/api/auth/me", null, proto(https), "Cookie: SESSION=" + id + "; XSRF-TOKEN=" + second);
        assertEquals("{\"username\":\"" + name + "\"}", me.body());
        assertEquals(List.of(), me.all("Set-Cookie"));

        https = k++ % 2 == 0;
        var logout = raw(server, "POST", "/api/auth/logout", null, proto(https),
                "Cookie: SESSION=" + id + "; XSRF-TOKEN=" + second, header(second));
        assertEquals(200, logout.status(), logout.body());
        assertEquals(List.of(removal(https), xsrf(https)), SchemeCookieTests.lines(logout));
        count(logout, https, secure, plain);

        https = k % 2 == 0;
        var after = raw(server, "GET", "/api/auth/me", null, proto(https), "Cookie: SESSION=" + id);
        assertEquals(401, after.status());
        assertEquals(List.of(xsrf(https)), SchemeCookieTests.lines(after), "no XSRF-TOKEN cookie sent: a new one");
        count(after, https, secure, plain);
    }

    /** Adds the reply's {@code Set-Cookie} lines to {@code secure} or {@code plain}, after checking they all agree. */
    static void count(Support.Reply reply, boolean https, AtomicInteger secure, AtomicInteger plain) {
        for (var line : reply.all("Set-Cookie")) {
            assertEquals(https, line.contains("; Secure;"), line.replaceAll("[A-Za-z0-9_-]{43}", "<value>"));
            (https ? secure : plain).incrementAndGet();
        }
    }

    static Suite tests() {
        return suite("concurrency with mixed schemes (13b)",
                test("200 virtual threads mix X-Forwarded-Proto: https and http: Secure exactly on the responses to"
                        + " https, nothing else differs", () -> {
                    Sessions sessions = CsrfExample.sessions(false, InstantSource.system());
                    var router = CsrfExample.router(
                            Support.router().filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks())),
                            CsrfExample.security(sessions), LoginSupport.authenticator());
                    var secure = new AtomicInteger();
                    var plain = new AtomicInteger();
                    var start = new CountDownLatch(1);
                    try (var server = Support.serve(router);
                            var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var futures = new ArrayList<Future<String>>();
                        for (int t = 0; t < THREADS; t++) {
                            int thread = t;
                            futures.add(executor.submit(() -> {
                                start.await();
                                flow(server, thread, secure, plain);
                                return "ok";
                            }));
                        }
                        start.countDown();
                        for (var future : futures) {
                            assertEquals("ok", future.get());
                        }
                    }
                    assertEquals(0, sessions.size(), "every session was logged out");
                    // 6 lines per thread (health, login 2, logout 2, after), alternating between the schemes
                    assertEquals(6 * THREADS, secure.get() + plain.get());
                    assertTrue(secure.get() == 3 * THREADS && plain.get() == 3 * THREADS,
                            secure.get() + " lines with Secure, " + plain.get() + " without");
                }));
    }
}
