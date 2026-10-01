package spola.test;

import java.time.Duration;
import java.util.List;

/**
 * The outcome of a run: every test, in the order run, plus every failed suite fixture.
 *
 * @param results the results
 * @param time    the duration of the whole run
 */
public record Report(List<Result> results, Duration time) {

    /**
     * Copies the results.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public Report {
        results = List.copyOf(results);
        java.util.Objects.requireNonNull(time, "time");
    }

    /**
     * The number of tests (suite fixtures not included).
     *
     * @return the number of results of kind {@link Result.Kind#TEST}
     */
    public long tests() {
        return results.stream().filter(r -> r.kind() == Result.Kind.TEST).count();
    }

    /**
     * The number of tests with the given status (suite fixtures not included).
     *
     * @param status the status
     * @return how many tests ended with it
     */
    public long count(Status status) {
        return results.stream().filter(r -> r.kind() == Result.Kind.TEST && r.status() == status).count();
    }

    /**
     * The number of failed suite fixtures ({@code beforeAll}, {@code afterAll}).
     *
     * @return how many suite fixtures failed
     */
    public long fixtureFailures() {
        return results.stream().filter(r -> r.kind() == Result.Kind.SUITE_FIXTURE).count();
    }

    /**
     * Whether the run is green: at least one test ran, and no test or suite fixture failed.
     * Skipped tests do not make a run red; a run where every test was skipped does.
     *
     * @return {@code true} if green
     */
    public boolean success() {
        return count(Status.PASSED) > 0 && results.stream().noneMatch(r -> r.status() == Status.FAILED);
    }

    /**
     * The failed tests and suite fixtures.
     *
     * @return the results with status {@link Status#FAILED}, in the order run
     */
    public List<Result> failures() {
        return results.stream().filter(r -> r.status() == Status.FAILED).toList();
    }
}
