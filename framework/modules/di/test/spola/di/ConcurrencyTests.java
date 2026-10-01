package spola.di;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import spola.test.Suite;

/** Thread-safe resolution with virtual threads. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    static final class Slow {
    }

    record Leaf(int n) {
    }

    record Pair(Leaf left, Leaf right) {
    }

    /** Runs {@code task} on {@code threads} virtual threads released together; their results. */
    static <T> List<T> together(int threads, Function<Integer, T> task) throws Exception {
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<T>>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                int n = i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.apply(n);
                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    static Set<Object> identities(List<?> objects) {
        var set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(objects);
        return set;
    }

    static Suite tests() {
        return suite("concurrency",
                test("a lazy singleton is created once when 1 000 virtual threads fetch it at the same time", () -> {
                    var created = new AtomicInteger();
                    try (var container = Container.create(binder -> binder.bind(Slow.class).to(() -> {
                        created.incrementAndGet();
                        Thread.sleep(50); // keep the window wide open for racing threads
                        return new Slow();
                    }))) {
                        var instances = together(1_000, _ -> container.get(Slow.class));
                        assertEquals(1_000, instances.size());
                        assertEquals(1, created.get(), "factory calls");
                        assertEquals(1, identities(instances).size(), "distinct instances");
                    }
                }),
                test("concurrent fetches of overlapping singleton graphs neither deadlock nor duplicate", () -> {
                    var created = new AtomicInteger();
                    try (var container = Container.create(binder -> {
                        for (int i = 0; i < 4; i++) {
                            int n = i;
                            binder.bind(Key.of(Leaf.class, n)).to(() -> {
                                created.incrementAndGet();
                                Thread.sleep(5);
                                return new Leaf(n);
                            });
                        }
                        for (int i = 0; i < 4; i++) {
                            binder.bind(Key.of(Pair.class, i)).to(Key.of(Leaf.class, i), Key.of(Leaf.class, (i + 1) % 4),
                                    Pair::new);
                        }
                    })) {
                        var pairs = together(1_000, n -> container.get(Key.of(Pair.class, n % 4)));
                        assertEquals(4, identities(pairs).size());
                        assertEquals(4, created.get(), "leaf factory calls");
                    }
                }).timeout(Duration.ofSeconds(20)),
                test("each of 1 000 concurrent call scopes gets its own instance", () -> {
                    try (var container = Container.create(binder ->
                            binder.bind(Slow.class).to(Slow::new).in(Scope.CALL))) {
                        var instances = together(1_000, _ -> container.callInCallScope(() -> {
                            var first = container.get(Slow.class);
                            assertTrue(first == container.get(Slow.class));
                            return first;
                        }));
                        assertEquals(1_000, identities(instances).size());
                    }
                }));
    }
}
