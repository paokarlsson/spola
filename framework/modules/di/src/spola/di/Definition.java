package spola.di;

import java.util.List;

/**
 * A finished binding, as the container sees it.
 *
 * @param key          what is bound
 * @param source       where {@code bind} was called, for messages
 * @param dependencies the declared dependencies, without duplicates
 * @param factory      creates the component; null if the binding never got a target
 * @param scope        the scope
 * @param owned        whether the container closes the instance (false for
 *                     {@code toInstance}: whoever made it closes it)
 */
record Definition<T>(Key<T> key, String source, List<Key<?>> dependencies, Factory<? extends T> factory,
                     Scope scope, boolean owned) {

    /** {@code key (bound at source)}. */
    String describe() {
        return key + " (bound at " + source + ")";
    }
}
