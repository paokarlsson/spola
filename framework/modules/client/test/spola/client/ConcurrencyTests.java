package spola.client;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Response;
import spola.json.Json;
import spola.json.codec.JsonCodec;
import spola.test.Suite;

/** Criterion 14: 200 virtual threads share one RestClient, each with its own body. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    record Echo(int n, String text) {
    }

    static final JsonCodec<Echo> ECHO = JsonCodec.object(Echo::new, field("n", INT, Echo::n), field("text", STRING, Echo::text));

    static Suite tests() {
        return suite("concurrency",
                test("200 virtual threads share a RestClient, send their own body and get their own answer", () -> {
                    var inFlight = new AtomicInteger();
                    var most = new AtomicInteger();
                    try (var server = Support.serve(request -> {
                        int now = inFlight.incrementAndGet();
                        most.accumulateAndGet(now, Math::max);
                        try {
                            var echo = ECHO.decode(Json.parse(request.bytes()));
                            Thread.sleep(20); // let the calls overlap
                            return Response.bytes(200, "application/json",
                                    ECHO.write(new Echo(echo.n() * 2, echo.text() + "!")).getBytes(
                                            java.nio.charset.StandardCharsets.UTF_8));
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    }); var client = RestClient.builder().baseUri(Support.base(server.port()))
                            .timeout(Duration.ofSeconds(30)).build()) {
                        var start = new CountDownLatch(1);
                        var wrong = new ConcurrentLinkedQueue<String>();
                        var request = client.post("/echo"); // shared too: every json(...) makes a new request
                        var futures = new ArrayList<Future<Echo>>();
                        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                            for (int i = 0; i < 200; i++) {
                                int n = i;
                                futures.add(executor.submit(() -> {
                                    start.await();
                                    var answer = request.json(new Echo(n, "t" + n), ECHO).retrieve(ECHO);
                                    if (!answer.equals(new Echo(n * 2, "t" + n + "!"))) {
                                        wrong.add(n + " got " + answer);
                                    }
                                    return answer;
                                }));
                            }
                            start.countDown();
                        }
                        var answers = new ArrayList<Echo>();
                        for (var future : futures) {
                            answers.add(future.get());
                        }
                        assertEquals(List.of(), List.copyOf(wrong));
                        for (int i = 0; i < 200; i++) {
                            assertEquals(new Echo(i * 2, "t" + i + "!"), answers.get(i));
                        }
                        assertTrue(most.get() > 1, "the calls overlapped: at most " + most.get() + " at once");
                    }
                }));
    }
}
