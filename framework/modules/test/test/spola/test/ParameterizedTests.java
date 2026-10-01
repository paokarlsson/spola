package spola.test;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertTrue;
import static spola.test.RunnerTests.run;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Parameterized tests: one test per input, each reported separately. */
final class ParameterizedTests {

    private ParameterizedTests() {
    }

    static Suite tests() {
        return suite("parameterization",
                test("three cases, one failing, report two ok and one failed", () -> {
                    var result = run(suite("root", parameterized("positive", List.of(1, -2, 3),
                            n -> Assertions.assertTrue(n > 0, "positive"))));
                    assertEquals(3L, result.report().tests());
                    assertEquals(2L, result.report().count(Status.PASSED));
                    assertEquals(1L, result.report().count(Status.FAILED));
                    assertInOrder(List.of(Status.PASSED, Status.FAILED, Status.PASSED), result.statuses());
                    assertEquals(List.of("root", "positive", "[2] -2"), result.result("[2] -2").path());
                    assertTrue(result.output().contains("    ok      [1] 1 ("), result.output());
                    assertTrue(result.output().contains("    FAILED  [2] -2 ("), result.output());
                    assertTrue(result.output().contains("    ok      [3] 3 ("), result.output());
                    assertTrue(result.output().contains("root: FAILED - 3 tests, 2 ok, 1 failed, 0 skipped"),
                            result.output());
                }),
                test("each case runs with its own input, in order", () -> {
                    var seen = Collections.synchronizedList(new ArrayList<String>());
                    run(suite("root", parameterized("cases", List.of("a", "b", "c"), seen::add)));
                    assertInOrder(List.of("a", "b", "c"), seen);
                }),
                test("a label names the cases; control characters are escaped", () -> {
                    var result = run(suite("root", parameterized("cases", List.of("x\ny", "z"),
                            s -> "input " + s, s -> { })));
                    assertEquals(List.of("[1] input x\\ny", "[2] input z"),
                            result.report().results().stream().map(r -> r.path().getLast()).toList());
                }),
                test("equal inputs still give distinct cases; null inputs are allowed", () -> {
                    var result = run(suite("root", parameterized("cases", Arrays.asList("same", "same", null),
                            s -> { })));
                    assertEquals(List.of("[1] same", "[2] same", "[3] null"),
                            result.report().results().stream().map(r -> r.path().getLast()).toList());
                }),
                test("the cases form a suite that takes fixtures like any other", () -> {
                    var log = Collections.synchronizedList(new ArrayList<String>());
                    run(suite("root", parameterized("cases", List.of(1, 2), n -> log.add("case " + n))
                            .beforeEach(() -> log.add("before"))));
                    assertInOrder(List.of("before", "case 1", "before", "case 2"), log);
                }));
    }
}
