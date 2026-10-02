package spola.json.codec;

import java.util.Objects;
import java.util.function.Function;
import spola.json.JsonValue;

/** {@link JsonCodec#map}: a type represented by another codec's type. */
final class MappedCodec<T, R> extends JsonCodec<R> {

    private final JsonCodec<T> inner;
    private final Function<? super T, ? extends R> to;
    private final Function<? super R, ? extends T> from;

    MappedCodec(JsonCodec<T> inner, Function<? super T, ? extends R> to, Function<? super R, ? extends T> from) {
        this.inner = inner;
        this.to = Objects.requireNonNull(to, "to");
        this.from = Objects.requireNonNull(from, "from");
    }

    @Override
    R decodeAt(JsonValue json, Path path, Decoding decoding) {
        int before = decoding.count();
        T value = inner.decodeAt(json, path, decoding);
        if (decoding.count() != before) {
            return null;
        }
        try {
            return to.apply(value);
        } catch (FieldValues.NotAField e) {
            throw e;
        } catch (RuntimeException e) {
            decoding.report(path, Messages.rejected(e));
            return null;
        }
    }

    @Override
    JsonValue encodeAt(R value, Path path, Encoding encoding) {
        if (value == null) {
            throw Encoding.nullValue(path);
        }
        T mapped;
        try {
            mapped = from.apply(value);
        } catch (RuntimeException e) {
            throw Encoding.fail(path, "the mapping failed: " + Messages.describe(e));
        }
        return inner.encodeAt(mapped, path, encoding);
    }
}
