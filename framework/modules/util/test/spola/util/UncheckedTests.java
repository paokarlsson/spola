package spola.util;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import spola.test.Suite;

/** {@link Unchecked}: every adapter and direct call, every kind of exception. */
final class UncheckedTests {

    private UncheckedTests() {
    }

    /** Throws {@code failure} as it is, checked or not. */
    static void raise(Throwable failure) throws Exception {
        switch (failure) {
            case Exception e -> throw e;
            case Error e -> throw e;
            default -> throw new AssertionError(failure);
        }
    }

    /** One way of calling throwing code through {@link Unchecked}: runs code that throws {@code failure}. */
    record Shape(String name, Consumer<Throwable> call) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Shape> SHAPES = List.of(
            new Shape("ThrowingRunnable via runnable()", f -> Unchecked.runnable(() -> raise(f)).run()),
            new Shape("ThrowingSupplier via supplier()", f -> Unchecked.supplier(() -> {
                raise(f);
                return "x";
            }).get()),
            new Shape("ThrowingFunction via function()", f -> Unchecked.<String, String>function(v -> {
                raise(f);
                return v;
            }).apply("x")),
            new Shape("ThrowingConsumer via consumer()", f -> Unchecked.<String>consumer(v -> raise(f)).accept("x")),
            new Shape("ThrowingRunnable via run()", f -> Unchecked.run(() -> raise(f))),
            new Shape("ThrowingSupplier via get()", f -> Unchecked.get(() -> {
                raise(f);
                return 1;
            })));

    static Suite tests() {
        return suite("unchecked",
                parameterized("IOException becomes UncheckedIOException with the original as cause", SHAPES, shape -> {
                    var io = new FileNotFoundException("missing.txt");
                    var e = assertThrows(UncheckedIOException.class, () -> shape.call().accept(io));
                    assertSame(io, e.getCause());
                }),
                parameterized("InterruptedException sets the interrupt flag again and is wrapped", SHAPES, shape -> {
                    var interrupted = new InterruptedException("stop");
                    assertFalse(Thread.currentThread().isInterrupted());
                    try {
                        var e = assertThrows(UncheckedException.class, () -> shape.call().accept(interrupted));
                        assertSame(interrupted, e.getCause());
                        assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag set again");
                    } finally {
                        Thread.interrupted();
                    }
                }),
                parameterized("another checked exception is wrapped with the original as cause", SHAPES, shape -> {
                    var checked = new TimeoutException("too slow");
                    var e = assertThrows(UncheckedException.class, () -> shape.call().accept(checked));
                    assertSame(checked, e.getCause());
                    assertEquals("java.util.concurrent.TimeoutException: too slow", e.getMessage());
                    assertFalse(Thread.currentThread().isInterrupted());
                }),
                parameterized("RuntimeException and Error pass through as the same instance", SHAPES, shape -> {
                    var runtime = new IllegalArgumentException("bad");
                    assertSame(runtime, assertThrows(IllegalArgumentException.class, () -> shape.call().accept(runtime)));
                    var error = new StackOverflowError("deep");
                    assertSame(error, assertThrows(StackOverflowError.class, () -> shape.call().accept(error)));
                    var unchecked = new UncheckedIOException(new IOException("already unchecked"));
                    assertSame(unchecked, assertThrows(UncheckedIOException.class, () -> shape.call().accept(unchecked)));
                }),
                test("values pass through the adapters", () -> {
                    assertEquals(List.of(3, 5), List.of("abc", "hello").stream()
                            .map(Unchecked.function(String::length)).toList());
                    var seen = new ArrayList<String>();
                    List.of("a", "b").forEach(Unchecked.consumer(seen::add));
                    assertEquals(List.of("a", "b"), seen);
                    assertEquals("s", Unchecked.supplier(() -> "s").get());
                    assertEquals(7, Unchecked.get(() -> 7));
                    var ran = new boolean[2];
                    Unchecked.run(() -> ran[0] = true);
                    Unchecked.runnable(() -> ran[1] = true).run();
                    assertTrue(ran[0] && ran[1]);
                }),
                test("wrap is usable in your own catch blocks", () -> {
                    var io = new IOException("x");
                    assertSame(io, Unchecked.wrap(io).getCause());
                    var runtime = new IllegalStateException();
                    assertSame(runtime, Unchecked.wrap(runtime));
                    assertThrows(NullPointerException.class, () -> Unchecked.wrap(null));
                }));
    }
}
