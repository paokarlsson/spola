package spola.di;

import java.util.List;
import java.util.Objects;

/**
 * Gives a bound key its target: a factory together with the dependencies it needs, or a ready
 * instance. Exactly one {@code to...} call per binding.
 *
 * <p><b>Typed form (preferred).</b> The dependencies are listed as types or keys, and the
 * factory receives them as arguments. The compiler checks that each argument matches its key,
 * and the factory cannot reach anything it did not declare:
 *
 * <pre>{@code
 * binder.bind(OrderService.class).to(OrderRepo.class, Clock.class, OrderService::new);
 * binder.bind(Checkout.class).to(Key.of(Gateway.class, Region.EU), Key.of(Clock.class), Checkout::new);
 * }</pre>
 *
 * <p>All dependencies of one call are either {@code Class} objects or {@code Key}s; for a mix,
 * write the unqualified ones as {@code Key.of(type)}.
 *
 * <p><b>Resolver form.</b> For more than five dependencies, or a factory that picks what to use
 * at runtime, the factory receives a {@link Resolver} and the dependencies are declared with
 * {@link Dependencies}. The resolver refuses keys that were not declared:
 *
 * <pre>{@code
 * binder.bind(OrderService.class).to(Dependencies.of(OrderRepo.class),
 *         r -> new OrderService(r.get(OrderRepo.class)));
 * }</pre>
 *
 * @param <T> the component type
 */
public final class BindingBuilder<T> {

    private final Binder binder;
    private final Registration<T> registration;

    BindingBuilder(Binder binder, Registration<T> registration) {
        this.binder = binder;
        this.registration = registration;
    }

    /**
     * Binds to a factory without dependencies.
     *
     * @param factory creates the component
     * @return the binding, to choose a scope
     */
    public Binding to(Factory.Of0<? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return define(List.of(), r -> factory.create());
    }

    /**
     * Binds to a factory with one unqualified dependency.
     *
     * @param a       the dependency's type
     * @param factory creates the component from the dependency
     * @param <A>     the dependency's type
     * @return the binding, to choose a scope
     */
    public <A> Binding to(Class<A> a, Factory.Of1<? super A, ? extends T> factory) {
        return to(Key.of(a), factory);
    }

    /**
     * Binds to a factory with one dependency.
     *
     * @param a       the dependency's key
     * @param factory creates the component from the dependency
     * @param <A>     the dependency's type
     * @return the binding, to choose a scope
     */
    public <A> Binding to(Key<A> a, Factory.Of1<? super A, ? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return define(List.of(a), r -> factory.create(r.get(a)));
    }

    /**
     * Binds to a factory with two unqualified dependencies.
     *
     * @param a       the first dependency's type
     * @param b       the second dependency's type
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B> Binding to(Class<A> a, Class<B> b, Factory.Of2<? super A, ? super B, ? extends T> factory) {
        return to(Key.of(a), Key.of(b), factory);
    }

    /**
     * Binds to a factory with two dependencies.
     *
     * @param a       the first dependency's key
     * @param b       the second dependency's key
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B> Binding to(Key<A> a, Key<B> b, Factory.Of2<? super A, ? super B, ? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return define(List.of(a, b), r -> factory.create(r.get(a), r.get(b)));
    }

    /**
     * Binds to a factory with three unqualified dependencies.
     *
     * @param a       the first dependency's type
     * @param b       the second dependency's type
     * @param c       the third dependency's type
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @param <C>     the third dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B, C> Binding to(Class<A> a, Class<B> b, Class<C> c,
                                Factory.Of3<? super A, ? super B, ? super C, ? extends T> factory) {
        return to(Key.of(a), Key.of(b), Key.of(c), factory);
    }

    /**
     * Binds to a factory with three dependencies.
     *
     * @param a       the first dependency's key
     * @param b       the second dependency's key
     * @param c       the third dependency's key
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @param <C>     the third dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B, C> Binding to(Key<A> a, Key<B> b, Key<C> c,
                                Factory.Of3<? super A, ? super B, ? super C, ? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return define(List.of(a, b, c), r -> factory.create(r.get(a), r.get(b), r.get(c)));
    }

    /**
     * Binds to a factory with four unqualified dependencies.
     *
     * @param a       the first dependency's type
     * @param b       the second dependency's type
     * @param c       the third dependency's type
     * @param d       the fourth dependency's type
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @param <C>     the third dependency's type
     * @param <D>     the fourth dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B, C, D> Binding to(Class<A> a, Class<B> b, Class<C> c, Class<D> d,
                                   Factory.Of4<? super A, ? super B, ? super C, ? super D, ? extends T> factory) {
        return to(Key.of(a), Key.of(b), Key.of(c), Key.of(d), factory);
    }

    /**
     * Binds to a factory with four dependencies.
     *
     * @param a       the first dependency's key
     * @param b       the second dependency's key
     * @param c       the third dependency's key
     * @param d       the fourth dependency's key
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @param <C>     the third dependency's type
     * @param <D>     the fourth dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B, C, D> Binding to(Key<A> a, Key<B> b, Key<C> c, Key<D> d,
                                   Factory.Of4<? super A, ? super B, ? super C, ? super D, ? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return define(List.of(a, b, c, d), r -> factory.create(r.get(a), r.get(b), r.get(c), r.get(d)));
    }

    /**
     * Binds to a factory with five unqualified dependencies.
     *
     * @param a       the first dependency's type
     * @param b       the second dependency's type
     * @param c       the third dependency's type
     * @param d       the fourth dependency's type
     * @param e       the fifth dependency's type
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @param <C>     the third dependency's type
     * @param <D>     the fourth dependency's type
     * @param <E>     the fifth dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B, C, D, E> Binding to(Class<A> a, Class<B> b, Class<C> c, Class<D> d, Class<E> e,
                                      Factory.Of5<? super A, ? super B, ? super C, ? super D, ? super E, ? extends T> factory) {
        return to(Key.of(a), Key.of(b), Key.of(c), Key.of(d), Key.of(e), factory);
    }

    /**
     * Binds to a factory with five dependencies.
     *
     * @param a       the first dependency's key
     * @param b       the second dependency's key
     * @param c       the third dependency's key
     * @param d       the fourth dependency's key
     * @param e       the fifth dependency's key
     * @param factory creates the component from the dependencies
     * @param <A>     the first dependency's type
     * @param <B>     the second dependency's type
     * @param <C>     the third dependency's type
     * @param <D>     the fourth dependency's type
     * @param <E>     the fifth dependency's type
     * @return the binding, to choose a scope
     */
    public <A, B, C, D, E> Binding to(Key<A> a, Key<B> b, Key<C> c, Key<D> d, Key<E> e,
                                      Factory.Of5<? super A, ? super B, ? super C, ? super D, ? super E, ? extends T> factory) {
        Objects.requireNonNull(factory, "factory");
        return define(List.of(a, b, c, d, e),
                r -> factory.create(r.get(a), r.get(b), r.get(c), r.get(d), r.get(e)));
    }

    /**
     * Binds to a factory that receives a {@link Resolver}. The resolver gives out exactly the
     * declared dependencies; {@link Container#create} validates them like those of the typed
     * form.
     *
     * @param dependencies everything the factory may ask the resolver for
     * @param factory      creates the component
     * @return the binding, to choose a scope
     */
    public Binding to(Dependencies dependencies, Factory<? extends T> factory) {
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(factory, "factory");
        return define(dependencies.keys(), factory);
    }

    /**
     * Binds to an instance made elsewhere. It is a singleton without dependencies, and the
     * container never closes it: whoever made it owns it.
     *
     * @param instance the component
     * @throws IllegalStateException if the binding already has a target or the container has
     *                               already been created
     */
    public void toInstance(T instance) {
        Objects.requireNonNull(instance, "instance");
        binder.checkOpen();
        registration.target(List.of(), r -> instance, false);
    }

    private Binding define(List<? extends Key<?>> dependencies, Factory<? extends T> factory) {
        dependencies.forEach(key -> Objects.requireNonNull(key, "dependency"));
        binder.checkOpen();
        registration.target(dependencies, factory, true);
        return new Binding(binder, registration);
    }
}
