package spola.di;

/**
 * A reusable group of bindings, installed into a container with {@link Container#create} or
 * into another module with {@link Binder#install}. Usually a lambda or a small class:
 *
 * <pre>{@code
 * ContainerModule persistence = binder -> {
 *     binder.bind(DataSource.class).to(Config.class, PooledDataSource::new).in(Scope.EAGER_SINGLETON);
 *     binder.bind(OrderRepo.class).to(DataSource.class, JdbcOrderRepo::new);
 * };
 * }</pre>
 *
 * <p>A module is installed at most once per container: installing a module equal to one already
 * installed does nothing. Give a module class value-based {@code equals} (a record, say) when
 * several modules install it.
 */
@FunctionalInterface
public interface ContainerModule {

    /**
     * Adds this module's bindings.
     *
     * @param binder the binder of the container being created; do not keep it, it refuses
     *               use once the container is created
     */
    void configure(Binder binder);
}
