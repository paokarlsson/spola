package spola.test;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertTrue;
import static spola.test.RunnerTests.inner;
import static spola.test.RunnerTests.run;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Timeouts per test and per suite: an overrunning test fails and the run goes on. */
final class TimeoutTests {

    private TimeoutTests() {
    }

    private static final Duration SHORT = Duration.ofMillis(200);

    static Suite tests() {
        return suite("timeout",
                test("a test that overruns its timeout fails, and the remaining tests still run", () -> {
                    var log = Collections.synchronizedList(new ArrayList<String>());
                    var result = run(inner(
                            test("sleeps", () -> Thread.sleep(Duration.ofSeconds(30))).timeout(SHORT),
                            test("next", () -> log.add("next ran"))).afterEach(() -> log.add("cleanup")));
                    assertInOrder(List.of(Status.FAILED, Status.PASSED), result.statuses());
                    var failure = result.result("sleeps").failure().orElseThrow();
                    assertTrue(failure instanceof TimedOut, "failure is a timeout: " + failure);
                    assertEquals("timed out after 200 ms (interrupted and stopped)", failure.getMessage());
                    assertTrue(failure.getSuppressed()[0] instanceof InterruptedException,
                            "what the test threw when interrupted is attached");
                    // The interrupted test stopped within the grace period, so its cleanup ran
                    // before the next test started.
                    assertInOrder(List.of("cleanup", "next ran", "cleanup"), log);
                    assertTrue(result.result("sleeps").time().compareTo(Duration.ofSeconds(5)) < 0,
                            "the runner did not wait for the test");
                }),
                test("the failure's stack trace shows where the test was stuck", () -> {
                    var result = run(inner(test("sleeps", () -> Thread.sleep(Duration.ofSeconds(30))).timeout(SHORT)));
                    var trace = List.of(result.result("sleeps").failure().orElseThrow().getStackTrace());
                    assertTrue(trace.stream().anyMatch(frame -> frame.getClassName().equals(TimeoutTests.class.getName())),
                            "trace includes the test's lambda: " + trace);
                    assertTrue(result.output().contains("timed out after 200 ms"), result.output());
                }),
                test("a test that ignores interrupts is abandoned and the run goes on", () -> {
                    var stop = new AtomicBoolean();
                    try {
                        var result = run(inner(
                                test("spins", () -> {
                                    while (!stop.get()) {
                                        Thread.onSpinWait();
                                    }
                                }).timeout(SHORT),
                                test("next", () -> { })), Runner.DEFAULT_TIMEOUT, Duration.ofMillis(100));
                        assertInOrder(List.of(Status.FAILED, Status.PASSED), result.statuses());
                        assertEquals("timed out after 200 ms (did not stop when interrupted; its thread is left"
                                        + " running as a daemon and its cleanup fixtures run only if it ever finishes)",
                                result.result("spins").failure().orElseThrow().getMessage());
                        assertTrue(result.report().time().compareTo(Duration.ofSeconds(5)) < 0,
                                "the run took " + result.report().time());
                    } finally {
                        stop.set(true);
                    }
                }),
                test("a suite's timeout applies to nested tests; a test's own timeout wins", () -> {
                    var result = run(suite("outer",
                            suite("nested",
                                    test("inherits", () -> Thread.sleep(Duration.ofSeconds(30))),
                                    test("own, longer", () -> Thread.sleep(Duration.ofMillis(400)))
                                            .timeout(Duration.ofSeconds(20))))
                            .timeout(SHORT));
                    assertEquals("timed out after 200 ms (interrupted and stopped)",
                            result.result("inherits").failure().orElseThrow().getMessage());
                    assertEquals(Status.PASSED, result.result("own, longer").status());
                }),
                test("the runner's default timeout applies when nothing sets one", () -> {
                    var result = run(inner(test("sleeps", () -> Thread.sleep(Duration.ofSeconds(30)))),
                            SHORT, Runner.GRACE);
                    assertEquals("timed out after 200 ms (interrupted and stopped)",
                            result.result("sleeps").failure().orElseThrow().getMessage());
                }),
                test("a suite fixture that overruns fails the suite fixture and skips the tests", () -> {
                    var result = run(inner(test("t", () -> { }))
                            .beforeAll(() -> Thread.sleep(Duration.ofSeconds(30)))
                            .timeout(SHORT));
                    assertEquals("timed out after 200 ms (interrupted and stopped)",
                            result.result("inner", "(beforeAll)").failure().orElseThrow().getMessage());
                    assertEquals(Status.SKIPPED, result.result("t").status());
                }),
                test("timeouts must be positive", () -> {
                    Assertions.assertThrows(IllegalArgumentException.class,
                            () -> test("t", () -> { }).timeout(Duration.ZERO));
                    Assertions.assertThrows(IllegalArgumentException.class,
                            () -> inner().timeout(Duration.ofMillis(-1)));
                }));
    }
}
