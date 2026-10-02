package spola.util;

/**
 * Like {@link Runnable}, but may throw a checked exception. Turn it into
 * a plain {@code Runnable} with {@link Unchecked}, which wraps checked exceptions.
 */
@FunctionalInterface
public interface ThrowingRunnable {

    /**
     * Runs the action.
     *
     * @throws Exception any exception
     */
    void run() throws Exception;
}
