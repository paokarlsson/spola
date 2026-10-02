package spola.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Calls code that throws checked exceptions where only unchecked ones are allowed (lambdas
 * passed to streams, {@code forEach}, and so on). Replaces Lombok's {@code @SneakyThrows}.
 *
 * {@snippet :
 * List<String> texts = paths.stream().map(Unchecked.function(Files::readString)).toList();
 * String config = Unchecked.get(() -> Files.readString(path));
 * Unchecked.run(() -> Thread.sleep(10));
 * }
 *
 * <h2>No sneaky throw</h2>
 * A checked exception is never rethrown undeclared (which Lombok does and which hides it from
 * the compiler and from {@code catch} clauses). It is wrapped by {@link #wrap(Exception)}:
 * <ul>
 *   <li>{@link IOException} (also its subclasses) becomes {@link UncheckedIOException} with
 *       the original as the cause.</li>
 *   <li>{@link InterruptedException}: the thread's interrupt flag is set again (catching it
 *       cleared the flag), then it is wrapped in an {@link UncheckedException}.</li>
 *   <li>Any other checked exception is wrapped in an {@link UncheckedException} with the
 *       original as the cause.</li>
 *   <li>{@link RuntimeException}s and {@link Error}s pass through unchanged: the very same
 *       instance is thrown.</li>
 * </ul>
 */
public final class Unchecked {

    private Unchecked() {
    }

    /**
     * Runs an action now, wrapping a checked exception.
     *
     * @param action the action
     */
    public static void run(ThrowingRunnable action) {
        Objects.requireNonNull(action, "action");
        try {
            action.run();
        } catch (Exception e) {
            throw wrap(e);
        }
    }

    /**
     * Computes a value now, wrapping a checked exception.
     *
     * @param supplier computes the value
     * @param <T> the type of the value
     * @return the value
     */
    public static <T> T get(ThrowingSupplier<? extends T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        try {
            return supplier.get();
        } catch (Exception e) {
            throw wrap(e);
        }
    }

    /**
     * Adapts a throwing runnable to a {@link Runnable} that wraps checked exceptions.
     *
     * @param action the action
     * @return the runnable
     */
    public static Runnable runnable(ThrowingRunnable action) {
        Objects.requireNonNull(action, "action");
        return () -> run(action);
    }

    /**
     * Adapts a throwing supplier to a {@link Supplier} that wraps checked exceptions.
     *
     * @param supplier the supplier
     * @param <T> the type of the result
     * @return the supplier
     */
    public static <T> Supplier<T> supplier(ThrowingSupplier<? extends T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return () -> get(supplier);
    }

    /**
     * Adapts a throwing function to a {@link Function} that wraps checked exceptions.
     *
     * @param function the function
     * @param <T> the type of the input
     * @param <R> the type of the result
     * @return the function
     */
    public static <T, R> Function<T, R> function(ThrowingFunction<? super T, ? extends R> function) {
        Objects.requireNonNull(function, "function");
        return value -> {
            try {
                return function.apply(value);
            } catch (Exception e) {
                throw wrap(e);
            }
        };
    }

    /**
     * Adapts a throwing consumer to a {@link Consumer} that wraps checked exceptions.
     *
     * @param consumer the consumer
     * @param <T> the type of the input
     * @return the consumer
     */
    public static <T> Consumer<T> consumer(ThrowingConsumer<? super T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        return value -> {
            try {
                consumer.accept(value);
            } catch (Exception e) {
                throw wrap(e);
            }
        };
    }

    /**
     * The unchecked exception to throw for {@code exception}, by the rules in the class
     * documentation. For an {@link InterruptedException} this also sets the current thread's
     * interrupt flag. Use it in your own {@code catch} blocks: {@code throw Unchecked.wrap(e);}.
     *
     * @param exception the exception
     * @return the same instance for a {@link RuntimeException}; otherwise a wrapper with
     *     {@code exception} as the cause
     */
    public static RuntimeException wrap(Exception exception) {
        Objects.requireNonNull(exception, "exception");
        return switch (exception) {
            case RuntimeException unchecked -> unchecked;
            case IOException io -> new UncheckedIOException(io);
            case InterruptedException interrupted -> {
                Thread.currentThread().interrupt();
                yield new UncheckedException(interrupted);
            }
            default -> new UncheckedException(exception);
        };
    }
}
