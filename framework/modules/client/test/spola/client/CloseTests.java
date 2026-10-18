package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.Response;
import spola.json.JsonObject;
import spola.test.Suite;

/** Criterion 15: close(). */
final class CloseTests {

    private CloseTests() {
    }

    static Suite tests() {
        return suite("close",
                test("after close() every retrieve throws IllegalStateException; a second close() does not throw", () -> {
                    try (var server = Support.serve(request -> Response.text(200, "{}"))) {
                        var client = Support.client(server.port());
                        var request = client.post("/x").json(JsonObject.of());
                        assertEquals(JsonObject.of(), request.retrieveJson());
                        client.close();
                        assertThrows(IllegalStateException.class, "the RestClient is closed", request::retrieveJson);
                        assertThrows(IllegalStateException.class, () -> request.retrieve(ResponseTests.POINT));
                        assertThrows(IllegalStateException.class, request::retrieveBodiless);
                        assertThrows(IllegalStateException.class, () -> client.get("/y").retrieveJson());
                        client.close();
                        assertTrue(client.toString().endsWith(", closed]"), client.toString());
                    }
                }),
                test("calls racing with close() either finish or get IllegalStateException, never a transport error",
                        () -> {
                    for (int round = 0; round < 5; round++) {
                        try (var server = Support.serve(request -> Response.text(200, "{}"))) {
                            var client = Support.client(server.port());
                            var unexpected = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
                            var refused = new java.util.concurrent.atomic.AtomicInteger();
                            var threads = new java.util.ArrayList<Thread>();
                            for (int i = 0; i < 20; i++) {
                                threads.add(Thread.ofVirtual().start(() -> {
                                    while (true) {
                                        try {
                                            client.get("/x").retrieveJson();
                                        } catch (IllegalStateException e) {
                                            refused.incrementAndGet();
                                            return;
                                        } catch (RuntimeException e) {
                                            unexpected.add(e);
                                            return;
                                        }
                                    }
                                }));
                            }
                            Thread.sleep(50);
                            client.close();
                            for (var thread : threads) {
                                thread.join(5_000);
                            }
                            assertEquals(java.util.List.of(), java.util.List.copyOf(unexpected));
                            assertEquals(20, refused.get());
                        }
                    }
                }),
                test("close() waits for a call in progress", () -> {
                    var entered = new CountDownLatch(1);
                    try (var server = Support.serve(request -> {
                        entered.countDown();
                        Thread.sleep(300);
                        return Response.text(200, "{\"late\":true}");
                    })) {
                        var client = RestClient.builder().baseUri(Support.base(server.port()))
                                .timeout(Duration.ofSeconds(10)).build();
                        var result = new AtomicReference<Object>();
                        var caller = Thread.ofVirtual().start(() -> {
                            try {
                                result.set(client.get("/x").retrieveJson());
                            } catch (RuntimeException e) {
                                result.set(e);
                            }
                        });
                        entered.await();
                        client.close();
                        caller.join(5_000);
                        assertEquals(JsonObject.of("late", spola.json.JsonBoolean.TRUE), result.get());
                    }
                }));
    }
}
