package spola.di;

/**
 * Creates a component from a {@link Resolver} that hands out the dependencies the binding
 * declared (see {@link BindingBuilder#to(Dependencies, Factory)}).
 *
 * <p>The nested interfaces {@link Of0} to {@link Of5} are the factories of the typed form
 * ({@code to(A.class, B.class, Service::new)}): they get the dependencies as arguments, so the
 * declared dependencies and the ones used are the same by construction.
 *
 * <p>Every factory may throw; the container wraps the exception in a
 * {@link ResolutionException} that names the dependency chain.
 *
 * @param <T> the component type
 */
@FunctionalInterface
public interface Factory<T> {

    /**
     * Creates the component.
     *
     * @param resolver gives out the declared dependencies, and only while this call runs
     * @return the component, not null
     * @throws Exception if the component cannot be created
     */
    T create(Resolver resolver) throws Exception;

    /**
     * A factory without dependencies.
     *
     * @param <T> the component type
     */
    @FunctionalInterface
    interface Of0<T> {
        /**
         * Creates the component.
         *
         * @return the component, not null
         * @throws Exception if the component cannot be created
         */
        T create() throws Exception;
    }

    /**
     * A factory with one dependency.
     *
     * @param <A> the dependency's type
     * @param <T> the component type
     */
    @FunctionalInterface
    interface Of1<A, T> {
        /**
         * Creates the component.
         *
         * @param a the dependency
         * @return the component, not null
         * @throws Exception if the component cannot be created
         */
        T create(A a) throws Exception;
    }

    /**
     * A factory with two dependencies.
     *
     * @param <A> the first dependency's type
     * @param <B> the second dependency's type
     * @param <T> the component type
     */
    @FunctionalInterface
    interface Of2<A, B, T> {
        /**
         * Creates the component.
         *
         * @param a the first dependency
         * @param b the second dependency
         * @return the component, not null
         * @throws Exception if the component cannot be created
         */
        T create(A a, B b) throws Exception;
    }

    /**
     * A factory with three dependencies.
     *
     * @param <A> the first dependency's type
     * @param <B> the second dependency's type
     * @param <C> the third dependency's type
     * @param <T> the component type
     */
    @FunctionalInterface
    interface Of3<A, B, C, T> {
        /**
         * Creates the component.
         *
         * @param a the first dependency
         * @param b the second dependency
         * @param c the third dependency
         * @return the component, not null
         * @throws Exception if the component cannot be created
         */
        T create(A a, B b, C c) throws Exception;
    }

    /**
     * A factory with four dependencies.
     *
     * @param <A> the first dependency's type
     * @param <B> the second dependency's type
     * @param <C> the third dependency's type
     * @param <D> the fourth dependency's type
     * @param <T> the component type
     */
    @FunctionalInterface
    interface Of4<A, B, C, D, T> {
        /**
         * Creates the component.
         *
         * @param a the first dependency
         * @param b the second dependency
         * @param c the third dependency
         * @param d the fourth dependency
         * @return the component, not null
         * @throws Exception if the component cannot be created
         */
        T create(A a, B b, C c, D d) throws Exception;
    }

    /**
     * A factory with five dependencies. More dependencies: use
     * {@link BindingBuilder#to(Dependencies, Factory)}.
     *
     * @param <A> the first dependency's type
     * @param <B> the second dependency's type
     * @param <C> the third dependency's type
     * @param <D> the fourth dependency's type
     * @param <E> the fifth dependency's type
     * @param <T> the component type
     */
    @FunctionalInterface
    interface Of5<A, B, C, D, E, T> {
        /**
         * Creates the component.
         *
         * @param a the first dependency
         * @param b the second dependency
         * @param c the third dependency
         * @param d the fourth dependency
         * @param e the fifth dependency
         * @return the component, not null
         * @throws Exception if the component cannot be created
         */
        T create(A a, B b, C c, D d, E e) throws Exception;
    }
}
