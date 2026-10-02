package spola.util;

/**
 * Like {@link java.util.function.Supplier}, but may throw a checked exception. Turn it into
 * a plain {@code Supplier} with {@link Unchecked}, which wraps checked exceptions.
 *
 * @param <T> the type of the result
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {

    /**
     * Computes the result.
     *
     * @return the result
     * @throws Exception any exception
     */
    T get() throws Exception;
}
