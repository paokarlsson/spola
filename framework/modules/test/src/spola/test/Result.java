package spola.test;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one test, or of one failed suite fixture.
 *
 * @param path    the names from the root suite down to the test; for a suite fixture the
 *                suite's path followed by {@code (beforeAll)} or {@code (afterAll)}
 * @param kind    whether this is a test or a suite fixture
 * @param status  passed, failed or skipped
 * @param time    how long it ran (zero when skipped)
 * @param failure what failed, present exactly when the status is {@link Status#FAILED}
 * @param reason  why it was skipped, present exactly when the status is {@link Status#SKIPPED}
 */
public record Result(List<String> path, Kind kind, Status status, Duration time, Optional<Throwable> failure,
                     Optional<String> reason) {

    /** What a result is about. */
    public enum Kind {
        /** A test case. */
        TEST,
        /** A {@code beforeAll} or {@code afterAll} fixture of a suite; only failures are recorded. */
        SUITE_FIXTURE
    }

    /**
     * Validates and copies the components.
     *
     * @throws IllegalArgumentException if {@code failure} or {@code reason} does not match the status
     * @throws NullPointerException     if a component is {@code null}
     */
    public Result {
        path = List.copyOf(path);
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(time, "time");
        if (failure.isPresent() != (status == Status.FAILED)) {
            throw new IllegalArgumentException("a failure is present exactly when the status is FAILED");
        }
        if (reason.isPresent() != (status == Status.SKIPPED)) {
            throw new IllegalArgumentException("a reason is present exactly when the status is SKIPPED");
        }
    }

    /**
     * The full name, the path joined with {@code " > "}.
     *
     * @return for example {@code spola.test > assertions > assertEquals}
     */
    public String name() {
        return String.join(" > ", path);
    }
}
