package spola.json.codec;

import spola.json.JsonNull;
import spola.json.JsonValue;

/** {@link JsonCodecs#nullable}: JSON {@code null} as Java {@code null}. */
final class NullableCodec<T> extends JsonCodec<T> {

    private final JsonCodec<T> inner;

    NullableCodec(JsonCodec<T> inner) {
        this.inner = inner;
    }

    @Override
    T decodeAt(JsonValue json, Path path, Decoding decoding) {
        return json instanceof JsonNull ? null : inner.decodeAt(json, path, decoding);
    }

    @Override
    JsonValue encodeAt(T value, Path path, Encoding encoding) {
        return value == null ? JsonNull.INSTANCE : inner.encodeAt(value, path, encoding);
    }
}
