package spola.json.codec;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;

/** {@link JsonCodec#sealed}: a discriminator member picks the variant. */
final class SealedCodec<T> extends JsonCodec<T> {

    private final String discriminator;
    private final Function<? super T, Variant.Tagged<T>> selector;
    private final Map<String, Variant<T, ? extends T>> byTag;
    private final Set<Variant<T, ?>> known;
    private final String tags;

    SealedCodec(String discriminator, Function<? super T, Variant.Tagged<T>> selector,
            List<Variant<T, ? extends T>> variants) {
        JsonString.of(Objects.requireNonNull(discriminator, "discriminator")); // rejects unpaired surrogates
        this.discriminator = discriminator;
        this.selector = Objects.requireNonNull(selector, "selector");
        if (variants.isEmpty()) {
            throw new IllegalArgumentException("a sealed codec needs at least one variant");
        }
        this.byTag = new LinkedHashMap<>();
        this.known = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var variant : variants) {
            if (byTag.putIfAbsent(variant.tag(), variant) != null) {
                throw new IllegalArgumentException("two variants have the tag " + Messages.excerpt(variant.tag()));
            }
            if (variant.codec().hasMember(discriminator)) {
                throw new IllegalArgumentException("the codec of variant " + Messages.excerpt(variant.tag())
                        + " has a field named like the discriminator " + Messages.excerpt(discriminator));
            }
            known.add(variant);
        }
        this.tags = Messages.choices(byTag.keySet());
    }

    @Override
    T decodeAt(JsonValue json, Path path, Decoding decoding) {
        if (!(json instanceof JsonObject object)) {
            decoding.report(path, Messages.expected("object", json));
            return null;
        }
        var at = path.member(discriminator);
        var tag = object.members().get(discriminator);
        if (tag == null) {
            decoding.report(at, "missing discriminator; expected one of " + tags);
            return null;
        }
        if (!(tag instanceof JsonString string)) {
            decoding.report(at, Messages.expected("a string (one of " + tags + ")", tag));
            return null;
        }
        var variant = byTag.get(string.value());
        if (variant == null) {
            decoding.report(at, "unknown tag " + Messages.excerpt(string.value()) + "; expected one of " + tags);
            return null;
        }
        return variant.codec().decodeObject(object, path, decoding, discriminator);
    }

    @Override
    JsonValue encodeAt(T value, Path path, Encoding encoding) {
        if (value == null) {
            throw Encoding.nullValue(path);
        }
        Variant.Tagged<T> tagged;
        try {
            tagged = selector.apply(value);
        } catch (RuntimeException e) {
            throw Encoding.fail(path, "the selector failed: " + Messages.describe(e));
        }
        if (tagged == null) {
            throw Encoding.fail(path, "the selector returned null instead of a tagged value");
        }
        if (!known.contains(tagged.variant())) {
            throw Encoding.fail(path, "the selector returned " + tagged.variant()
                    + ", which is not one of the variants of this sealed codec (" + tags + ")");
        }
        return tagged.encode(path, encoding, discriminator);
    }
}
