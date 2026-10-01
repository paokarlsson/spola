package spola.di;

/**
 * What a {@link Factory} receives: the dependencies its binding declared.
 *
 * <p>The container's resolver gives out only declared dependencies, and only while the factory
 * runs. Asking for anything else, or using the resolver after the factory returned, throws
 * {@link ResolutionException}. This keeps the dependency graph that {@link Container#create}
 * validated equal to the one used at runtime. Tests may implement this interface to call a
 * factory directly.
 */
public interface Resolver {

    /**
     * A declared dependency.
     *
     * @param key the dependency's key
     * @param <T> the dependency's type
     * @return the dependency, in the scope it was bound in
     * @throws ResolutionException if {@code key} was not declared, or creating it failed
     */
    <T> T get(Key<T> key);

    /**
     * A declared unqualified dependency; the same as {@code get(Key.of(type))}.
     *
     * @param type the dependency's type
     * @param <T>  the dependency's type
     * @return the dependency
     * @throws ResolutionException if it was not declared, or creating it failed
     */
    default <T> T get(Class<T> type) {
        return get(Key.of(type));
    }
}
