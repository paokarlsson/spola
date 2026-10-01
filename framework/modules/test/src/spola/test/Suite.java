package spola.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A named group of tests and nested suites, with fixtures. Create one with
 * {@link Tests#suite(String, Test...)} and add fixtures with the methods below; every method
 * returns a new suite.
 *
 * <p>Order of execution: the {@code beforeAll} fixtures, then each child in order, then the
 * {@code afterAll} fixtures. For each test, the {@code beforeEach} fixtures of every enclosing
 * suite run first (outermost suite first), then the body, then the {@code afterEach} fixtures
 * (innermost suite first). Fixtures of one suite run in the order they were added.
 *
 * <p>Cleanup is guaranteed: every {@code afterEach} fixture runs after every test that was
 * started, even when a {@code beforeEach} fixture, the body or another {@code afterEach}
 * fixture failed, and every {@code afterAll} fixture runs even when a {@code beforeAll}
 * fixture or a test failed. Cleanup fixtures must therefore tolerate a setup that did not
 * complete. If a {@code beforeAll} fixture fails, the remaining {@code beforeAll} fixtures are
 * not run and every test in the suite is reported as skipped. The only exception is a test
 * that overruns its timeout and does not stop when interrupted: its thread is abandoned and
 * its {@code afterEach} fixtures run whenever (if ever) it finishes.
 *
 * @param name       the suite's name, unique within its parent
 * @param tests      the children, run in this order; their names are unique
 * @param beforeAll  run once before the first child
 * @param beforeEach run before every test in this suite and its nested suites
 * @param afterEach  run after every test in this suite and its nested suites
 * @param afterAll   run once after the last child
 * @param timeout    timeout of each test (and each fixture) in this suite and nested suites that
 *                   do not set their own; when empty the enclosing suite's timeout applies
 * @param skipReason when present nothing in the suite runs and every test is reported as skipped
 */
public record Suite(String name, List<Test> tests, List<Body> beforeAll, List<Body> beforeEach,
                    List<Body> afterEach, List<Body> afterAll, Optional<Duration> timeout,
                    Optional<String> skipReason) implements Test {

    /**
     * Validates and copies the components.
     *
     * @throws IllegalArgumentException if the name is blank or contains control characters, two
     *                                  children have the same name, the timeout is not positive
     *                                  or the skip reason is blank
     * @throws NullPointerException     if a component or an element is {@code null}
     */
    public Suite {
        Names.check(name);
        tests = List.copyOf(tests);
        beforeAll = List.copyOf(beforeAll);
        beforeEach = List.copyOf(beforeEach);
        afterEach = List.copyOf(afterEach);
        afterAll = List.copyOf(afterAll);
        timeout.ifPresent(Names::checkTimeout);
        skipReason.ifPresent(Names::checkReason);
        var names = new HashSet<String>();
        for (var test : tests) {
            if (!names.add(test.name())) {
                throw new IllegalArgumentException("suite " + name + " has two children named " + test.name());
            }
        }
    }

    /**
     * Adds a fixture that runs once before the first child of this suite.
     *
     * @param fixture the fixture
     * @return a copy with the fixture added after the existing ones
     */
    public Suite beforeAll(Body fixture) {
        return new Suite(name, tests, plus(beforeAll, fixture), beforeEach, afterEach, afterAll, timeout,
                skipReason);
    }

    /**
     * Adds a fixture that runs before every test in this suite and its nested suites.
     *
     * @param fixture the fixture
     * @return a copy with the fixture added after the existing ones
     */
    public Suite beforeEach(Body fixture) {
        return new Suite(name, tests, beforeAll, plus(beforeEach, fixture), afterEach, afterAll, timeout,
                skipReason);
    }

    /**
     * Adds a cleanup fixture that runs after every started test in this suite and its nested
     * suites, also when the test failed.
     *
     * @param fixture the fixture
     * @return a copy with the fixture added after the existing ones
     */
    public Suite afterEach(Body fixture) {
        return new Suite(name, tests, beforeAll, beforeEach, plus(afterEach, fixture), afterAll, timeout,
                skipReason);
    }

    /**
     * Adds a cleanup fixture that runs once after the last child of this suite, also when a
     * test or a {@code beforeAll} fixture failed.
     *
     * @param fixture the fixture
     * @return a copy with the fixture added after the existing ones
     */
    public Suite afterAll(Body fixture) {
        return new Suite(name, tests, beforeAll, beforeEach, afterEach, plus(afterAll, fixture), timeout,
                skipReason);
    }

    /**
     * Sets the timeout of each test and fixture in this suite and its nested suites, unless
     * they set their own.
     *
     * @param timeout a positive duration
     * @return a copy with the timeout
     */
    public Suite timeout(Duration timeout) {
        return new Suite(name, tests, beforeAll, beforeEach, afterEach, afterAll, Optional.of(timeout), skipReason);
    }

    /**
     * Skips the whole suite: no fixture or test in it runs, and every test is reported as skipped.
     *
     * @param reason why the suite is skipped
     * @return a skipped copy
     */
    public Suite skip(String reason) {
        return new Suite(name, tests, beforeAll, beforeEach, afterEach, afterAll, timeout, Optional.of(reason));
    }

    private static List<Body> plus(List<Body> list, Body fixture) {
        var result = new ArrayList<>(list);
        result.add(Objects.requireNonNull(fixture, "fixture"));
        return result;
    }
}
