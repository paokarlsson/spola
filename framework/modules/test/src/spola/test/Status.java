package spola.test;

/** The outcome of a test or a suite fixture. */
public enum Status {
    /** The test ran and nothing failed. */
    PASSED,
    /** The test, one of its fixtures or a suite fixture threw, or the test overran its timeout. */
    FAILED,
    /** The test was not run (skipped explicitly, or a {@code beforeAll} fixture failed). */
    SKIPPED
}
