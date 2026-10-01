package spola.di;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Collects the bindings of a container while its modules run. Only {@link Container#create}
 * makes one; it is handed to each {@link ContainerModule#configure}. Not thread-safe, and
 * unusable once the container is created.
 *
 * <p>A binding is {@code bind(key)}, then a target ({@code to(...)} or {@code toInstance}),
 * then optionally a scope ({@code in(...)}):
 *
 * <pre>{@code
 * binder.bind(OrderService.class).to(OrderRepo.class, Clock.class, OrderService::new);
 * binder.bind(Key.of(Greeter.class, Lang.SV)).to(SwedishGreeter::new).in(Scope.PROTOTYPE);
 * }</pre>
 *
 * <p>Mistakes that only show in the whole graph (a key bound twice, a binding without a
 * target, missing dependencies, cycles) are reported together by {@link Container#create}.
 * Each binding remembers where {@code bind} was called, for those messages.
 */
public final class Binder {

    private static final StackWalker STACK = StackWalker.getInstance();

    private final List<Registration<?>> registrations = new ArrayList<>();
    private final Set<ContainerModule> installed = new HashSet<>();
    private boolean finished;

    Binder() {
    }

    /**
     * Starts binding the unqualified key of a type.
     *
     * @param type the component type
     * @param <T>  the component type
     * @return the builder; call one of its {@code to} methods
     * @throws IllegalStateException if the container has already been created
     */
    public <T> BindingBuilder<T> bind(Class<T> type) {
        return bind(Key.of(type));
    }

    /**
     * Starts binding a key.
     *
     * @param key the key
     * @param <T> the component type
     * @return the builder; call one of its {@code to} methods
     * @throws IllegalStateException if the container has already been created
     */
    public <T> BindingBuilder<T> bind(Key<T> key) {
        Objects.requireNonNull(key, "key");
        checkOpen();
        var registration = new Registration<>(key, callerLocation());
        registrations.add(registration);
        return new BindingBuilder<>(this, registration);
    }

    /**
     * Installs a module, unless an equal module is already installed in this container.
     *
     * @param module the module
     * @throws IllegalStateException if the container has already been created
     */
    public void install(ContainerModule module) {
        Objects.requireNonNull(module, "module");
        checkOpen();
        if (installed.add(module)) {
            module.configure(this);
        }
    }

    void checkOpen() {
        if (finished) {
            throw new IllegalStateException("the binder was used after its container was created;"
                    + " bind everything inside ContainerModule.configure");
        }
    }

    /** Closes the binder and returns the bindings in the order they were made. */
    List<Definition<?>> finish() {
        checkOpen();
        finished = true;
        return registrations.stream().<Definition<?>>map(Registration::definition).toList();
    }

    /** {@code Class.method(File.java:line)} of the first caller outside this class. */
    private static String callerLocation() {
        var self = Binder.class.getName();
        return STACK.walk(frames -> frames
                .dropWhile(frame -> frame.getClassName().equals(self))
                .findFirst()
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + "("
                        + (frame.getFileName() == null ? "unknown source" : frame.getFileName() + ":" + frame.getLineNumber())
                        + ")")
                .orElse("unknown location"));
    }
}
