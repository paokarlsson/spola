package spola.test;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.RunnerTests.run;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;

/** Nested suites and the rules for names. */
final class StructureTests {

    private StructureTests() {
    }

    static Suite tests() {
        return suite("nested suites",
                test("tests in nested suites run in order and carry their full path", () -> {
                    var result = run(suite("a",
                            test("t1", () -> { }),
                            suite("b",
                                    suite("c", test("t2", () -> { })),
                                    test("t3", () -> { })),
                            test("t4", () -> { })));
                    assertInOrder(List.of("a > t1", "a > b > c > t2", "a > b > t3", "a > t4"),
                            result.report().results().stream().map(Result::name).toList());
                    assertTrue(result.report().success());
                }),
                test("the report indents by depth", () -> {
                    var output = run(suite("a", suite("b", test("t", () -> { })))).output();
                    assertTrue(output.startsWith("a\n  b\n    ok      t ("), output);
                }),
                test("a suite may hold the same test name as another suite", () ->
                        assertEquals(2L, run(suite("a", suite("x", test("t", () -> { })),
                                suite("y", test("t", () -> { })))).report().count(Status.PASSED))),
                test("sibling names must be unique", () -> assertThrows(IllegalArgumentException.class,
                        "suite s has two children named t",
                        () -> suite("s", test("t", () -> { }), suite("t")))),
                test("names must not be blank or contain line breaks", () -> {
                    assertThrows(IllegalArgumentException.class, () -> test(" ", () -> { }));
                    assertThrows(IllegalArgumentException.class, () -> suite(""));
                    assertThrows(IllegalArgumentException.class,
                            "a test or suite name must not contain line breaks or other control characters: a?b",
                            () -> test("a\nb", () -> { }));
                }),
                test("null parts are rejected when the tree is built", () -> {
                    assertThrows(NullPointerException.class, () -> test("t", null));
                    assertThrows(NullPointerException.class, () -> suite("s", (Test) null));
                    assertThrows(NullPointerException.class, () -> suite("s").beforeEach(null));
                    assertThrows(IllegalArgumentException.class, () -> test("t", () -> { }).skip(" "));
                }),
                test("suites are values: adding a fixture leaves the original unchanged", () -> {
                    var plain = suite("s", test("t", () -> { }));
                    var withFixture = plain.beforeEach(() -> { });
                    assertEquals(0, plain.beforeEach().size());
                    assertEquals(1, withFixture.beforeEach().size());
                }));
    }
}
