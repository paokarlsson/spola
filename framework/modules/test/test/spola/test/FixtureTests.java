package spola.test;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertTrue;
import static spola.test.RunnerTests.inner;
import static spola.test.RunnerTests.run;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Before/after fixtures per test and per suite, and that cleanup always runs. */
final class FixtureTests {

    private FixtureTests() {
    }

    /** A thread-safe event log: tests run on their own threads. */
    private static List<String> log() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    private static Body record(List<String> log, String event) {
        return () -> log.add(event);
    }

    private static Body boom(List<String> log, String event) {
        return () -> {
            log.add(event);
            throw new IllegalStateException(event + " failed");
        };
    }

    static Suite tests() {
        return suite("fixtures",
                test("fixtures run in order around each test, outer suite first", () -> {
                    var log = log();
                    var tree = suite("outer",
                            test("t1", record(log, "t1")),
                            suite("nested", test("t2", record(log, "t2")))
                                    .beforeAll(record(log, "nested beforeAll"))
                                    .beforeEach(record(log, "nested beforeEach"))
                                    .afterEach(record(log, "nested afterEach"))
                                    .afterAll(record(log, "nested afterAll")))
                            .beforeAll(record(log, "outer beforeAll"))
                            .beforeEach(record(log, "outer beforeEach 1"))
                            .beforeEach(record(log, "outer beforeEach 2"))
                            .afterEach(record(log, "outer afterEach 1"))
                            .afterEach(record(log, "outer afterEach 2"))
                            .afterAll(record(log, "outer afterAll"));
                    var result = run(tree);
                    assertTrue(result.report().success(), result.output());
                    assertInOrder(List.of(
                            "outer beforeAll",
                            "outer beforeEach 1", "outer beforeEach 2", "t1", "outer afterEach 1", "outer afterEach 2",
                            "nested beforeAll",
                            "outer beforeEach 1", "outer beforeEach 2", "nested beforeEach", "t2",
                            "nested afterEach", "outer afterEach 1", "outer afterEach 2",
                            "nested afterAll",
                            "outer afterAll"), log);
                }),
                test("cleanup runs when the test throws after a fixture opened a resource", () -> {
                    var file = Files.createTempFile("spola-test-fixture-", ".tmp");
                    var channel = new AtomicReference<FileChannel>();
                    var tree = inner(test("writes and throws", () -> {
                        assertTrue(channel.get().isOpen(), "the fixture opened the resource");
                        throw new IllegalStateException("test failed with the resource open");
                    }))
                            .beforeEach(() -> channel.set(FileChannel.open(file, StandardOpenOption.WRITE)))
                            .afterEach(() -> {
                                channel.get().close();
                                Files.delete(file);
                            });
                    try {
                        var result = run(tree);
                        assertEquals(Status.FAILED, result.result("writes and throws").status());
                        assertEquals("test failed with the resource open",
                                result.result("writes and throws").failure().orElseThrow().getMessage());
                        assertFalse(channel.get().isOpen(), "the afterEach fixture closed the resource");
                        assertFalse(Files.exists(file), "the afterEach fixture deleted the file");
                    } finally {
                        Files.deleteIfExists(file);
                    }
                }),
                test("cleanup runs when a beforeEach fixture fails; the body does not", () -> {
                    var log = log();
                    var result = run(suite("outer",
                            suite("nested", test("t", record(log, "body")))
                                    .beforeEach(boom(log, "nested beforeEach"))
                                    .afterEach(record(log, "nested afterEach")))
                            .beforeEach(record(log, "outer beforeEach"))
                            .afterEach(record(log, "outer afterEach")));
                    assertEquals(Status.FAILED, result.result("t").status());
                    assertEquals("nested beforeEach failed", result.result("t").failure().orElseThrow().getMessage());
                    assertInOrder(List.of("outer beforeEach", "nested beforeEach", "nested afterEach", "outer afterEach"),
                            log);
                }),
                test("every afterEach fixture runs even if one fails, and fails the test", () -> {
                    var log = log();
                    var result = run(inner(test("t", record(log, "body")))
                            .afterEach(boom(log, "after 1"))
                            .afterEach(record(log, "after 2")));
                    assertInOrder(List.of("body", "after 1", "after 2"), log);
                    assertEquals(Status.FAILED, result.result("t").status());
                    assertEquals("after 1 failed", result.result("t").failure().orElseThrow().getMessage());
                }),
                test("an afterEach failure after a failed body is attached as suppressed", () -> {
                    var log = log();
                    var result = run(inner(test("t", boom(log, "body"))).afterEach(boom(log, "after")));
                    var failure = result.result("t").failure().orElseThrow();
                    assertEquals("body failed", failure.getMessage());
                    assertEquals(1, failure.getSuppressed().length);
                    assertEquals("after failed", failure.getSuppressed()[0].getMessage());
                }),
                test("a failed beforeAll skips the suite's tests, stops later beforeAll, still runs afterAll", () -> {
                    var log = log();
                    var result = run(suite("outer",
                            suite("broken",
                                    test("a", record(log, "a")),
                                    suite("deeper", test("b", record(log, "b"))))
                                    .beforeAll(boom(log, "beforeAll 1"))
                                    .beforeAll(record(log, "beforeAll 2"))
                                    .beforeEach(record(log, "beforeEach"))
                                    .afterAll(record(log, "afterAll 1"))
                                    .afterAll(record(log, "afterAll 2")),
                            test("c", record(log, "c"))));
                    assertInOrder(List.of("beforeAll 1", "afterAll 1", "afterAll 2", "c"), log);
                    assertInOrder(List.of(Status.SKIPPED, Status.SKIPPED, Status.PASSED), result.statuses());
                    assertEquals("a beforeAll fixture of broken failed",
                            result.result("deeper", "b").reason().orElseThrow());
                    var fixture = result.result("broken", "(beforeAll)");
                    assertEquals(Result.Kind.SUITE_FIXTURE, fixture.kind());
                    assertEquals("beforeAll 1 failed", fixture.failure().orElseThrow().getMessage());
                    assertFalse(result.report().success(), "a failed suite fixture makes the run red");
                }),
                test("a failed afterAll is reported and makes the run red; every afterAll runs", () -> {
                    var log = log();
                    var result = run(inner(test("t", record(log, "t")))
                            .afterAll(boom(log, "afterAll 1"))
                            .afterAll(record(log, "afterAll 2")));
                    assertInOrder(List.of("t", "afterAll 1", "afterAll 2"), log);
                    assertEquals(Status.PASSED, result.result("t").status());
                    assertEquals(Status.FAILED, result.result("inner", "(afterAll)").status());
                    assertEquals(1L, result.report().fixtureFailures());
                    assertFalse(result.report().success());
                }),
                test("a fixture may throw a checked exception", () -> {
                    var result = run(inner(test("t", () -> { })).beforeEach(() -> {
                        throw new java.io.IOException("no disk");
                    }));
                    assertTrue(result.result("t").failure().orElseThrow() instanceof java.io.IOException);
                }),
                test("a skipped test runs no fixture; a skipped suite runs nothing", () -> {
                    var log = log();
                    var result = run(suite("outer",
                            test("skipped", record(log, "skipped body")).skip("not today"),
                            suite("off", test("x", record(log, "x")), suite("deeper", test("y", record(log, "y"))))
                                    .beforeAll(record(log, "off beforeAll"))
                                    .afterAll(record(log, "off afterAll"))
                                    .skip("switched off"),
                            test("runs", record(log, "runs")))
                            .beforeEach(record(log, "beforeEach"))
                            .afterEach(record(log, "afterEach")));
                    assertInOrder(List.of("beforeEach", "runs", "afterEach"), log);
                    assertInOrder(List.of(Status.SKIPPED, Status.SKIPPED, Status.SKIPPED, Status.PASSED),
                            result.statuses());
                    assertEquals("not today", result.result("skipped").reason().orElseThrow());
                    assertEquals("switched off", result.result("deeper", "y").reason().orElseThrow());
                    assertTrue(result.report().success(), "skipped tests do not make a run red");
                }));
    }
}
