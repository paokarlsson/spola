package spola.test;

/**
 * The body of a parameterized test, run once per input
 * (see {@link Tests#parameterized(String, java.util.List, CaseBody)}).
 *
 * @param <T> the type of the inputs
 */
public interface CaseBody<T> {

    /**
     * Runs the test for one input.
     *
     * @param input the case's input
     * @throws Exception when the case fails
     */
    void run(T input) throws Exception;
}
