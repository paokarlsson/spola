package spola.util;

/**
 * Like {@link java.util.function.Function}, but may throw a checked exception. Turn it into
 * a plain {@code Function} with {@link Unchecked}, which wraps checked exceptions.
 *
 * @param <T> the type of the input
 * @param <R> the type of the result
 */
@FunctionalInterface
public interface ThrowingFunction<T, R> {

    /**
     * Applies the function to a value.
     *
     * @param value the input
     * @return the result
     * @throws Exception any exception
     */
    R apply(T value) throws Exception;
}
