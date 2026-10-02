package spola.util;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A value computed at most once, on the first {@link #get()}. Replaces Lombok's
 * {@code @Getter(lazy = true)}:
 *
 * {@snippet :
 * private final Lazy<Report> report = Lazy.of(() -> buildExpensiveReport());
 *
 * public Report report() {
 *     return report.get();
 * }
 * }
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>At most once.</b> However many (virtual) threads call {@link #get()} at the same
 *       time, the supplier runs at most once successfully; the others wait for it and get the
 *       same value. After the value is computed, {@code get()} is a volatile read without a
 *       lock.</li>
 *   <li><b>Exceptions are not remembered.</b> If the supplier throws, {@code get()} throws the
 *       same exception and nothing is stored; the next {@code get()} calls the supplier again.
 *       (A failure caused by something temporary, such as a network error, can then succeed
 *       later.)</li>
 *   <li><b>No {@code null}.</b> A supplier that returns {@code null} is an error:
 *       {@code get()} throws a {@link NullPointerException} and nothing is stored, as for an
 *       exception. Use {@link java.util.Optional} for a value that may be absent.</li>
 *   <li><b>The supplier is released.</b> After a successful computation the reference to the
 *       supplier is cleared, so whatever it captured can be garbage-collected.</li>
 *   <li><b>No recursion.</b> A supplier that calls {@code get()} on its own {@code Lazy}
 *       (directly or indirectly, in the same thread) gets an {@link IllegalStateException}
 *       instead of endless recursion.</li>
 * </ul>
 * The lock is a {@link ReentrantLock}, so a virtual thread waiting for the value does not
 * pin its carrier thread.
 *
 * @param <T> the type of the value
 */
public final class Lazy<T> implements Supplier<T> {

    private final ReentrantLock lock = new ReentrantLock();

    /** Cleared after a successful computation; read and written only while holding the lock. */
    private Supplier<? extends T> supplier;

    /** {@code null} until computed; never {@code null} afterwards. */
    private volatile T value;

    private Lazy(Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    /**
     * A lazy value computed by {@code supplier} on the first {@link #get()}.
     *
     * @param supplier computes the value; must not return {@code null}
     * @param <T> the type of the value
     * @return the lazy value, not yet computed
     */
    public static <T> Lazy<T> of(Supplier<? extends T> supplier) {
        return new Lazy<>(Objects.requireNonNull(supplier, "supplier"));
    }

    /**
     * The value, computed by the supplier on the first call.
     *
     * @return the value, never {@code null}
     * @throws NullPointerException if the supplier returned {@code null} (not remembered)
     * @throws IllegalStateException if called from within its own supplier
     */
    @Override
    public T get() {
        T current = value;
        if (current != null) {
            return current;
        }
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Lazy.get() was called from within its own supplier");
        }
        lock.lock();
        try {
            current = value;
            if (current == null) {
                current = supplier.get();
                if (current == null) {
                    throw new NullPointerException("the supplier of a Lazy returned null; a Lazy cannot hold"
                            + " null (use Optional for a value that may be absent)");
                }
                value = current;
                supplier = null;
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Whether the value has been computed. Does not compute it.
     *
     * @return {@code true} once a {@link #get()} has succeeded
     */
    public boolean isComputed() {
        return value != null;
    }

    /**
     * Does not compute the value and does not show it (it could be secret).
     *
     * @return {@code Lazy[computed]} or {@code Lazy[not computed]}
     */
    @Override
    public String toString() {
        return isComputed() ? "Lazy[computed]" : "Lazy[not computed]";
    }
}
