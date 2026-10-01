package spola.test;

/**
 * Code run by the test runner: a test body or a fixture. It may throw anything; a thrown
 * exception or error fails the test (or the fixture).
 */
public interface Body {

    /**
     * Runs the code.
     *
     * @throws Exception when the test or fixture fails; assertions throw {@link AssertionFailure}
     */
    void run() throws Exception;
}
