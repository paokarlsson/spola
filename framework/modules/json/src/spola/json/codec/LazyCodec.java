package spola.json.codec;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import spola.json.JsonValue;

/**
 * {@link JsonCodecs#lazy}: a codec obtained on first use, for recursive types. The supplier is
 * called at most once (under a lock that does not pin virtual threads); afterwards the codec
 * is read from a {@code volatile} field.
 */
final class LazyCodec<T> extends JsonCodec<T> {

    private final ReentrantLock lock = new ReentrantLock();
    private Supplier<? extends JsonCodec<T>> supplier;
    private volatile JsonCodec<T> codec;

    LazyCodec(Supplier<? extends JsonCodec<T>> supplier) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    private JsonCodec<T> codec() {
        var resolved = codec;
        if (resolved != null) {
            return resolved;
        }
        lock.lock();
        try {
            if (codec == null) {
                var supplied = supplier.get();
                if (supplied == null) {
                    throw new IllegalStateException("the supplier of a lazy codec returned null");
                }
                codec = supplied;
                supplier = null;
            }
            return codec;
        } finally {
            lock.unlock();
        }
    }

    @Override
    T decodeAt(JsonValue json, Path path, Decoding decoding) {
        decoding.enterLazy();
        T value = codec().decodeAt(json, path, decoding);
        decoding.exitLazy();
        return value;
    }

    @Override
    JsonValue encodeAt(T value, Path path, Encoding encoding) {
        encoding.enterLazy();
        var json = codec().encodeAt(value, path, encoding);
        encoding.exitLazy();
        return json;
    }
}
