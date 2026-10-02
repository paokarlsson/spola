package spola.util;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.Logger;
import spola.test.Suite;
import spola.util.LoggingTests.RecursiveMessage;
import spola.util.LoggingTests.ThrowingToString;

/**
 * {@link Log}'s guarantee that a logging call does not throw because of a bad trailing exception
 * or a failing backend, whatever the backend is; only fatal errors propagate.
 */
final class LogFailureTests {

    private LogFailureTests() {
    }

    /**
     * Behaves like a typical backend: for a record with an exception it calls the exception's
     * {@code toString()} and {@code printStackTrace(...)} before it records anything, so a bad
     * exception makes {@code log} throw. Records without an exception are recorded as they are.
     */
    static class PrintingLogger implements System.Logger {
        record Entry(Level level, String message, Throwable thrown) {
        }

        final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String getName() {
            return "printing";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            calls.incrementAndGet();
            if (thrown != null) {
                var text = new StringWriter();
                text.write(thrown.toString());
                thrown.printStackTrace(new PrintWriter(text));
            }
            entries.add(new Entry(level, message, thrown));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            calls.incrementAndGet();
            entries.add(new Entry(level, format, null));
        }
    }

    /** A backend whose {@code log} methods always throw what {@code failure} supplies. */
    static final class ThrowingLogger implements System.Logger {
        final Supplier<? extends Throwable> failure;
        final AtomicInteger calls = new AtomicInteger();

        ThrowingLogger(Supplier<? extends Throwable> failure) {
            this.failure = failure;
        }

        @Override
        public String getName() {
            return "throwing";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            calls.incrementAndGet();
            throwIt();
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            calls.incrementAndGet();
            throwIt();
        }

        private void throwIt() {
            switch (failure.get()) {
                case RuntimeException e -> throw e;
                case Error e -> throw e;
                case Throwable t -> throw new AssertionError("test setup: unchecked throwables only", t);
            }
        }
    }

    /** A bad trailing exception and the error printing it gives. */
    record Bad(String name, Supplier<RuntimeException> exception, Class<? extends Throwable> failure) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Bad> bad() {
        return List.of(
                new Bad("toString throws AssertionError", ThrowingToString::new, AssertionError.class),
                new Bad("recursive getMessage gives StackOverflowError", RecursiveMessage::new,
                        StackOverflowError.class));
    }

    /** The fallback text {@code Log} logs when the backend fails with an exception. */
    static String fallback(String text, Throwable thrown, Class<? extends Throwable> failure) {
        return text + " [log(" + thrown.getClass().getName() + ") threw " + failure.getName() + "]";
    }

    static Suite tests() {
        return suite("log failures",
                parameterized("a backend that prints a bad trailing exception: nothing escapes, the text and a marker"
                        + " are logged", bad(), bad -> {
                    var logger = new PrintingLogger();
                    var log = Log.of(logger);
                    var thrown = bad.exception().get();
                    log.warn("x", thrown);
                    log.error("order {} failed", 42, thrown);
                    log.info(() -> "lazy", thrown);
                    log.info("after");
                    var messages = logger.entries.stream().map(PrintingLogger.Entry::message).toList();
                    assertEquals(List.of(
                            fallback("x", thrown, bad.failure()),
                            fallback("order 42 failed", thrown, bad.failure()),
                            fallback("lazy", thrown, bad.failure()),
                            "after"), messages);
                    assertTrue(messages.getFirst().contains(thrown.getClass().getName()), messages.getFirst());
                    assertTrue(messages.getFirst().contains(bad.failure().getName()), messages.getFirst());
                    for (var entry : logger.entries) {
                        assertNull(entry.thrown()); // the retry is logged without the exception
                    }
                    assertEquals(Level.WARNING, logger.entries.getFirst().level());
                    assertEquals(7, logger.calls.get(), "two calls per failing message, one for the good one");
                }),
                test("a backend that prints a good trailing exception gets it once, unchanged", () -> {
                    var logger = new PrintingLogger();
                    var thrown = new IllegalStateException("fine");
                    Log.of(logger).warn("x", thrown);
                    assertEquals(1, logger.calls.get());
                    assertEquals("x", logger.entries.getFirst().message());
                    assertSame(thrown, logger.entries.getFirst().thrown());
                }),
                parameterized("a backend whose log always throws: nothing escapes, at most two calls per message",
                        List.<Supplier<? extends Throwable>>of(
                                () -> new IllegalStateException("backend down"),
                                () -> new AssertionError("backend bug")),
                        failure -> failure.get().getClass().getSimpleName(),
                        failure -> {
                            var logger = new ThrowingLogger(failure);
                            var log = Log.of(logger);
                            var thrown = new IllegalArgumentException("e");
                            assertCalls(1, logger, () -> log.info("x"));
                            assertCalls(1, logger, () -> log.error("x {}", "arg"));
                            assertCalls(2, logger, () -> log.error("x", thrown));
                            assertCalls(2, logger, () -> log.error("x {}", "arg", thrown));
                            assertCalls(2, logger, () -> log.error("x {} {}", "a", "b", thrown));
                            assertCalls(1, logger, () -> log.debug(() -> "x"));
                            assertCalls(2, logger, () -> log.trace(() -> "x", thrown));
                            assertCalls(2, logger, () -> log.warn("x", new ThrowingToString()));
                            for (var calls : LogTests.calls(log)) {
                                assertCalls(1, logger, () -> calls.message().accept("x"));
                                assertCalls(1, logger, () -> calls.one().accept("x {}", 1));
                                assertCalls(2, logger, () -> calls.one().accept("x", thrown));
                                assertCalls(2, logger, () -> calls.two().accept("x {}", 1, thrown));
                                assertCalls(2, logger, () -> calls.many().accept("x {}", new Object[] {1, 2, thrown}));
                                assertCalls(1, logger, () -> calls.lazy().accept(() -> "x"));
                                assertCalls(2, logger, () -> calls.lazyThrown().accept(() -> "x", thrown));
                            }
                        }),
                test("an OutOfMemoryError from the backend is rethrown, on the first call or the retry", () -> {
                    var oom = new ThrowingLogger(() -> new OutOfMemoryError("test"));
                    var log = Log.of(oom);
                    assertEquals("test", assertThrows(OutOfMemoryError.class, () -> log.info("x")).getMessage());
                    assertThrows(OutOfMemoryError.class, () -> log.error("x {}", "arg"));
                    assertThrows(OutOfMemoryError.class, () -> log.error("x", new IllegalStateException()));
                    assertThrows(OutOfMemoryError.class, () -> log.warn(() -> "x", new IllegalStateException()));
                    assertEquals(4, oom.calls.get(), "no retry after a fatal error");
                    var count = new AtomicInteger();
                    var oomOnRetry = new ThrowingLogger(() -> count.incrementAndGet() == 1
                            ? new IllegalStateException("first")
                            : new OutOfMemoryError("test"));
                    assertThrows(OutOfMemoryError.class, () -> Log.of(oomOnRetry).error("x", new RuntimeException()));
                    assertEquals(2, oomOnRetry.calls.get());
                }),
                test("isLoggable that throws propagates (outside the guarantee)", () -> {
                    var logger = new PrintingLogger() {
                        @Override
                        public boolean isLoggable(Level level) {
                            throw new IllegalStateException("isLoggable");
                        }
                    };
                    assertThrows(IllegalStateException.class, () -> Log.of(logger).info("x"));
                    assertEquals(0, logger.calls.get());
                }),
                parameterized("the JDK default backend (ConsoleHandler + SimpleFormatter, no Logging.configure):"
                        + " a bad trailing exception does not escape", bad(), LogFailureTests::defaultBackend));
    }

    /**
     * Runs one bad exception through {@code System.getLogger} with the JDK's own console handler
     * and formatter. The handler is a fresh {@link ConsoleHandler} created while
     * {@code System.err} points at a buffer (a {@code ConsoleHandler} binds {@code System.err}
     * when it is created), on a logger of its own that does not pass records to the root. Both
     * {@code System.err} and the logger are restored afterwards, so no global configuration is
     * left behind and nothing reaches the real standard error.
     */
    static void defaultBackend(Bad bad) {
        var name = "spola.util.test.defaultbackend";
        var jul = Logger.getLogger(name);
        var savedErr = System.err;
        var savedLevel = jul.getLevel();
        var savedUseParent = jul.getUseParentHandlers();
        var buffer = new ByteArrayOutputStream();
        ConsoleHandler handler = null;
        try {
            System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            handler = new ConsoleHandler();
            jul.setUseParentHandlers(false);
            jul.setLevel(java.util.logging.Level.INFO);
            jul.addHandler(handler);
            var thrown = bad.exception().get();
            var log = Log.of(System.getLogger(name));
            log.warn("x", thrown);
            log.warn("after");
            handler.flush();
            var text = buffer.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("WARNING: " + fallback("x", thrown, bad.failure())), text);
            assertTrue(text.contains("WARNING: after"), text);
        } finally {
            if (handler != null) {
                jul.removeHandler(handler);
                handler.close();
            }
            jul.setLevel(savedLevel);
            jul.setUseParentHandlers(savedUseParent);
            System.setErr(savedErr);
        }
    }

    static void assertCalls(int expected, ThrowingLogger logger, Runnable call) {
        int before = logger.calls.get();
        call.run();
        assertEquals(expected, logger.calls.get() - before, "backend calls");
    }
}
