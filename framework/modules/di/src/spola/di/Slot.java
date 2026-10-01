package spola.di;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Holds one instance (a singleton, or a call-scoped component in one scope) and creates it
 * exactly once, however many threads ask at the same time.
 *
 * <p>Double-checked locking on a volatile field, with a {@link ReentrantLock} per slot (virtual
 * threads waiting for it unmount). A thread holds the lock while the factory runs, so it may
 * take the locks of the slot's dependencies; that cannot deadlock because the container refuses
 * cyclic graphs and factories reach only declared dependencies. If the factory throws, nothing
 * is stored and the next call tries again.
 */
final class Slot {

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Object instance;

    Object get(List<? extends Key<?>> path, Supplier<Object> create) {
        var existing = instance;
        if (existing != null) {
            return existing;
        }
        if (lock.isHeldByCurrentThread()) { // unreachable for a validated graph; never create twice
            throw new ResolutionException(path, "dependency cycle at runtime");
        }
        lock.lock();
        try {
            if (instance == null) {
                instance = create.get();
            }
            return instance;
        } finally {
            lock.unlock();
        }
    }
}
