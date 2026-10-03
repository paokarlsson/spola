package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.BOB;
import static spola.security.web.Support.BOB_PASSWORD;
import static spola.security.web.Support.COLON;
import static spola.security.web.Support.COLON_PASSWORD;
import static spola.security.web.Support.EVE;
import static spola.security.web.Support.EVE_PASSWORD;
import static spola.security.web.Support.UNICODE;
import static spola.security.web.Support.UNICODE_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 10: 200 virtual threads at once, mixed credentials, users and roles. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    /** A short code per user, so that the handler can answer who it saw without a name. */
    static final Map<String, String> CODES = Map.of(ADA, "A", BOB, "B", COLON, "C", UNICODE, "U");

    /**
     * One kind of request and what it must get.
     *
     * @param path          the path
     * @param authorization the field, or {@code null} for none
     * @param status        the expected status
     * @param body          the expected body (the handler's {@code seen:<code>} or a problem)
     */
    record Kind(String path, String authorization, int status, String body) {
    }

    static List<Kind> kinds() {
        return List.of(
                new Kind("/admin/x", basic(ADA, ADA_PASSWORD), 200, "seen:A"),
                new Kind("/admin/x", basic(BOB, BOB_PASSWORD), 403, BasicTests.PROBLEM_403),
                new Kind("/admin/x", null, 401, BasicTests.PROBLEM_401),
                new Kind("/user/x", basic(BOB, BOB_PASSWORD), 200, "seen:B"),
                new Kind("/user/x", basic(ADA, ADA_PASSWORD), 200, "seen:A"),
                new Kind("/user/x", basic(COLON, COLON_PASSWORD), 200, "seen:C"),
                new Kind("/user/x", basic(UNICODE, UNICODE_PASSWORD), 200, "seen:U"),
                new Kind("/user/x", basic(ADA, BOB_PASSWORD), 401, BasicTests.PROBLEM_401),
                new Kind("/user/x", basic(EVE, EVE_PASSWORD), 401, BasicTests.PROBLEM_401),
                new Kind("/public/x", null, 200, "seen:-"),
                new Kind("/public/x", basic(BOB, BOB_PASSWORD), 200, "seen:B"),
                new Kind("/public/x", basic("zUsrNobody", "zPwd-x"), 401, BasicTests.PROBLEM_401),
                new Kind("/public/x", "Basic %%%%", 401, BasicTests.PROBLEM_401));
    }

    static Suite tests() {
        return suite("concurrency",
                test("200 virtual threads at once: each request gets exactly its answer and the handler the right"
                        + " identity", () -> {
                            var security = WebSecurity.builder()
                                    .authentication(HttpBasic.of(Support.authenticator(), "app"))
                                    .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
                                    .rule(RequestMatcher.under("/admin"), AccessRule.hasRole("ADMIN"))
                                    .rule(RequestMatcher.under("/user"), AccessRule.hasAnyRole("USER"))
                                    .build();
                            var mismatches = new ConcurrentLinkedQueue<String>();
                            spola.web.WebHandler handler = request -> {
                                var identity = WebSecurity.identity(request);
                                var code = identity.map(i -> CODES.getOrDefault(i.name(), "?")).orElse("-");
                                if (identity.isPresent() && !WebSecurity.requireIdentity(request).equals(identity.get())) {
                                    mismatches.add("requireIdentity differs");
                                }
                                return Response.text(200, "seen:" + code);
                            };
                            var router = Support.router()
                                    .filter(security.filter())
                                    .get("/public/x", handler)
                                    .get("/admin/x", handler)
                                    .get("/user/x", handler)
                                    .build();
                            var kinds = kinds();
                            var start = new CountDownLatch(1);
                            try (var server = Support.serve(router);
                                    var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                                var futures = new ArrayList<Future<String>>();
                                for (int i = 0; i < 200; i++) {
                                    var kind = kinds.get(i % kinds.size());
                                    int n = i;
                                    futures.add(executor.submit(() -> {
                                        start.await();
                                        var reply = kind.authorization() == null
                                                ? Support.send(server.port(), "GET", kind.path())
                                                : Support.send(server.port(), "GET", kind.path(), kind.authorization());
                                        if (reply.status() != kind.status() || !reply.body().equals(kind.body())) {
                                            return "request " + n + " " + kind.path() + ": expected " + kind.status() + " "
                                                    + kind.body() + ", was " + reply;
                                        }
                                        return "";
                                    }));
                                }
                                start.countDown();
                                var failures = new ArrayList<String>();
                                for (var future : futures) {
                                    var result = future.get(60, TimeUnit.SECONDS);
                                    if (!result.isEmpty()) {
                                        failures.add(result);
                                    }
                                }
                                assertEquals(List.of(), failures);
                                assertEquals(List.of(), List.copyOf(mismatches));
                            }
                        }));
    }
}
