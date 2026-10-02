package spola.json.codec;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import spola.json.JsonString;
import spola.json.JsonValue;

/** {@link JsonCodecs#enumOf}: an enum constant as a string, by a name the application chooses. */
final class EnumCodec<E extends Enum<E>> extends JsonCodec<E> {

    private final Map<String, E> byName;
    private final IdentityHashMap<E, JsonString> names;
    private final String allowed;

    EnumCodec(E[] values, Function<? super E, String> name) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(name, "name");
        this.byName = LinkedHashMap.newLinkedHashMap(values.length);
        this.names = new IdentityHashMap<>(values.length);
        for (var value : values) {
            Objects.requireNonNull(value, "an element of values");
            var text = Objects.requireNonNull(name.apply(value), "the name of " + value.name());
            var json = JsonString.of(text); // rejects unpaired surrogates
            var previous = byName.putIfAbsent(text, value);
            if (previous != null && previous != value) {
                throw new IllegalArgumentException(Messages.excerpt(previous.name()) + " and " + Messages.excerpt(value.name())
                        + " have the same name " + Messages.excerpt(text));
            }
            names.put(value, json);
        }
        this.allowed = Messages.choices(byName.keySet());
    }

    @Override
    E decodeAt(JsonValue json, Path path, Decoding decoding) {
        if (!(json instanceof JsonString string)) {
            decoding.report(path, Messages.expected("a string (one of " + allowed + ")", json));
            return null;
        }
        var value = byName.get(string.value());
        if (value == null) {
            decoding.report(path, "unknown value " + Messages.excerpt(string.value()) + "; expected one of " + allowed);
        }
        return value;
    }

    @Override
    JsonValue encodeAt(E value, Path path, Encoding encoding) {
        if (value == null) {
            throw Encoding.nullValue(path);
        }
        var json = names.get(value);
        if (json == null) {
            throw Encoding.fail(path, Messages.excerpt(value.name()) + " is not one of the values given to this enum codec");
        }
        return json;
    }
}
