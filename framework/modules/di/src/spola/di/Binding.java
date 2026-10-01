package spola.di;

import java.util.Objects;

/**
 * A binding with a factory; choose its scope here. Without a call to {@link #in(Scope)} the
 * scope is {@link Scope#SINGLETON} (lazy).
 */
public final class Binding {

    private final Binder binder;
    private final Registration<?> registration;

    Binding(Binder binder, Registration<?> registration) {
        this.binder = binder;
        this.registration = registration;
    }

    /**
     * Sets the scope. At most once per binding.
     *
     * @param scope the scope
     * @throws IllegalStateException if the scope was already set or the container has already
     *                               been created
     */
    public void in(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        binder.checkOpen();
        registration.scope(scope);
    }
}
