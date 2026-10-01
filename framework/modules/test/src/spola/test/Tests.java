package spola.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Factory methods for tests and suites, meant to be imported statically:
 *
 * <pre>{@code
 * public static Suite suite() {
 *     return suite("strings",
 *             test("concatenation", () -> assertEquals("ab", "a" + "b")),
 *             parameterized("blank", List.of("", " ", "\t"), s -> assertTrue(s.isBlank())));
 * }
 * }</pre>
 */
public final class Tests {

    private Tests() {
    }

    /**
     * A test.
     *
     * @param name the test's name, unique within its suite
     * @param body the code under test
     * @return the test, without its own timeout
     */
    public static TestCase test(String name, Body body) {
        return new TestCase(name, body, Optional.empty(), Optional.empty());
    }

    /**
     * A suite of tests and nested suites.
     *
     * @param name  the suite's name
     * @param tests the children, run in this order
     * @return the suite, without fixtures
     */
    public static Suite suite(String name, Test... tests) {
        return suite(name, List.of(tests));
    }

    /**
     * A suite of tests and nested suites.
     *
     * @param name  the suite's name
     * @param tests the children, run in this order
     * @return the suite, without fixtures
     */
    public static Suite suite(String name, List<? extends Test> tests) {
        return new Suite(name, List.copyOf(tests), List.of(), List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty());
    }

    /**
     * A parameterized test: a suite with one test per input, each reported separately and named
     * {@code [i] input} (from 1, with {@link String#valueOf(Object)} of the input).
     *
     * @param name   the suite's name
     * @param inputs the inputs, one test each
     * @param body   the test, run once per input
     * @param <T>    the type of the inputs
     * @return the suite of cases; add fixtures or a timeout to it like to any suite
     */
    public static <T> Suite parameterized(String name, List<? extends T> inputs, CaseBody<? super T> body) {
        return parameterized(name, inputs, String::valueOf, body);
    }

    /**
     * A parameterized test: a suite with one test per input, each reported separately and named
     * {@code [i] label} (from 1).
     *
     * @param name   the suite's name
     * @param inputs the inputs, one test each
     * @param label  a short description of an input for the case's name; control characters in
     *               it are shown escaped
     * @param body   the test, run once per input
     * @param <T>    the type of the inputs
     * @return the suite of cases; add fixtures or a timeout to it like to any suite
     */
    public static <T> Suite parameterized(String name, List<? extends T> inputs, Function<? super T, String> label,
                                          CaseBody<? super T> body) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(body, "body");
        var cases = new ArrayList<TestCase>();
        int index = 1;
        for (T input : inputs) {
            var caseName = "[" + index++ + "] " + Values.escapeControls(String.valueOf(label.apply(input)));
            cases.add(test(caseName.strip(), () -> body.run(input)));
        }
        return suite(name, cases);
    }
}
