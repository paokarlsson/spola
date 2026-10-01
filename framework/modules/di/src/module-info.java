/**
 * Spola's dependency injection container, a replacement for Spring Core without annotations,
 * scanning or reflection.
 *
 * <p>Components are bound in plain Java code: a {@link spola.di.ContainerModule} receives a
 * {@link spola.di.Binder} and binds each {@link spola.di.Key} to a factory together with the
 * dependencies the factory needs. {@link spola.di.Container#create} checks the whole graph
 * (missing dependencies, cycles, duplicates, scope mistakes) before any component is created
 * and reports every problem with the dependency chain. Scopes: lazy and eager singletons,
 * prototypes and a call scope built on {@link java.lang.ScopedValue}. Closing the container
 * closes the {@link java.lang.AutoCloseable} singletons it created, in reverse creation order.
 */
module spola.di {
    exports spola.di;
}
