package spola.di;

/**
 * How long a component lives. Chosen per binding with {@link Binding#in(Scope)}; the default
 * is {@link #SINGLETON}.
 */
public enum Scope {

    /**
     * One instance per container, created on first use. Creation is thread-safe: concurrent
     * first calls (also from virtual threads) create it exactly once. Closed with the container.
     */
    SINGLETON,

    /**
     * One instance per container, created by {@link Container#create} (after validation, in
     * binding order). A failing factory fails {@code create}. Closed with the container.
     */
    EAGER_SINGLETON,

    /**
     * A new instance every time it is fetched or injected. The container does not keep or
     * close prototypes; whoever fetches one owns it.
     */
    PROTOTYPE,

    /**
     * One instance per call scope ({@link Container#runInCallScope} and
     * {@link Container#callInCallScope}), held in a {@link java.lang.ScopedValue}. Fetching it
     * outside a call scope fails. Closed when its call scope ends. A singleton may not depend on
     * a call-scoped component (checked by {@link Container#create}).
     */
    CALL
}
