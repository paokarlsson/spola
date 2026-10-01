package spola.test;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Runs a {@link Suite} and prints the report: one line per test as it finishes, then the
 * stack trace of every failure, then a summary with counts and time.
 *
 * <p>Tests run one at a time, in order. Each test (its {@code beforeEach} fixtures, its body
 * and its {@code afterEach} fixtures) runs on a fresh platform daemon thread, and so does each
 * {@code beforeAll} and {@code afterAll} fixture; the runner waits for it at most the timeout
 * (see {@link TestCase#timeout()} and {@link Suite#timeout()}, default {@link #DEFAULT_TIMEOUT}).
 * A test that overruns is interrupted and failed; if it stops within a short grace period
 * its cleanup has run before the next test starts, otherwise its thread is abandoned and the
 * run goes on. The runner itself never waits longer than timeout plus grace for anything.
 */
public final class Runner {

    /** The timeout of a test or fixture when neither it nor an enclosing suite sets one. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** How long a test that overran its timeout gets to stop after being interrupted. */
    static final Duration GRACE = Duration.ofSeconds(1);

    private final PrintStream out;
    private final Duration grace;
    private final List<Result> results = new ArrayList<>();

    private Runner(PrintStream out, Duration grace) {
        this.out = out;
        this.grace = grace;
    }

    /**
     * Runs every test in {@code suite} and prints the report to {@code out}.
     *
     * @param suite the root suite
     * @param out   receives the report
     * @return the results; {@link Report#success()} tells whether the run is green
     */
    public static Report run(Suite suite, PrintStream out) {
        return run(suite, out, DEFAULT_TIMEOUT, GRACE);
    }

    /** {@link #run(Suite, PrintStream)} with another default timeout and grace period (for tests). */
    static Report run(Suite suite, PrintStream out, Duration defaultTimeout, Duration grace) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(out, "out");
        var runner = new Runner(out, grace);
        long start = System.nanoTime();
        runner.runSuite(suite, new Scope(List.of(), List.of(), List.of(), defaultTimeout));
        var report = new Report(runner.results, Duration.ofNanos(System.nanoTime() - start));
        runner.printSummary(suite.name(), report);
        return report;
    }

    /**
     * Where a node runs: the path of its suite, the {@code beforeEach} fixtures to run before
     * each test (outermost first), the {@code afterEach} fixtures to run after (innermost
     * first) and the timeout in force.
     */
    private record Scope(List<String> path, List<Body> beforeEach, List<Body> afterEach, Duration timeout) {

        Scope enter(Suite suite) {
            var after = new ArrayList<>(suite.afterEach());
            after.addAll(afterEach);
            return new Scope(append(path, suite.name()),
                    Stream.concat(beforeEach.stream(), suite.beforeEach().stream()).toList(),
                    List.copyOf(after),
                    suite.timeout().orElse(timeout));
        }

        String indent() {
            return "  ".repeat(path.size());
        }
    }

    private void runSuite(Suite suite, Scope parent) {
        var scope = parent.enter(suite);
        out.println(parent.indent() + suite.name());
        if (suite.skipReason().isPresent()) {
            skipAll(suite.tests(), scope, suite.skipReason().get());
            return;
        }
        String abort = null;
        for (var fixture : suite.beforeAll()) {
            var failure = bounded(fixture, scope.timeout(), String.join(" > ", scope.path()) + " (beforeAll)");
            if (failure.isPresent()) {
                fixtureFailed(scope, "(beforeAll)", failure.get());
                abort = "a beforeAll fixture of " + suite.name() + " failed";
                break;
            }
        }
        if (abort == null) {
            for (var test : suite.tests()) {
                switch (test) {
                    case TestCase single -> runTest(single, scope);
                    case Suite nested -> runSuite(nested, scope);
                }
            }
        } else {
            skipAll(suite.tests(), scope, abort);
        }
        for (var fixture : suite.afterAll()) {
            bounded(fixture, scope.timeout(), String.join(" > ", scope.path()) + " (afterAll)")
                    .ifPresent(failure -> fixtureFailed(scope, "(afterAll)", failure));
        }
    }

    private void runTest(TestCase test, Scope scope) {
        var path = append(scope.path(), test.name());
        if (test.skipReason().isPresent()) {
            skipped(path, scope, test.skipReason().get());
            return;
        }
        Body whole = () -> {
            Throwable failure = null;
            try {
                for (var fixture : scope.beforeEach()) {
                    fixture.run();
                }
                test.body().run();
            } catch (Throwable t) {
                failure = t;
            }
            for (var fixture : scope.afterEach()) {
                try {
                    fixture.run();
                } catch (Throwable t) {
                    if (failure == null) {
                        failure = t;
                    } else if (failure != t) {
                        failure.addSuppressed(t);
                    }
                }
            }
            rethrow(failure);
        };
        long start = System.nanoTime();
        var failure = bounded(whole, test.timeout().orElse(scope.timeout()), String.join(" > ", path));
        var time = Duration.ofNanos(System.nanoTime() - start);
        var status = failure.isPresent() ? Status.FAILED : Status.PASSED;
        results.add(new Result(path, Result.Kind.TEST, status, time, failure, Optional.empty()));
        out.println(scope.indent() + (status == Status.PASSED ? "ok      " : "FAILED  ") + test.name()
                + " (" + time.toMillis() + " ms)");
    }

    /** Lets {@code whole} above rethrow whatever the test threw, checked or not. */
    private static void rethrow(Throwable failure) throws Exception {
        switch (failure) {
            case null -> { }
            case Exception e -> throw e;
            case Error e -> throw e;
            default -> throw new IllegalStateException("unexpected throwable", failure);
        }
    }

    private void skipAll(List<Test> tests, Scope scope, String reason) {
        for (var test : tests) {
            switch (test) {
                case TestCase single -> skipped(append(scope.path(), single.name()), scope, reason);
                case Suite nested -> {
                    out.println(scope.indent() + nested.name());
                    skipAll(nested.tests(), scope.enter(nested), reason);
                }
            }
        }
    }

    private void skipped(List<String> path, Scope scope, String reason) {
        results.add(new Result(path, Result.Kind.TEST, Status.SKIPPED, Duration.ZERO, Optional.empty(),
                Optional.of(reason)));
        out.println(scope.indent() + "skipped " + path.getLast() + " - " + reason);
    }

    private void fixtureFailed(Scope scope, String which, Throwable failure) {
        results.add(new Result(append(scope.path(), which), Result.Kind.SUITE_FIXTURE, Status.FAILED, Duration.ZERO,
                Optional.of(failure), Optional.empty()));
        out.println(scope.indent() + "FAILED  " + which);
    }

    /**
     * Runs {@code body} on a fresh platform daemon thread and waits at most {@code timeout}
     * (plus the grace period if it has to be interrupted). Platform rather than virtual
     * threads: a virtual thread that spins without blocking would keep its carrier thread,
     * and enough of them would starve every later test.
     *
     * @return what {@code body} threw, or a {@link TimedOut}; empty if it completed
     */
    private Optional<Throwable> bounded(Body body, Duration timeout, String name) {
        var thrown = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().daemon().name("spola-test " + name).unstarted(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        thread.start();
        try {
            if (thread.join(timeout)) {
                return Optional.ofNullable(thrown.get());
            }
            var stuckAt = thread.getStackTrace();
            thread.interrupt();
            boolean stopped = thread.join(grace);
            var timedOut = new TimedOut(timeout, stopped);
            timedOut.setStackTrace(stuckAt);
            if (stopped && thrown.get() != null) {
                timedOut.addSuppressed(thrown.get());
            }
            return Optional.of(timedOut);
        } catch (InterruptedException e) {
            // The runner itself was interrupted: fail this test, keep the flag so the caller sees it.
            thread.interrupt();
            Thread.currentThread().interrupt();
            return Optional.of(e);
        }
    }

    private void printSummary(String suite, Report report) {
        var failures = report.failures();
        if (!failures.isEmpty()) {
            out.println();
            out.println("FAILURES (" + failures.size() + "):");
            int number = 1;
            for (var failure : failures) {
                out.println();
                out.println(number++ + ") " + failure.name());
                var trace = new StringWriter();
                failure.failure().orElseThrow().printStackTrace(new PrintWriter(trace));
                trace.toString().lines().forEach(line -> out.println("   " + line));
            }
            out.println();
        }
        var verdict = report.success() ? "OK" : failures.isEmpty() ? "FAILED (no test passed)" : "FAILED";
        out.println(suite + ": " + verdict + " - " + report.tests() + " tests, " + report.count(Status.PASSED)
                + " ok, " + report.count(Status.FAILED) + " failed, " + report.count(Status.SKIPPED) + " skipped, "
                + report.fixtureFailures() + " suite fixtures failed, in " + report.time().toMillis() + " ms");
    }

    private static List<String> append(List<String> path, String name) {
        return Stream.concat(path.stream(), Stream.of(name)).toList();
    }
}
