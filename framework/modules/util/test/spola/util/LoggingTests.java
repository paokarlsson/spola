package spola.util;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import spola.test.Body;
import spola.test.Suite;

/** {@link Logging}: the one-line format, levels per prefix and replacing a configuration. */
final class LoggingTests {

    private LoggingTests() {
    }

    /** One line: timestamp, level padded to five, [thread], logger name, " : ", message. */
    static final Pattern LINE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})) (TRACE|DEBUG|INFO |WARN |ERROR)"
                    + " \\[([^\\]]*)\\] (\\S*) : (.*)");

    /** Runs {@code body} with logging configured, and always restores the JDK's setup afterwards. */
    static void withLogging(LoggingSettings settings, Body body) throws Exception {
        var root = Logger.getLogger("");
        var handlersBefore = List.of(root.getHandlers());
        var levelBefore = root.getLevel();
        try {
            Logging.configure(settings);
            body.run();
        } finally {
            Logging.reset();
            assertEquals(handlersBefore, List.of(root.getHandlers()), "root handlers restored");
            assertEquals(levelBefore, root.getLevel(), "root level restored");
        }
    }

    static List<String> lines(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    static Log log(String name) {
        return Log.of(System.getLogger(name));
    }

    static LoggingSettings utc(ByteArrayOutputStream out, Map<String, Level> levels) {
        return new LoggingSettings(levels, out, ZoneOffset.UTC);
    }

    static Suite tests() {
        return suite("console logging",
                test("one line per message: ISO-8601 timestamp with millis, level, thread, logger, message", () -> {
                    var out = new ByteArrayOutputStream();
                    var before = Instant.now().minusMillis(1);
                    withLogging(utc(out, Map.of("", Level.INFO)), () -> {
                        var named = Thread.ofPlatform().name("worker-7").start(
                                () -> log("spola.test.format.Orders").info("order {} placed", 42));
                        named.join();
                        var unnamed = Thread.ofVirtual().unstarted(
                                () -> log("spola.test.format.Payments").warn("slow {} ms", 250));
                        unnamed.start();
                        unnamed.join();
                        log("spola.test.format.Orders").error("tab\tkept, newline\nescaped\r too");
                        var after = Instant.now().plusMillis(1);
                        var lines = lines(out);
                        assertEquals(3, lines.size(), String.join("\n", lines));

                        var first = LINE.matcher(lines.get(0));
                        assertTrue(first.matches(), lines.get(0));
                        var time = OffsetDateTime.parse(first.group(1));
                        assertEquals(ZoneOffset.UTC, time.getOffset());
                        assertTrue(!time.toInstant().isBefore(before) && !time.toInstant().isAfter(after),
                                "timestamp " + time + " between " + before + " and " + after);
                        assertEquals("INFO ", first.group(2));
                        assertEquals("worker-7", first.group(3));
                        assertEquals("spola.test.format.Orders", first.group(4));
                        assertEquals("order 42 placed", first.group(5));

                        var second = LINE.matcher(lines.get(1));
                        assertTrue(second.matches(), lines.get(1));
                        assertEquals("WARN ", second.group(2));
                        assertEquals("virtual-" + unnamed.threadId(), second.group(3));
                        assertEquals("slow 250 ms", second.group(5));

                        var third = LINE.matcher(lines.get(2));
                        assertTrue(third.matches(), lines.get(2));
                        assertEquals("ERROR", third.group(2));
                        assertEquals("tab\tkept, newline\\nescaped\\r too", third.group(5));
                    });
                }),
                test("the stack trace follows on the next lines", () -> {
                    var out = new ByteArrayOutputStream();
                    withLogging(utc(out, Map.of("", Level.INFO)), () -> {
                        var failure = new IOException("disk full");
                        failure.initCause(new IllegalStateException("root cause"));
                        log("spola.test.trace.Store").error("could not save {}", "order-1", failure);
                        log("spola.test.trace.Store").info("after");
                        var lines = lines(out);
                        var head = LINE.matcher(lines.getFirst());
                        assertTrue(head.matches(), lines.getFirst());
                        assertEquals("could not save order-1", head.group(5));
                        assertEquals("\tjava.io.IOException: disk full", lines.get(1));
                        assertTrue(lines.get(2).startsWith("\t\tat ") && lines.get(2).contains("spola.util.LoggingTests"),
                                lines.get(2));
                        assertTrue(lines.stream().anyMatch(l -> l.equals(
                                "\tCaused by: java.lang.IllegalStateException: root cause")), String.join("\n", lines));
                        var last = LINE.matcher(lines.getLast());
                        assertTrue(last.matches(), lines.getLast());
                        assertEquals("after", last.group(5));
                        for (var line : lines.subList(1, lines.size() - 1)) {
                            assertFalse(LINE.matcher(line).matches(), "stack trace line looks like a record: " + line);
                        }
                    });
                }),
                test("line breaks in exception messages cannot forge a record", () -> {
                    var out = new ByteArrayOutputStream();
                    withLogging(utc(out, Map.of("", Level.INFO)), () -> {
                        var fake = "2026-10-02T08:00:00.000+02:00 ERROR [main] com.example.Auth : admin login OK";
                        NumberFormatException failure = null;
                        try {
                            Integer.parseInt("42\n" + fake);
                        } catch (NumberFormatException e) {
                            failure = e;
                        }
                        failure.initCause(new IllegalStateException("cause\r" + fake));
                        failure.addSuppressed(new IllegalArgumentException("suppressed\r\n" + fake + "\u2028" + fake));
                        log("spola.test.forge.Auth").warn("bad id", failure);
                        log("spola.test.forge.Auth").info("after");
                        var lines = lines(out);
                        assertTrue(LINE.matcher(lines.getFirst()).matches(), lines.getFirst());
                        assertTrue(LINE.matcher(lines.getLast()).matches(), lines.getLast());
                        var continuation = lines.subList(1, lines.size() - 1);
                        assertTrue(continuation.size() > 3, String.join("\n", lines));
                        for (var line : continuation) {
                            assertTrue(line.startsWith("\t"), "continuation line without tab: " + line);
                            assertFalse(LINE.matcher(line).matches(), "stack trace line looks like a record: " + line);
                            assertFalse(line.contains("\u2028"), "unescaped U+2028: " + line);
                        }
                        var all = String.join("\n", continuation);
                        assertTrue(all.contains("\t" + fake), all);
                        assertTrue(all.contains("cause"), all);
                        assertTrue(all.contains("suppressed"), all);
                        assertTrue(all.contains("\\u2028"), all);
                    });
                }),
                test("a trailing exception whose toString or getMessage throws an Error is still logged", () -> {
                    var out = new ByteArrayOutputStream();
                    withLogging(utc(out, Map.of("", Level.INFO)), () -> {
                        var throwing = new ThrowingToString();
                        var recursive = new RecursiveMessage();
                        log("spola.test.badthrown.X").warn("t", throwing);
                        log("spola.test.badthrown.X").warn("d", recursive);
                        log("spola.test.badthrown.X").info("after");
                        var lines = lines(out);
                        assertEquals(5, lines.size(), String.join("\n", lines));
                        var first = LINE.matcher(lines.get(0));
                        assertTrue(first.matches(), lines.get(0));
                        assertEquals("t", first.group(5));
                        assertEquals("\t" + ThrowingToString.class.getName()
                                + " [printStackTrace() threw java.lang.AssertionError]", lines.get(1));
                        var second = LINE.matcher(lines.get(2));
                        assertTrue(second.matches(), lines.get(2));
                        assertEquals("d", second.group(5));
                        assertEquals("\t" + RecursiveMessage.class.getName()
                                + " [printStackTrace() threw java.lang.StackOverflowError]", lines.get(3));
                        var last = LINE.matcher(lines.get(4));
                        assertTrue(last.matches(), lines.get(4));
                        assertEquals("after", last.group(5));
                    });
                }),
                test("levels per prefix: the longest prefix wins and prefixes match whole segments", () -> {
                    var out = new ByteArrayOutputStream();
                    var levels = Map.of(
                            "", Level.WARNING,
                            "spola.lvl.com.ex", Level.TRACE,
                            "spola.lvl.com.example", Level.DEBUG,
                            "spola.lvl.com.example.noisy", Level.ERROR);
                    withLogging(utc(out, levels), () -> {
                        var order = log("spola.lvl.com.example.Order");
                        var noisy = log("spola.lvl.com.example.noisy.Poller");
                        var ex = log("spola.lvl.com.ex.Thing");
                        var examples = log("spola.lvl.com.examples.Other");
                        var other = log("spola.other.X");
                        order.trace("order trace");
                        order.debug("order debug");
                        noisy.warn("noisy warn");
                        noisy.error("noisy error");
                        ex.trace("ex trace");
                        examples.info("examples info");
                        examples.warn("examples warn");
                        other.info("other info");
                        other.warn("other warn");
                        log("spola.lvl.com.example").debug("prefix itself");
                        assertFalse(order.isTraceEnabled(), "com.ex must not cover com.example");
                        assertTrue(order.isDebugEnabled());
                        assertFalse(noisy.isWarnEnabled());
                        assertTrue(ex.isTraceEnabled());
                        assertFalse(examples.isInfoEnabled(), "com.example must not cover com.examples");
                        var messages = lines(out).stream().map(l -> {
                            var m = LINE.matcher(l);
                            assertTrue(m.matches(), l);
                            return m.group(5);
                        }).toList();
                        assertEquals(List.of("order debug", "noisy error", "ex trace", "examples warn", "other warn",
                                "prefix itself"), messages);
                    });
                }),
                test("a second configure replaces the first: no doubled lines, old output and levels gone", () -> {
                    var first = new ByteArrayOutputStream();
                    var second = new ByteArrayOutputStream();
                    var root = Logger.getLogger("");
                    withLogging(utc(first, Map.of("", Level.INFO, "spola.again.debug", Level.DEBUG)), () -> {
                        Logging.configure(utc(first, Map.of("", Level.INFO, "spola.again.debug", Level.DEBUG)));
                        assertEquals(1, root.getHandlers().length);
                        log("spola.again.X").info("once");
                        assertEquals(1, lines(first).size(), String.join("\n", lines(first)));

                        Logging.configure(utc(second, Map.of("", Level.WARNING)));
                        assertEquals(1, root.getHandlers().length);
                        log("spola.again.X").info("dropped by the new root level");
                        log("spola.again.debug.Y").debug("the old prefix level is gone");
                        log("spola.again.X").warn("to the second stream");
                        assertEquals(1, lines(first).size(), "nothing more to the first stream");
                        assertEquals(1, lines(second).size(), String.join("\n", lines(second)));
                        assertTrue(lines(second).getFirst().endsWith(" : to the second stream"));
                    });
                }),
                test("configure removes the JDK's default handler; reset restores it and the old levels", () -> {
                    var root = Logger.getLogger("");
                    var handlers = List.of(root.getHandlers());
                    var preset = Logger.getLogger("spola.preset");
                    preset.setLevel(java.util.logging.Level.SEVERE);
                    try {
                        var out = new ByteArrayOutputStream();
                        withLogging(utc(out, Map.of("spola.preset", Level.TRACE)), () -> {
                            assertEquals(1, root.getHandlers().length);
                            assertFalse(handlers.contains(root.getHandlers()[0]));
                            assertEquals(java.util.logging.Level.FINER, preset.getLevel());
                            assertEquals(java.util.logging.Level.INFO, root.getLevel(), "root is INFO without \"\"");
                        });
                        assertEquals(java.util.logging.Level.SEVERE, preset.getLevel());
                        Logging.reset();
                        assertEquals(handlers, List.of(root.getHandlers()), "a second reset changes nothing");
                    } finally {
                        preset.setLevel(null);
                    }
                }),
                test("a PrintStream is written with its own charset; the stream is never closed", () -> {
                    var bytes = new ByteArrayOutputStream();
                    var closed = new boolean[1];
                    var print = new PrintStream(bytes, true, StandardCharsets.ISO_8859_1) {
                        @Override
                        public void close() {
                            closed[0] = true;
                            super.close();
                        }
                    };
                    withLogging(new LoggingSettings(Map.of(), print, ZoneId.of("Europe/Stockholm")), () -> {
                        log("spola.charset.X").info("smörgås");
                        Logging.configure(new LoggingSettings(Map.of(), print, ZoneId.of("Europe/Stockholm")));
                    });
                    assertFalse(closed[0], "the stream was closed");
                    var line = bytes.toString(StandardCharsets.ISO_8859_1).strip();
                    assertTrue(line.endsWith(" : smörgås"), line);
                    var m = LINE.matcher(line);
                    assertTrue(m.matches(), line);
                    assertFalse(m.group(1).endsWith("Z"), "Stockholm is never UTC: " + m.group(1));
                }),
                test("nothing is configured by loading the classes or by logging", () -> {
                    var root = Logger.getLogger("");
                    var handlers = List.of(root.getHandlers());
                    var level = root.getLevel();
                    Log.forClass(LoggingTests.class).trace("below the JDK default level, not written");
                    LoggingSettings.defaults();
                    assertEquals(handlers, List.of(root.getHandlers()));
                    assertSame(level, root.getLevel());
                    for (var handler : root.getHandlers()) {
                        assertFalse(handler.getClass().getName().startsWith("spola."), handler.toString());
                    }
                }),
                test("settings validate prefixes and copy the map", () -> {
                    var levels = new HashMap<String, Level>();
                    levels.put("a.b", Level.DEBUG);
                    var settings = utc(new ByteArrayOutputStream(), levels);
                    levels.put("c", Level.ERROR);
                    assertEquals(Map.of("a.b", Level.DEBUG), settings.levels());
                    assertThrows(UnsupportedOperationException.class, () -> settings.levels().put("x", Level.INFO));
                    for (var bad : List.of(".a", "a.", "a..b", ".")) {
                        assertThrows(IllegalArgumentException.class, () -> utc(new ByteArrayOutputStream(),
                                Map.of(bad, Level.INFO)));
                    }
                    assertEquals(Map.of("", Level.INFO, "x", Level.TRACE),
                            LoggingSettings.defaults().withLevel("x", Level.TRACE).levels());
                    assertSame(System.err, LoggingSettings.defaults().output());
                }),
                test("parseLevel reads level names as written in configuration", () -> {
                    assertEquals(Level.ALL, LoggingSettings.parseLevel("ALL"));
                    assertEquals(Level.TRACE, LoggingSettings.parseLevel("trace"));
                    assertEquals(Level.DEBUG, LoggingSettings.parseLevel(" Debug "));
                    assertEquals(Level.INFO, LoggingSettings.parseLevel("INFO"));
                    assertEquals(Level.WARNING, LoggingSettings.parseLevel("warn"));
                    assertEquals(Level.WARNING, LoggingSettings.parseLevel("WARNING"));
                    assertEquals(Level.ERROR, LoggingSettings.parseLevel("error"));
                    assertEquals(Level.OFF, LoggingSettings.parseLevel("off"));
                    for (var level : Level.values()) {
                        assertEquals(level, LoggingSettings.parseLevel(level.getName()));
                    }
                    for (var bad : List.of("", "LOUD", "FINE", "SEVERE", "debug!", "INFO DEBUG")) {
                        var failure = assertThrows(IllegalArgumentException.class, () -> LoggingSettings.parseLevel(bad));
                        assertTrue(failure.getMessage().contains("TRACE, DEBUG, INFO, WARN, ERROR, OFF or ALL"),
                                failure.getMessage());
                    }
                    assertThrows(NullPointerException.class, () -> LoggingSettings.parseLevel(null));
                }),
                test("System.Logger levels map to java.util.logging levels as the JDK maps them", () -> {
                    assertEquals(java.util.logging.Level.ALL, Logging.julLevel(Level.ALL));
                    assertEquals(java.util.logging.Level.FINER, Logging.julLevel(Level.TRACE));
                    assertEquals(java.util.logging.Level.FINE, Logging.julLevel(Level.DEBUG));
                    assertEquals(java.util.logging.Level.INFO, Logging.julLevel(Level.INFO));
                    assertEquals(java.util.logging.Level.WARNING, Logging.julLevel(Level.WARNING));
                    assertEquals(java.util.logging.Level.SEVERE, Logging.julLevel(Level.ERROR));
                    assertEquals(java.util.logging.Level.OFF, Logging.julLevel(Level.OFF));
                    assertEquals("DEBUG", LineFormatter.levelName(java.util.logging.Level.CONFIG));
                    assertEquals("TRACE", LineFormatter.levelName(java.util.logging.Level.FINEST));
                }));
    }

    /** An exception whose {@code toString} throws an {@link Error}. */
    static final class ThrowingToString extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String toString() {
            throw new AssertionError("boom");
        }
    }

    /** The common mistake: {@code getMessage} uses {@code this}, so toString recurses forever. */
    static final class RecursiveMessage extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public String getMessage() {
            return "failed: " + this;
        }
    }
}
