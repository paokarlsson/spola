package spola.test;


import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * The tests of {@code spola.test}, run by the runner itself on every build (the entry named
 * in {@code Build.java}). Most tests run a small suite with a nested {@link Runner} and check
 * its {@link Report} and printed output.
 */
public final class RunnerTests {

    private RunnerTests() {
    }

    /**
     * Every test of the module.
     *
     * @return the root suite
     */
    public static Suite suite() {
        return Tests.suite("spola.test",
                AssertionsTests.tests(),
                FixtureTests.tests(),
                ParameterizedTests.tests(),
                TimeoutTests.tests(),
                StructureTests.tests(),
                ReportTests.tests(),
                ToolTests.tests());
    }

    /** A finished inner run: its report and everything it printed. */
    record Run(Report report, String output) {

        /** The single result whose path ends with {@code name}. */
        Result result(String... name) {
            var suffix = List.of(name);
            var matches = report.results().stream()
                    .filter(r -> r.path().size() >= suffix.size()
                            && r.path().subList(r.path().size() - suffix.size(), r.path().size()).equals(suffix))
                    .toList();
            if (matches.size() != 1) {
                throw new AssertionFailure("expected one result named " + suffix + " but found " + matches.size()
                        + " in " + report.results().stream().map(Result::name).toList());
            }
            return matches.getFirst();
        }

        /** The statuses of all tests, in the order run. */
        List<Status> statuses() {
            return report.results().stream().filter(r -> r.kind() == Result.Kind.TEST).map(Result::status).toList();
        }
    }

    /** Runs {@code suite} with a nested runner whose output is captured. */
    static Run run(Suite suite) {
        return run(suite, Runner.DEFAULT_TIMEOUT, Runner.GRACE);
    }

    /** Runs {@code suite} with a nested runner, the given default timeout and grace period. */
    static Run run(Suite suite, Duration defaultTimeout, Duration grace) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            var report = Runner.run(suite, out, defaultTimeout, grace);
            return new Run(report, bytes.toString(StandardCharsets.UTF_8));
        }
    }

    /** A suite named {@code inner} with the given tests, for inner runs. */
    static Suite inner(Test... tests) {
        return Tests.suite("inner", tests);
    }
}
