package spola.di;

import java.util.LinkedHashSet;
import java.util.List;

/** A binding while its module runs: filled in by {@link BindingBuilder} and {@link Binding}. */
final class Registration<T> {

    private final Key<T> key;
    private final String source;
    private List<Key<?>> dependencies = List.of();
    private Factory<? extends T> factory;
    private boolean owned = true;
    private Scope scope = Scope.SINGLETON;
    private boolean scopeChosen;

    Registration(Key<T> key, String source) {
        this.key = key;
        this.source = source;
    }

    void target(List<? extends Key<?>> dependencies, Factory<? extends T> factory, boolean owned) {
        if (this.factory != null) {
            throw new IllegalStateException(key + " (bound at " + source + ") already has a target");
        }
        this.dependencies = List.copyOf(new LinkedHashSet<>(dependencies));
        this.factory = factory;
        this.owned = owned;
    }

    void scope(Scope scope) {
        if (scopeChosen) {
            throw new IllegalStateException(key + " (bound at " + source + ") already has scope " + this.scope);
        }
        this.scope = scope;
        scopeChosen = true;
    }

    Definition<T> definition() {
        return new Definition<>(key, source, dependencies, factory, scope, owned);
    }
}
