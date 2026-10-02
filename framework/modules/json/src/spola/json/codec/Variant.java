package spola.json.codec;

import java.util.Objects;
import spola.json.JsonObject;
import spola.json.JsonString;

/**
 * One subtype of a sealed hierarchy in {@link JsonCodec#sealed(String, java.util.function.Function, Variant...)}:
 * its tag (the discriminator's value in JSON) and the object codec of the subtype.
 *
 * <pre>{@code
 * static final Variant<Shape, Circle> CIRCLE = Variant.of("circle", CIRCLE_CODEC);
 * }</pre>
 *
 * <p>The sealed codec's selector returns {@link #tag(Object)} for each value; the type
 * parameters make sure a {@code Circle} can only be tagged by a variant for {@code Circle}.
 * Immutable and thread-safe.
 *
 * @param <T> the sealed type
 * @param <S> the subtype
 */
public final class Variant<T, S extends T> {

    private final String tag;
    private final ObjectCodec<S> codec;

    private Variant(String tag, ObjectCodec<S> codec) {
        JsonString.of(Objects.requireNonNull(tag, "tag")); // rejects unpaired surrogates
        this.tag = tag;
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * A variant.
     *
     * @param <T>   the sealed type
     * @param <S>   the subtype
     * @param tag   the discriminator's value for this subtype, such as {@code "circle"}
     * @param codec the subtype's object codec; its fields must not be named like the
     *              discriminator
     * @return the variant
     * @throws IllegalArgumentException if {@code tag} contains an unpaired surrogate
     */
    public static <T, S extends T> Variant<T, S> of(String tag, ObjectCodec<S> codec) {
        return new Variant<>(tag, codec);
    }

    /**
     * The tag.
     *
     * @return the discriminator's value for this subtype
     */
    public String tag() {
        return tag;
    }

    /**
     * The subtype's codec.
     *
     * @return the object codec
     */
    public ObjectCodec<S> codec() {
        return codec;
    }

    /**
     * Marks a value as this variant, for the selector of a sealed codec.
     *
     * @param value the value
     * @return the value with this variant, to be encoded by the sealed codec
     */
    public Tagged<T> tag(S value) {
        return new Tagged<>(this, value);
    }

    /**
     * The variant as text, for debugging.
     *
     * @return {@code Variant[tag]}
     */
    @Override
    public String toString() {
        return "Variant[" + Messages.excerpt(tag) + "]";
    }

    /** Encodes {@code value} as this variant's object, with the discriminator first. */
    JsonObject encode(S value, Path path, Encoding encoding, String discriminator) {
        return codec.encodeObject(value, path, encoding, discriminator, JsonString.of(tag));
    }

    /**
     * A value together with the variant that encodes it; what a sealed codec's selector
     * returns. Made only by {@link Variant#tag(Object)}, so the value always has the variant's
     * subtype.
     *
     * @param <T> the sealed type
     */
    public static final class Tagged<T> {

        private final Variant<T, ?> variant;
        private final Encoder encoder;

        private <S extends T> Tagged(Variant<T, S> variant, S value) {
            this.variant = variant;
            this.encoder = (path, encoding, discriminator) -> variant.encode(value, path, encoding, discriminator);
        }

        Variant<T, ?> variant() {
            return variant;
        }

        JsonObject encode(Path path, Encoding encoding, String discriminator) {
            return encoder.encode(path, encoding, discriminator);
        }

        /**
         * The tagged value as text, for debugging.
         *
         * @return {@code Tagged[tag]}
         */
        @Override
        public String toString() {
            return "Tagged[" + Messages.excerpt(variant.tag) + "]";
        }

        /** Encodes the captured value; keeps the subtype without a cast. */
        @FunctionalInterface
        private interface Encoder {
            JsonObject encode(Path path, Encoding encoding, String discriminator);
        }
    }
}
