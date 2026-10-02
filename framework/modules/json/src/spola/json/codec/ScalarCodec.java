package spola.json.codec;

import java.io.Serial;
import java.util.Objects;
import spola.json.JsonValue;

/**
 * A codec for a value without nested objects or arrays: a reader and a writer that either
 * succeed or reject with a message. Used for the built-in codecs, enums and {@code JSON}.
 */
final class ScalarCodec<T> extends JsonCodec<T> {

    /** Reads a JSON value (never Java {@code null}, possibly JSON {@code null}). */
    @FunctionalInterface
    interface Reader<T> {
        T read(JsonValue json) throws Rejected;
    }

    /** Writes a Java value (never {@code null}). */
    @FunctionalInterface
    interface Writer<T> {
        JsonValue write(T value) throws Rejected;
    }

    /** A value was rejected; the message is safe (built by the codec, not taken from input). */
    static final class Rejected extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        Rejected(String message) {
            super(message, null, false, false);
        }
    }

    private final Reader<T> reader;
    private final Writer<T> writer;

    ScalarCodec(Reader<T> reader, Writer<T> writer) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    T decodeAt(JsonValue json, Path path, Decoding decoding) {
        try {
            return reader.read(json);
        } catch (Rejected e) {
            decoding.report(path, e.getMessage());
            return null;
        }
    }

    @Override
    JsonValue encodeAt(T value, Path path, Encoding encoding) {
        if (value == null) {
            throw Encoding.nullValue(path);
        }
        try {
            return writer.write(value);
        } catch (Rejected e) {
            throw Encoding.fail(path, e.getMessage());
        }
    }
}
