package spola.util;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNull;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import spola.test.Suite;

/** {@link Log}: placeholders, levels, no work when a level is off, threads. */
final class LogTests {

    private LogTests() {
    }

    /** Counts how often its {@code toString()} is called. */
    static final class Counted {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String toString() {
            calls.incrementAndGet();
            return "counted";
        }
    }

    /** An argument whose {@code toString()} throws, with a message that must not be logged. */
    static final class Exploding {
        @Override
        public String toString() {
            throw new IllegalStateException("do-not-print-me");
        }
    }

    /** The methods of one level, so every test can run against all five. */
    record Calls(String name, Level level, BooleanSupplier enabled, Consumer<String> message,
                 BiConsumer<String, Object> one, TriConsumer two, BiConsumer<String, Object[]> many,
                 Consumer<Supplier<String>> lazy, BiConsumer<Supplier<String>, Throwable> lazyThrown) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    interface TriConsumer {
        void accept(String format, Object first, Object second);
    }

    static List<Calls> calls(Log log) {
        return List.of(
                new Calls("trace", Level.TRACE, log::isTraceEnabled, log::trace, log::trace, log::trace, log::trace,
                        log::trace, log::trace),
                new Calls("debug", Level.DEBUG, log::isDebugEnabled, log::debug, log::debug, log::debug, log::debug,
                        log::debug, log::debug),
                new Calls("info", Level.INFO, log::isInfoEnabled, log::info, log::info, log::info, log::info,
                        log::info, log::info),
                new Calls("warn", Level.WARNING, log::isWarnEnabled, log::warn, log::warn, log::warn, log::warn,
                        log::warn, log::warn),
                new Calls("error", Level.ERROR, log::isErrorEnabled, log::error, log::error, log::error, log::error,
                        log::error, log::error));
    }

    static List<String> names() {
        return List.of("trace", "debug", "info", "warn", "error");
    }

    static Calls callsFor(Log log, String name) {
        return calls(log).stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    /** Formats through a real {@code Log} and returns the one recorded entry. */
    static RecordingLogger.Entry logged(String format, Object... arguments) {
        var logger = new RecordingLogger(Level.ALL);
        Log.of(logger).info(format, arguments);
        return logger.only();
    }

    static Suite tests() {
        return suite("log",
                placeholderTests(),
                laziness(),
                levelTests(),
                test("forClass uses the class's fully qualified name; of wraps the given logger", () -> {
                    assertEquals("spola.util.LogTests", Log.forClass(LogTests.class).name());
                    assertEquals(System.getLogger("spola.util.LogTests").getName(),
                            Log.forClass(LogTests.class).logger().getName());
                    var logger = new RecordingLogger(Level.ALL);
                    assertSame(logger, Log.of(logger).logger());
                    assertEquals("Log[recording]", Log.of(logger).toString());
                }),
                test("one static Log shared by 1 000 virtual threads loses and mixes nothing", () -> {
                    var logger = new RecordingLogger(Level.ALL);
                    var log = Log.of(logger);
                    var threads = new ArrayList<Thread>();
                    for (int i = 0; i < 1_000; i++) {
                        int n = i;
                        threads.add(Thread.ofVirtual().start(() -> log.info("thread {} value {}", n, n * 2)));
                    }
                    for (var thread : threads) {
                        thread.join();
                    }
                    assertEquals(1_000, logger.entries.size());
                    var seen = new java.util.HashSet<String>();
                    for (var entry : logger.entries) {
                        var parts = entry.message().split(" ");
                        assertEquals(Integer.parseInt(parts[1]) * 2, Integer.parseInt(parts[3]), entry.message());
                        seen.add(parts[1]);
                    }
                    assertEquals(1_000, seen.size());
                }));
    }

    static Suite placeholderTests() {
        return suite("placeholders",
                test("arguments replace the placeholders in order", () -> {
                    assertEquals("a=1, b=two, c=3.5", logged("a={}, b={}, c={}", 1, "two", 3.5).message());
                    var logger = new RecordingLogger(Level.ALL);
                    Log.of(logger).info("x={} y={}", "1", "2");
                    Log.of(logger).info("only {}", "one");
                    assertEquals("x=1 y=2", logger.entries.get(0).message());
                    assertEquals("only one", logger.entries.get(1).message());
                }),
                test("too few arguments leave the remaining placeholders untouched", () -> {
                    assertEquals("a=1, b={}, c={}", logged("a={}, b={}, c={}", 1).message());
                    var logger = new RecordingLogger(Level.ALL);
                    Log.of(logger).info("none {} here {}");
                    assertEquals("none {} here {}", logger.only().message());
                }),
                test("surplus arguments are ignored", () -> {
                    var entry = logged("a={}", 1, 2, "three");
                    assertEquals("a=1", entry.message());
                    assertNull(entry.thrown());
                    assertEquals("no placeholders", logged("no placeholders", "x").message());
                }),
                test("a last Throwable that no placeholder consumed is logged as the exception, not as text", () -> {
                    var failure = new IllegalStateException("broken-db");
                    var entry = logged("order {} failed", 42, failure);
                    assertEquals("order 42 failed", entry.message());
                    assertSame(failure, entry.thrown());
                    assertFalse(entry.message().contains("broken-db"));
                    var logger = new RecordingLogger(Level.ALL);
                    Log.of(logger).error("plain message", failure);
                    assertEquals("plain message", logger.only().message());
                    assertSame(failure, logger.only().thrown());
                    var skipped = logged("{}", 1, "surplus", failure);
                    assertEquals("1", skipped.message());
                    assertSame(failure, skipped.thrown());
                }),
                test("a Throwable consumed by a placeholder is text and not logged as the exception", () -> {
                    var failure = new IllegalStateException("shown");
                    var entry = logged("failed: {}", failure);
                    assertEquals("failed: java.lang.IllegalStateException: shown", entry.message());
                    assertNull(entry.thrown());
                    var notLast = logged("x", failure, "after");
                    assertEquals("x", notLast.message());
                    assertNull(notLast.thrown()); // only the last argument can be the exception
                }),
                test("\\{} writes a literal {} and consumes no argument; \\\\{} is a backslash and a placeholder", () -> {
                    assertEquals("literal {} then 1", logged("literal \\{} then {}", 1).message());
                    assertEquals("C:\\dir", logged("C:\\\\{}", "dir").message());
                    assertEquals("{}", logged("\\{}").message());
                    assertEquals("a \\ b", logged("a \\ b").message());
                    assertEquals("{}{}x", logged("\\{}\\{}{}", "x").message());
                    assertEquals("{ } {", logged("{ } {", "unused").message());
                }),
                test("null arguments and a null message are written as null", () -> {
                    assertEquals("a=null b=null", logged("a={} b={}", null, null).message());
                    var logger = new RecordingLogger(Level.ALL);
                    Log.of(logger).info((String) null);
                    assertEquals("null", logger.only().message());
                    assertEquals("null", logged("{}", Log.defer(() -> null)).message());
                }),
                test("arrays are written with Arrays.deepToString", () -> {
                    var logger = new RecordingLogger(Level.ALL);
                    var log = Log.of(logger);
                    log.info("{} {}", new int[] {1, 2}, new String[][] {{"a"}, {"b", null}});
                    log.info("{}", (Object) new Object[] {1, new long[] {2L}, new char[] {'c'}});
                    assertEquals("[1, 2] [[a], [b, null]]", logger.entries.get(0).message());
                    assertEquals("[1, [2], [c]]", logger.entries.get(1).message());
                }),
                test("an argument whose toString throws gives a marker with the class name, not an exception", () -> {
                    var entry = logged("value={} next={}", new Exploding(), "ok");
                    assertEquals("value=[toString() threw java.lang.IllegalStateException] next=ok", entry.message());
                    assertFalse(entry.message().contains("do-not-print-me"));
                    var nested = logged("{}", (Object) new Object[] {new Exploding()});
                    assertEquals("[toString() threw java.lang.IllegalStateException]", nested.message());
                    var deferred = logged("{}", Log.defer(() -> {
                        throw new UnsupportedOperationException("hidden");
                    }));
                    assertEquals("[supplier threw java.lang.UnsupportedOperationException]", deferred.message());
                    var self = new Log.Deferred[1];
                    self[0] = Log.defer(() -> self[0]);
                    assertEquals("[supplier threw java.lang.StackOverflowError]", logged("{}", self[0]).message());
                    var logger = new RecordingLogger(Level.ALL);
                    Log.of(logger).info(() -> {
                        throw new IllegalArgumentException("hidden");
                    });
                    assertEquals("[message supplier threw java.lang.IllegalArgumentException]",
                            logger.only().message());
                }),
                test("the logger gets a finished message and no MessageFormat parameters", () -> {
                    var entry = logged("quote ' and {0} stay {}", "x");
                    assertEquals("quote ' and {0} stay x", entry.message());
                    assertNull(entry.params());
                }),
                test("a message supplier's text is not formatted; a thrown is passed on", () -> {
                    var logger = new RecordingLogger(Level.ALL);
                    var failure = new RuntimeException("f");
                    Log.of(logger).warn(() -> "as is {} \\{}", failure);
                    assertEquals("as is {} \\{}", logger.only().message());
                    assertSame(failure, logger.only().thrown());
                }));
    }

    static Suite laziness() {
        return suite("no work when the level is off",
                parameterized("nothing is formatted and no supplier is called when off", names(), name -> {
                    var logger = new RecordingLogger(Level.OFF);
                    var calls = callsFor(Log.of(logger), name);
                    var counted = new Counted();
                    var supplied = new AtomicInteger();
                    Supplier<String> message = () -> {
                        supplied.incrementAndGet();
                        return "m";
                    };
                    var deferred = Log.defer(() -> {
                        supplied.incrementAndGet();
                        return "d";
                    });
                    calls.message().accept("plain {}");
                    calls.one().accept("{}", counted);
                    calls.two().accept("{} {}", counted, deferred);
                    calls.many().accept("{} {} {}", new Object[] {counted, counted, deferred});
                    calls.lazy().accept(message);
                    calls.lazyThrown().accept(message, new RuntimeException());
                    assertFalse(calls.enabled().getAsBoolean());
                    assertEquals(0, counted.calls.get(), "toString calls when off");
                    assertEquals(0, supplied.get(), "supplier calls when off");
                    assertTrue(logger.entries.isEmpty());

                    logger.threshold = calls.level();
                    calls.message().accept("plain {}");
                    calls.one().accept("{}", counted);
                    calls.two().accept("{} {}", counted, deferred);
                    calls.many().accept("{} {} {}", new Object[] {counted, counted, deferred});
                    calls.lazy().accept(message);
                    calls.lazyThrown().accept(message, new RuntimeException());
                    assertTrue(calls.enabled().getAsBoolean());
                    assertEquals(4, counted.calls.get(), "toString calls when on");
                    assertEquals(4, supplied.get(), "supplier calls when on");
                    assertEquals(List.of("plain {}", "counted", "counted d", "counted counted d", "m", "m"),
                            logger.entries.stream().map(RecordingLogger.Entry::message).toList());
                }),
                test("a level below the threshold is off; the threshold and above are on", () -> {
                    var logger = new RecordingLogger(Level.INFO);
                    var log = Log.of(logger);
                    var counted = new Counted();
                    log.trace("{}", counted);
                    log.debug("{}", counted);
                    assertEquals(0, counted.calls.get());
                    log.info("{}", counted);
                    log.warn("{}", counted);
                    log.error("{}", counted);
                    assertEquals(3, counted.calls.get());
                }));
    }

    static Suite levelTests() {
        return suite("levels",
                parameterized("each method logs at its System.Logger.Level", names(), name -> {
                    var logger = new RecordingLogger(Level.ALL);
                    var calls = callsFor(Log.of(logger), name);
                    var expected = switch (name) {
                        case "trace" -> Level.TRACE;
                        case "debug" -> Level.DEBUG;
                        case "info" -> Level.INFO;
                        case "warn" -> Level.WARNING;
                        case "error" -> Level.ERROR;
                        default -> throw new AssertionError(name);
                    };
                    calls.message().accept("m");
                    calls.one().accept("{}", 1);
                    calls.two().accept("{}{}", 1, 2);
                    calls.many().accept("{}", new Object[] {1, 2, 3});
                    calls.lazy().accept(() -> "s");
                    calls.lazyThrown().accept(() -> "s", null);
                    assertEquals(6, logger.entries.size());
                    for (var entry : logger.entries) {
                        assertEquals(expected, entry.level(), name);
                    }
                    logger.threshold = expected;
                    assertTrue(calls.enabled().getAsBoolean(), "enabled at its own level");
                    if (expected != Level.ERROR) {
                        logger.threshold = Level.values()[expected.ordinal() + 1];
                        assertFalse(calls.enabled().getAsBoolean(), "disabled one level up");
                    }
                    logger.threshold = Level.OFF;
                    assertFalse(calls.enabled().getAsBoolean(), "disabled when off");
                }));
    }
}
