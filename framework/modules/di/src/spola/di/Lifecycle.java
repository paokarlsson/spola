package spola.di;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The {@link AutoCloseable} instances one owner created (the container, or one call scope), in
 * creation order, and closing them in reverse.
 */
final class Lifecycle {

    record Created(Key<?> key, AutoCloseable instance) {
    }

    record Failure(Key<?> key, Throwable error) {
    }

    private final String what;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Created> created = new ArrayList<>();
    private volatile boolean closed;

    Lifecycle(String what) {
        this.what = what;
    }

    boolean isClosed() {
        return closed;
    }

    /**
     * Remembers a newly created instance if it is closeable. If the owner closed meanwhile (a
     * lazy singleton finished while the container closed), the instance is closed right away
     * and the caller gets an exception instead of a closed component.
     */
    void register(List<? extends Key<?>> path, Key<?> key, Object instance) {
        if (!(instance instanceof AutoCloseable closeable)) {
            return;
        }
        lock.lock();
        try {
            if (!closed) {
                created.add(new Created(key, closeable));
                return;
            }
        } finally {
            lock.unlock();
        }
        var failure = new ResolutionException(path, what + " was closed while " + key
                + " was being created; it has been closed again");
        closeEach(List.of(new Created(key, closeable))).forEach(f -> failure.addSuppressed(f.error()));
        throw failure;
    }

    /** Closes everything in reverse creation order; empty if all closed fine or already closed. */
    Optional<CloseException> close() {
        List<Created> toClose;
        lock.lock();
        try {
            if (closed) {
                return Optional.empty();
            }
            closed = true;
            toClose = new ArrayList<>(created.reversed());
            created.clear();
        } finally {
            lock.unlock();
        }
        var failures = closeEach(toClose);
        return failures.isEmpty() ? Optional.empty() : Optional.of(new CloseException(what, failures));
    }

    /** Closes each instance in order; a failure is collected and the next one is still closed. */
    private static List<Failure> closeEach(List<Created> instances) {
        var failures = new ArrayList<Failure>();
        for (var entry : instances) {
            try {
                entry.instance().close();
            } catch (Throwable error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.add(new Failure(entry.key(), error));
            }
        }
        return failures;
    }
}
