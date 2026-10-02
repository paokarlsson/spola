package spola.json.codec;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Codecs are shared constants: 1 000 virtual threads read and write with the same ones at once. */
final class CodecConcurrencyTests {

    private CodecConcurrencyTests() {
    }

    static Suite tests() {
        return suite("concurrency",
                test("1 000 virtual threads share the codecs, reading and writing at once, with the same results", () -> {
                    // a fresh lazy codec, so the threads also race to resolve it on first use
                    var tree = JsonCodecs.list(JsonCodecs.lazy(() -> Model.NODE));
                    var value = Model.everything();
                    var expected = Model.EVERYTHING.write(value);
                    var bad = expected.replace("\"retries\":5", "\"retries\":\"5\"").replace("\"a@b.se\"", "\"ab.se\"");
                    var expectedProblems = Problems.of(() -> Model.EVERYTHING.read(bad));
                    var trees = "[" + Model.NODE.write(Model.tree(3, 2)) + "]";
                    var start = new CountDownLatch(1);
                    var failures = new AtomicInteger();
                    Set<String> results = ConcurrentHashMap.newKeySet();
                    var threads = new ArrayList<Thread>();
                    for (int i = 0; i < 1_000; i++) {
                        threads.add(Thread.ofVirtual().start(() -> {
                            try {
                                start.await();
                                var read = Model.EVERYTHING.read(expected);
                                var written = Model.EVERYTHING.write(read);
                                List<JsonProblem> problems;
                                try {
                                    Model.EVERYTHING.read(bad);
                                    problems = List.of();
                                } catch (JsonMappingException e) {
                                    problems = e.problems();
                                }
                                var nodes = tree.write(tree.read(trees));
                                results.add(read.equals(value) + "|" + written + "|" + problems + "|" + nodes);
                            } catch (Throwable e) {
                                failures.incrementAndGet();
                            }
                        }));
                    }
                    start.countDown();
                    for (var thread : threads) {
                        thread.join();
                    }
                    assertEquals(0, failures.get());
                    assertEquals(Set.of("true|" + expected + "|" + expectedProblems + "|" + trees), results);
                    assertEquals(2, expectedProblems.size());
                }));
    }
}
