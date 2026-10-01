package spola.test;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A single test: a name and a body. Create one with {@link Tests#test(String, Body)}.
 *
 * @param name       the test's name, unique within its suite
 * @param body       the code under test; throwing fails the test
 * @param timeout    the test's own timeout; when empty the nearest enclosing suite's timeout,
 *                   or else {@link Runner#DEFAULT_TIMEOUT}, applies
 * @param skipReason when present the test is not run and is reported as skipped
 */
public record TestCase(String name, Body body, Optional<Duration> timeout, Optional<String> skipReason)
        implements Test {

    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if the name is blank or contains control characters, the
     *                                  timeout is not positive or the skip reason is blank
     * @throws NullPointerException     if a component is {@code null}
     */
    public TestCase {
        Names.check(name);
        Objects.requireNonNull(body, "body");
        timeout.ifPresent(Names::checkTimeout);
        skipReason.ifPresent(Names::checkReason);
    }

    /**
     * This test with its own timeout. The whole test (the {@code beforeEach} fixtures, the
     * body and the {@code afterEach} fixtures) must finish within it.
     *
     * @param timeout a positive duration
     * @return a copy with the timeout
     */
    public TestCase timeout(Duration timeout) {
        return new TestCase(name, body, Optional.of(timeout), skipReason);
    }

    /**
     * This test, skipped: it is reported as skipped with the reason and neither the body nor
     * any fixture runs for it.
     *
     * @param reason why the test is skipped
     * @return a skipped copy
     */
    public TestCase skip(String reason) {
        return new TestCase(name, body, timeout, Optional.of(reason));
    }
}
