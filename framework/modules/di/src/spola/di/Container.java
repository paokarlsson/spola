package spola.di;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A validated, running set of components.
 *
 * <pre>{@code
 * ContainerModule shop = binder -> {
 *     binder.bind(OrderRepo.class).to(InMemoryOrderRepo::new);
 *     binder.bind(OrderService.class).to(OrderRepo.class, OrderService::new);
 * };
 * try (var container = Container.create(shop)) {
 *     container.get(OrderService.class).place(order);
 * }
 * }</pre>
 *
 * <p>{@link #create} runs the modules, validates the whole graph and creates the eager
 * singletons; wiring errors therefore fail {@code create}, not the first {@link #get}.
 * Fetching is thread-safe. {@link #close} closes the {@link AutoCloseable} singletons the
 * container created, in reverse creation order (an instance counts as created when its factory
 * returns, so its dependencies come before it and are closed after it).
 */
public final class Container implements AutoCloseable {

    private final Map<Key<?>, Definition<?>> definitions;
    private final Map<Key<?>, Slot> singletons = new HashMap<>();
    private final Lifecycle lifecycle = new Lifecycle("the container");
    /** The current call scope of this container. */
    private final ScopedValue<CallScope> callScope = ScopedValue.newInstance();
    /** Bound while one of this container's factories runs, to catch {@code get} from inside. */
    private final ScopedValue<Key<?>> creating = ScopedValue.newInstance();

    /** One call scope: its instances and what to close when it ends. */
    private record CallScope(Map<Key<?>, Slot> slots, Lifecycle lifecycle) {
    }

    private Container(List<Definition<?>> definitions) {
        var byKey = new LinkedHashMap<Key<?>, Definition<?>>();
        for (var definition : definitions) {
            byKey.put(definition.key(), definition);
            if (definition.scope() == Scope.SINGLETON || definition.scope() == Scope.EAGER_SINGLETON) {
                singletons.put(definition.key(), new Slot());
            }
        }
        this.definitions = byKey;
    }

    /**
     * Creates a container: runs the modules, validates the bindings and creates the eager
     * singletons in binding order.
     *
     * @param modules the modules; each distinct module is installed once
     * @return the container
     * @throws WiringException     if the bindings do not form a valid graph (nothing has been
     *                             created then)
     * @throws ResolutionException if an eager singleton cannot be created; what was already
     *                             created has been closed
     */
    public static Container create(ContainerModule... modules) {
        var binder = new Binder();
        for (var module : modules) {
            binder.install(module);
        }
        var definitions = binder.finish();
        var problems = Validator.problems(definitions);
        if (!problems.isEmpty()) {
            throw new WiringException(problems);
        }
        var container = new Container(definitions);
        try {
            for (var definition : definitions) {
                if (definition.scope() == Scope.EAGER_SINGLETON) {
                    container.resolve(definition.key(), List.of(definition.key()));
                }
            }
        } catch (RuntimeException | Error failure) {
            container.lifecycle.close().ifPresent(failure::addSuppressed);
            throw failure;
        }
        return container;
    }

    /**
     * The unqualified component of a type; the same as {@code get(Key.of(type))}.
     *
     * @param type the component type
     * @param <T>  the component type
     * @return the component
     * @throws ResolutionException   if it is not bound or cannot be created
     * @throws IllegalStateException if the container is closed
     */
    public <T> T get(Class<T> type) {
        return get(Key.of(type));
    }

    /**
     * A component.
     *
     * <p>Not for use inside a factory of this container: a factory gets its dependencies from
     * its {@link Resolver}, so that the validated graph is the one used.
     *
     * @param key the component's key
     * @param <T> the component type
     * @return the component, in the scope it was bound in
     * @throws ResolutionException   if it is not bound, cannot be created, is call-scoped and
     *                               no call scope is active, or this is called from a factory
     * @throws IllegalStateException if the container is closed
     */
    public <T> T get(Key<T> key) {
        Objects.requireNonNull(key, "key");
        checkOpen();
        if (creating.isBound()) {
            throw new ResolutionException(List.of(creating.get(), key), "Container.get was called from the factory of "
                    + creating.get() + "; declare the dependency and use the Resolver the factory receives");
        }
        return resolve(key, List.of(key));
    }

    /**
     * Runs code in a new call scope: {@link Scope#CALL} components fetched in it (also by
     * other components' factories) are created once per scope, and closed in reverse creation
     * order when it ends. Nested call scopes are independent of the outer one. Threads forked
     * with {@code StructuredTaskScope} inside it share the scope; other threads do not.
     *
     * @param body the code
     * @throws CloseException        if closing a call-scoped component failed (when the body
     *                               threw, this is added to its exception as suppressed
     *                               instead)
     * @throws IllegalStateException if the container is closed
     */
    public void runInCallScope(Runnable body) {
        Objects.requireNonNull(body, "body");
        callInCallScope(() -> {
            body.run();
            return null;
        });
    }

    /**
     * Like {@link #runInCallScope}, for code that returns a value or throws a checked exception.
     *
     * @param body the code
     * @param <R>  the result type
     * @param <X>  the exception the body may throw
     * @return what the body returned
     * @throws X                     what the body threw
     * @throws CloseException        if closing a call-scoped component failed
     * @throws IllegalStateException if the container is closed
     */
    public <R, X extends Throwable> R callInCallScope(ScopedValue.CallableOp<? extends R, X> body) throws X {
        Objects.requireNonNull(body, "body");
        checkOpen();
        var scope = new CallScope(new ConcurrentHashMap<>(), new Lifecycle("a call scope"));
        R result;
        try {
            result = ScopedValue.where(callScope, scope).call(body);
        } catch (Throwable failure) {
            scope.lifecycle().close().ifPresent(failure::addSuppressed);
            throw failure;
        }
        var closeFailure = scope.lifecycle().close();
        if (closeFailure.isPresent()) {
            throw closeFailure.get();
        }
        return result;
    }

    /**
     * Closes the {@link AutoCloseable} singletons this container created, last created first.
     * A failing {@code close} does not stop the others. Instances bound with
     * {@link BindingBuilder#toInstance} and prototypes are not closed. Calling it again does
     * nothing.
     *
     * @throws CloseException if one or more components failed to close; all were attempted
     */
    @Override
    public void close() {
        var failure = lifecycle.close();
        if (failure.isPresent()) {
            throw failure.get();
        }
    }

    private void checkOpen() {
        if (lifecycle.isClosed()) {
            throw new IllegalStateException("the container is closed");
        }
    }

    /** Fetches {@code key}; {@code path} is the chain that led here, ending with {@code key}. */
    <T> T resolve(Key<T> key, List<Key<?>> path) {
        var definition = definitions.get(key);
        if (definition == null) {
            throw new ResolutionException(path, key + " is not bound" + similar(key));
        }
        Object instance = switch (definition.scope()) {
            case SINGLETON, EAGER_SINGLETON ->
                    singletons.get(key).get(path, () -> create(definition, path, lifecycle));
            case PROTOTYPE -> create(definition, path, null);
            case CALL -> {
                if (!callScope.isBound()) {
                    throw new ResolutionException(path, key + " is call-scoped and no call scope is active;"
                            + " use Container.runInCallScope or callInCallScope");
                }
                var scope = callScope.get();
                yield scope.slots().computeIfAbsent(key, _ -> new Slot())
                        .get(path, () -> create(definition, path, scope.lifecycle()));
            }
        };
        return key.type().cast(instance);
    }

    private String similar(Key<?> key) {
        var sameType = new ArrayList<Key<?>>();
        for (var bound : definitions.keySet()) {
            if (bound.type() == key.type()) {
                sameType.add(bound);
            }
        }
        return sameType.isEmpty() ? "" : " (bound for that type: " + sameType + ")";
    }

    /** Runs the factory; registers the instance with {@code owner} (null: nobody keeps it). */
    private Object create(Definition<?> definition, List<Key<?>> path, Lifecycle owner) {
        var resolver = new FactoryResolver(this, definition, path);
        Object instance;
        try {
            instance = ScopedValue.where(creating, definition.key()).call(() -> definition.factory().create(resolver));
        } catch (ResolutionException failure) {
            throw failure;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ResolutionException(path, "the factory of " + definition.describe() + " was interrupted", failure);
        } catch (Exception failure) {
            throw new ResolutionException(path, "the factory of " + definition.describe() + " threw " + failure, failure);
        } finally {
            resolver.finish();
        }
        if (instance == null) {
            throw new ResolutionException(path, "the factory of " + definition.describe() + " returned null");
        }
        if (!definition.key().type().isInstance(instance)) { // only through unchecked casts
            throw new ResolutionException(path, "the factory of " + definition.describe() + " returned a "
                    + instance.getClass().getName());
        }
        if (owner != null && definition.owned()) {
            owner.register(path, definition.key(), instance);
        }
        return instance;
    }
}
