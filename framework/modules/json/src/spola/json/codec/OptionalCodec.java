package spola.json.codec;

import java.util.Optional;
import spola.json.JsonNull;
import spola.json.JsonValue;

/** {@link JsonCodecs#optional}: JSON {@code null} as {@code Optional.empty()}. */
final class OptionalCodec<T> extends JsonCodec<Optional<T>> {

    private final JsonCodec<T> inner;

    OptionalCodec(JsonCodec<T> inner) {
        this.inner = inner;
    }

    @Override
    Optional<T> decodeAt(JsonValue json, Path path, Decoding decoding) {
        if (json instanceof JsonNull) {
            return Optional.empty();
        }
        return Optional.ofNullable(inner.decodeAt(json, path, decoding));
    }

    @Override
    JsonValue encodeAt(Optional<T> value, Path path, Encoding encoding) {
        if (value == null) {
            throw Encoding.fail(path, "value is null; expected an Optional, empty or not");
        }
        return value.isEmpty() ? JsonNull.INSTANCE : inner.encodeAt(value.get(), path, encoding);
    }
}
