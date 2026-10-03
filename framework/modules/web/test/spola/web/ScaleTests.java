package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 14: 1 000 routes, and 200 concurrent clients on one router. */
final class ScaleTests {

    private ScaleTests() {
    }

    static final AttributeKey<String> CLIENT = AttributeKey.of("client", String.class);

    static Suite tests() {
        return suite("scale and concurrency",
                test("1 000 routes of mixed shapes all match correctly", () -> {
                    var builder = Router.builder();
                    var targets = new ArrayList<String[]>();
                    for (int i = 0; i < 1_000; i++) {
                        int n = i;
                        switch (i % 5) {
                            case 0 -> {
                                builder.get("/s" + i, r -> Response.text(200, "route " + n));
                                targets.add(new String[] {"GET", "/s" + i, "route " + n});
                            }
                            case 1 -> {
                                builder.get("/s" + i + "/{id}", r -> Response.text(200, "route " + n + " id=" + r.pathParam("id")));
                                targets.add(new String[] {"GET", "/s" + i + "/abc" + i, "route " + n + " id=abc" + i});
                            }
                            case 2 -> {
                                builder.post("/g/{x}/s" + i, r -> Response.text(200, "route " + n + " x=" + r.pathParam("x")));
                                targets.add(new String[] {"POST", "/g/v" + i + "/s" + i, "route " + n + " x=v" + i});
                            }
                            case 3 -> {
                                builder.get("/c" + i + "/{rest...}", r -> Response.text(200, "route " + n + " rest="
                                        + r.pathParam("rest")));
                                targets.add(new String[] {"GET", "/c" + i + "/a/b", "route " + n + " rest=a/b"});
                            }
                            default -> {
                                builder.put("/t/{a}/{b}/s" + i, r -> Response.text(200, "route " + n + " "
                                        + r.pathParam("a") + r.pathParam("b")));
                                targets.add(new String[] {"PUT", "/t/1/2/s" + i, "route " + n + " 12"});
                            }
                        }
                    }
                    var router = builder.build();
                    assertEquals("Router[1000 routes, 0 filters, 0 mappers]", router.toString());
                    try (var server = Support.serve(router); var client = Support.client()) {
                        var base = "http://127.0.0.1:" + server.port();
                        for (var target : targets) {
                            var response = client.send(HttpRequest.newBuilder(URI.create(base + target[1]))
                                    .method(target[0], HttpRequest.BodyPublishers.noBody()).build(),
                                    HttpResponse.BodyHandlers.ofString());
                            assertEquals(200, response.statusCode(), target[0] + " " + target[1]);
                            assertEquals(target[2], response.body());
                        }
                        var missing = client.send(HttpRequest.newBuilder(URI.create(base + "/s1000")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(404, missing.statusCode());
                    }
                }),
                test("200 concurrent clients get their own response and attribute", () -> {
                    var router = Router.builder()
                            .filter((request, next) -> next.proceed(
                                    request.withAttribute(CLIENT, request.headers().first("X-Client").orElseThrow())))
                            .get("/who/{n}", r -> {
                                Thread.sleep(20); // overlap the requests
                                return Response.text(200, "n=" + r.pathParam("n") + " q=" + r.query("q").orElseThrow()
                                        + " client=" + r.attribute(CLIENT).orElseThrow());
                            })
                            .build();
                    try (var server = Support.serve(router);
                         var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var start = new CountDownLatch(1);
                        var futures = new ArrayList<Future<String>>();
                        for (int i = 0; i < 200; i++) {
                            int n = i;
                            futures.add(executor.submit(() -> {
                                start.await();
                                return Support.send(server.port(), "GET", "/who/" + n + "?q=" + (n * 7), "X-Client: c" + n)
                                        .body();
                            }));
                        }
                        start.countDown();
                        for (int i = 0; i < 200; i++) {
                            assertEquals("n=" + i + " q=" + (i * 7) + " client=c" + i, futures.get(i).get());
                        }
                    }
                }));
    }
}
