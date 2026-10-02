package spola.json.codec;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import spola.json.JsonNull;
import spola.json.JsonString;
import spola.json.JsonValue;

/**
 * One member of an object codec: its JSON name, the codec of its value and how to read it from
 * the Java value. Made with {@link JsonCodec#field(String, JsonCodec, Function)} or
 * {@link JsonCodec#optionalField(String, JsonCodec, Function)}; immutable and thread-safe.
 *
 * <ul>
 *   <li>{@code field("name", STRING, Person::name)} is required: a missing member is a problem,
 *       and so is {@code null} (unless the codec is {@code nullable}).</li>
 *   <li>{@code field("qty", INT, Line::qty).orElse(1)} uses {@code 1} when the member is
 *       missing; {@code null} is still a problem unless the codec accepts it. The value is
 *       always written when encoding.</li>
 *   <li>{@code optionalField("note", STRING, Person::note)} is for a component of type
 *       {@code Optional<String>}: a missing member and {@code null} both give
 *       {@code Optional.empty()}, and an empty value is left out when encoding (like
 *       Jackson's {@code @JsonInclude(NON_ABSENT)}).</li>
 * </ul>
 *
 * <p>A field is also the key of its value in the general form,
 * {@link JsonCodec#object(java.util.List, Function)}: {@code values.get(NAME)}.
 *
 * @param <R> the type the field belongs to
 * @param <T> the type of the field's value
 */
public final class Field<R, T> {

    /** How a member is decoded when missing or present and encoded; one per kind of field. */
    sealed interface Slot<T> permits Required, WithDefault, Optionally {

        /** The value for a missing member, or {@code null} after reporting a problem. */
        T missing(Path path, Decoding decoding);

        /** The value for a present member (possibly JSON {@code null}). */
        T present(JsonValue json, Path path, Decoding decoding);

        /** The member's JSON value, or {@code null} to leave the member out. */
        JsonValue encode(T value, Path path, Encoding encoding);
    }

    record Required<T>(JsonCodec<T> codec) implements Slot<T> {
        @Override
        public T missing(Path path, Decoding decoding) {
            decoding.report(path, "missing member");
            return null;
        }

        @Override
        public T present(JsonValue json, Path path, Decoding decoding) {
            return codec.decodeAt(json, path, decoding);
        }

        @Override
        public JsonValue encode(T value, Path path, Encoding encoding) {
            return codec.encodeAt(value, path, encoding);
        }
    }

    record WithDefault<T>(JsonCodec<T> codec, T fallback) implements Slot<T> {
        @Override
        public T missing(Path path, Decoding decoding) {
            return fallback;
        }

        @Override
        public T present(JsonValue json, Path path, Decoding decoding) {
            return codec.decodeAt(json, path, decoding);
        }

        @Override
        public JsonValue encode(T value, Path path, Encoding encoding) {
            return codec.encodeAt(value, path, encoding);
        }
    }

    record Optionally<V>(JsonCodec<V> codec) implements Slot<Optional<V>> {
        @Override
        public Optional<V> missing(Path path, Decoding decoding) {
            return Optional.empty();
        }

        @Override
        public Optional<V> present(JsonValue json, Path path, Decoding decoding) {
            if (json instanceof JsonNull) {
                return Optional.empty();
            }
            return Optional.ofNullable(codec.decodeAt(json, path, decoding));
        }

        @Override
        public JsonValue encode(Optional<V> value, Path path, Encoding encoding) {
            if (value == null) {
                throw Encoding.fail(path, "value is null; an optional field needs an Optional, empty or not");
            }
            return value.isEmpty() ? null : codec.encodeAt(value.get(), path, encoding);
        }
    }

    private final String name;
    private final Slot<T> slot;
    private final Function<? super R, ? extends T> getter;

    Field(String name, Slot<T> slot, Function<? super R, ? extends T> getter) {
        JsonString.of(Objects.requireNonNull(name, "name")); // rejects unpaired surrogates
        this.name = name;
        this.slot = slot;
        this.getter = Objects.requireNonNull(getter, "getter");
    }

    /**
     * The member's name in JSON.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * This field with a value for when the member is missing. JSON {@code null} is not
     * "missing": it is decoded by the codec, which rejects it unless it is {@code nullable}.
     * The value is always written when encoding, also when it equals {@code fallback}.
     *
     * @param fallback the value of a missing member; may be {@code null} (for a
     *                 {@code nullable} codec)
     * @return a new field with the same name, codec and getter
     * @throws IllegalStateException if this is an {@code optionalField} (it already has a
     *                               value for a missing member: {@code Optional.empty()})
     */
    public Field<R, T> orElse(T fallback) {
        return switch (slot) {
            case Required<T> required -> new Field<>(name, new WithDefault<>(required.codec(), fallback), getter);
            case WithDefault<T> withDefault -> new Field<>(name, new WithDefault<>(withDefault.codec(), fallback), getter);
            case Optionally<?> _ -> throw new IllegalStateException("optional field " + Messages.excerpt(name)
                    + " is already Optional.empty() when missing; orElse applies to field(...)");
        };
    }

    Slot<T> slot() {
        return slot;
    }

    /** Reads the field's value from {@code value}; an exception from the getter fails the encoding. */
    T get(R value, Path path) {
        try {
            return getter.apply(value);
        } catch (RuntimeException e) {
            throw Encoding.fail(path, "the getter failed: " + Messages.describe(e));
        }
    }

    /**
     * The field as text, for debugging.
     *
     * @return {@code Field[name]}
     */
    @Override
    public String toString() {
        return "Field[" + Messages.excerpt(name) + "]";
    }
}
