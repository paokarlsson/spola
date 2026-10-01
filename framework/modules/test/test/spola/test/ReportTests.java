package spola.test;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.RunnerTests.inner;
import static spola.test.RunnerTests.run;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.regex.Pattern;

/** The printed report: one line per test, stack traces of failures, a summary. */
final class ReportTests {

    private ReportTests() {
    }

    static Suite tests() {
        return suite("report",
                test("one line per test with its status", () -> {
                    var output = run(inner(
                            test("good", () -> { }),
                            test("bad", () -> Assertions.assertEquals(1, 2)),
                            test("later", () -> { }).skip("not yet"))).output();
                    assertTrue(Pattern.compile("(?m)^  ok      good \\(\\d+ ms\\)$").matcher(output).find(), output);
                    assertTrue(Pattern.compile("(?m)^  FAILED  bad \\(\\d+ ms\\)$").matcher(output).find(), output);
                    assertTrue(output.contains("\n  skipped later - not yet\n"), output);
                }),
                test("a failure shows the test's name, the expected and the actual value and a stack trace", () -> {
                    var output = run(suite("root", suite("group",
                            test("compares", () -> Assertions.assertEquals("expected text", "actual text"))))).output();
                    var failures = output.substring(output.indexOf("FAILURES (1):"));
                    assertTrue(failures.contains("\n1) root > group > compares\n"), output);
                    assertTrue(failures.contains("   spola.test.AssertionFailure: values differ\n"
                            + "     expected: \"expected text\"\n"
                            + "     actual:   \"actual text\"\n"), output);
                    assertTrue(failures.contains("\tat spola.test/spola.test.ReportTests.lambda$"), "trace points at the test: " + output);
                }),
                test("the summary counts tests by status and gives the time", () -> {
                    var result = run(inner(
                            test("a", () -> { }),
                            test("b", () -> { }),
                            test("c", () -> Assertions.fail("no")),
                            test("d", () -> { }).skip("later")));
                    var summary = result.output().strip().lines().toList().getLast();
                    assertTrue(Pattern.matches("inner: FAILED - 4 tests, 2 ok, 1 failed, 1 skipped, "
                            + "0 suite fixtures failed, in \\d+ ms", summary), summary);
                    assertEquals(4L, result.report().tests());
                    assertEquals(1L, result.report().count(Status.SKIPPED));
                }),
                test("a green run says OK", () -> {
                    var result = run(inner(test("a", () -> { })));
                    assertTrue(result.report().success());
                    assertTrue(result.output().strip().lines().toList().getLast().startsWith("inner: OK - 1 tests, 1 ok,"),
                            result.output());
                }),
                test("a run without a passing test is red", () -> {
                    var empty = run(inner());
                    assertFalse(empty.report().success(), "an empty suite is red");
                    assertTrue(empty.output().contains("inner: FAILED (no test passed) - 0 tests"), empty.output());
                    var allSkipped = run(inner(test("a", () -> { }).skip("off")));
                    assertFalse(allSkipped.report().success(), "a suite where every test is skipped is red");
                }),
                test("a result carries a failure exactly when it failed", () -> {
                    assertTrue(run(inner(test("a", () -> { }))).result("a").failure().isEmpty());
                    Assertions.assertThrows(IllegalArgumentException.class, () -> new Result(java.util.List.of("x"),
                            Result.Kind.TEST, Status.FAILED, java.time.Duration.ZERO, java.util.Optional.empty(),
                            java.util.Optional.empty()));
                }));
    }
}
