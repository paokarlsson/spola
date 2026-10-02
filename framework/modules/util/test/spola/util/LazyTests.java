package spola.util;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import spola.test.Suite;

/** {@link Lazy}: once under contention, exceptions and null not remembered, supplier released. */
final class LazyTests {

    private LazyTests() {
    }

    static Suite tests() {
        return suite("lazy",
                test("1 000 virtual threads calling get() at once compute the value exactly once", () -> {
                    var computations = new AtomicInteger();
                    var lazy = Lazy.of(() -> {
                        computations.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return new Object();
                    });
                    var start = new CountDownLatch(1);
                    var results = ConcurrentHashMap.<Object>newKeySet();
                    var failures = new AtomicInteger();
                    var threads = new ArrayList<Thread>();
                    for (int i = 0; i < 1_000; i++) {
                        threads.add(Thread.ofVirtual().start(() -> {
                            try {
                                start.await();
                                results.add(lazy.get());
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
                    assertEquals(1, computations.get());
                    assertEquals(1, results.size());
                    assertSame(results.iterator().next(), lazy.get());
                    assertEquals(1, computations.get());
                }),
                test("an exception from the supplier is not remembered; the next get() tries again", () -> {
                    var calls = new AtomicInteger();
                    var failure = new IllegalStateException("temporary");
                    var lazy = Lazy.of(() -> {
                        if (calls.incrementAndGet() == 1) {
                            throw failure;
                        }
                        return "ok";
                    });
                    assertSame(failure, assertThrows(IllegalStateException.class, lazy::get));
                    assertFalse(lazy.isComputed());
                    assertEquals("ok", lazy.get());
                    assertEquals("ok", lazy.get());
                    assertEquals(2, calls.get());
                }),
                test("null from the supplier is a NullPointerException with a clear message, not remembered", () -> {
                    var calls = new AtomicInteger();
                    var lazy = Lazy.of(() -> calls.incrementAndGet() == 1 ? null : "second");
                    var e = assertThrows(NullPointerException.class, lazy::get);
                    assertTrue(e.getMessage().contains("supplier of a Lazy returned null"), e.getMessage());
                    assertEquals("second", lazy.get());
                    assertThrows(NullPointerException.class, () -> Lazy.of(null));
                }),
                test("the supplier is released after a successful computation", () -> {
                    var captured = new StringBuilder("value");
                    // A capturing lambda: a new object per evaluation, unlike a constant lambda the JVM caches.
                    Supplier<String> supplier = () -> captured.toString();
                    var released = new WeakReference<>(supplier);
                    var lazy = Lazy.of(supplier);
                    supplier = null;
                    assertEquals("value", lazy.get());
                    for (int i = 0; i < 100 && released.get() != null; i++) {
                        System.gc();
                        Thread.sleep(10);
                    }
                    assertTrue(released.get() == null, "the supplier is still reachable from the Lazy");
                    assertEquals("value", lazy.get());
                }),
                test("a supplier calling its own get() fails instead of recursing", () -> {
                    var holder = new ArrayList<Lazy<String>>();
                    holder.add(Lazy.of(() -> holder.getFirst().get() + "!"));
                    assertThrows(IllegalStateException.class, () -> holder.getFirst().get());
                }),
                test("toString neither computes nor shows the value", () -> {
                    var calls = new AtomicInteger();
                    var lazy = Lazy.of(() -> "secret-" + calls.incrementAndGet());
                    assertEquals("Lazy[not computed]", lazy.toString());
                    assertEquals(0, calls.get());
                    lazy.get();
                    assertEquals("Lazy[computed]", lazy.toString());
                    assertTrue(Set.of(lazy.get()).contains("secret-1"));
                }));
    }
}
