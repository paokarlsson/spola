package spola.di;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The dependencies a resolver-based binding declares, for
 * {@link BindingBuilder#to(Dependencies, Factory)}:
 *
 * <pre>{@code
 * binder.bind(OrderService.class).to(
 *         Dependencies.of(OrderRepo.class).and(Key.of(Clock.class, Zone.UTC)),
 *         r -> new OrderService(r.get(OrderRepo.class), r.get(Key.of(Clock.class, Zone.UTC))));
 * }</pre>
 *
 * <p>Immutable. A key listed twice counts once.
 */
public final class Dependencies {

    private static final Dependencies NONE = new Dependencies(List.of());

    private final List<Key<?>> keys;

    private Dependencies(List<Key<?>> keys) {
        this.keys = keys;
    }

    /**
     * No dependencies.
     *
     * @return the empty declaration
     */
    public static Dependencies none() {
        return NONE;
    }

    /**
     * Unqualified dependencies.
     *
     * @param types the types, at least one
     * @return the declaration
     */
    public static Dependencies of(Class<?>... types) {
        return NONE.and(types);
    }

    /**
     * Dependencies given by key.
     *
     * @param keys the keys, at least one
     * @return the declaration
     */
    public static Dependencies of(Key<?>... keys) {
        return NONE.and(keys);
    }

    /**
     * These dependencies and some unqualified ones.
     *
     * @param types more types
     * @return a new declaration
     */
    public Dependencies and(Class<?>... types) {
        var more = new ArrayList<Key<?>>();
        for (var type : types) {
            more.add(Key.of(type));
        }
        return with(more);
    }

    /**
     * These dependencies and some more given by key.
     *
     * @param keys more keys
     * @return a new declaration
     */
    public Dependencies and(Key<?>... keys) {
        return with(List.of(keys));
    }

    private Dependencies with(List<Key<?>> more) {
        var all = new LinkedHashSet<>(keys);
        for (var key : more) {
            all.add(Objects.requireNonNull(key, "dependency"));
        }
        return new Dependencies(List.copyOf(all));
    }

    /**
     * The declared keys.
     *
     * @return the keys in declaration order, without duplicates
     */
    public List<Key<?>> keys() {
        return keys;
    }

    /**
     * The declared keys.
     *
     * @return for example {@code [shop.OrderRepo, java.time.Clock[UTC]]}
     */
    @Override
    public String toString() {
        return keys.toString();
    }
}
