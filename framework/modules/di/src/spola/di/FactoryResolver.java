package spola.di;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The resolver one factory call gets: declared dependencies only, and only during the call. */
final class FactoryResolver implements Resolver {

    private final Container container;
    private final Definition<?> owner;
    private final List<Key<?>> path;
    private final Set<Key<?>> declared;
    private volatile boolean finished;

    FactoryResolver(Container container, Definition<?> owner, List<Key<?>> path) {
        this.container = container;
        this.owner = owner;
        this.path = path;
        this.declared = new HashSet<>(owner.dependencies());
    }

    @Override
    public <T> T get(Key<T> key) {
        Objects.requireNonNull(key, "key");
        if (finished) {
            throw new ResolutionException(path, "the resolver of " + owner.describe()
                    + " was used after its factory returned; fetch dependencies inside the factory");
        }
        if (!declared.contains(key)) {
            throw new ResolutionException(path, "the factory of " + owner.describe() + " asked for " + key
                    + ", which it does not declare; declared: " + owner.dependencies());
        }
        var next = new ArrayList<Key<?>>(path);
        next.add(key);
        return container.resolve(key, List.copyOf(next));
    }

    void finish() {
        finished = true;
    }
}
