package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Parsing, reading and writing from many virtual threads at once. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    static Suite tests() {
        return suite("concurrency",
                test("1 000 virtual threads parse the same document and read a shared tree with the same results", () -> {
                    var document = RfcExampleTests.OBJECT;
                    var bytes = document.getBytes(StandardCharsets.UTF_8);
                    var shared = (JsonObject) Json.parse(document);
                    var expectedText = Json.writePretty(shared);
                    var start = new CountDownLatch(1);
                    var failures = new AtomicInteger();
                    Set<String> results = ConcurrentHashMap.newKeySet();
                    var threads = new ArrayList<Thread>();
                    for (int i = 0; i < 1_000; i++) {
                        boolean asBytes = i % 2 == 0;
                        threads.add(Thread.ofVirtual().start(() -> {
                            try {
                                start.await();
                                var own = (JsonObject) (asBytes ? Json.parse(bytes) : Json.parse(document));
                                var image = shared.object("Image");
                                var result = Json.writePretty(own) + "|" + own.equals(shared) + "|" + own.hashCode()
                                        + "|" + image.object("Thumbnail").string("Url")
                                        + "|" + image.array("IDs").number(3).intValueExact()
                                        + "|" + Json.writePretty(shared);
                                results.add(result);
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
                    assertEquals(Set.of(expectedText + "|true|" + shared.hashCode()
                            + "|http://www.example.com/image/481989943|38793|" + expectedText), results);
                }));
    }
}
