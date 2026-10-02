package spola.util;

/**
 * Like {@link java.util.function.Consumer}, but may throw a checked exception. Turn it into
 * a plain {@code Consumer} with {@link Unchecked}, which wraps checked exceptions.
 *
 * @param <T> the type of the input
 */
@FunctionalInterface
public interface ThrowingConsumer<T> {

    /**
     * Accepts a value.
     *
     * @param value the input
     * @throws Exception any exception
     */
    void accept(T value) throws Exception;
}
