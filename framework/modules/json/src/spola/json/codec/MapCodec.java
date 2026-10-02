package spola.json.codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import spola.json.JsonObject;
import spola.json.JsonValue;

/** {@link JsonCodecs#map}: a JSON object as an unmodifiable {@code Map<String, V>} in member order. */
final class MapCodec<V> extends JsonCodec<Map<String, V>> {

    private final JsonCodec<V> value;

    MapCodec(JsonCodec<V> value) {
        this.value = value;
    }

    @Override
    Map<String, V> decodeAt(JsonValue json, Path path, Decoding decoding) {
        if (!(json instanceof JsonObject object)) {
            decoding.report(path, Messages.expected("object", json));
            return null;
        }
        int saved = decoding.enter(path);
        if (saved < 0) {
            return null;
        }
        int before = decoding.count();
        var out = LinkedHashMap.<String, V>newLinkedHashMap(object.size());
        for (var member : object.members().entrySet()) {
            out.put(member.getKey(), value.decodeAt(member.getValue(), path.key(member.getKey()), decoding));
        }
        decoding.exit(saved);
        return decoding.count() == before ? Collections.unmodifiableSequencedMap(out) : null;
    }

    @Override
    JsonValue encodeAt(Map<String, V> map, Path path, Encoding encoding) {
        if (map == null) {
            throw Encoding.nullValue(path);
        }
        int saved = encoding.enter(path);
        var out = JsonObject.builder();
        for (var entry : map.entrySet()) {
            var name = entry.getKey();
            if (name == null) {
                throw Encoding.fail(path, "the map has a null key");
            }
            var member = path.key(name);
            var encoded = value.encodeAt(entry.getValue(), member, encoding);
            try {
                out.put(name, encoded);
            } catch (IllegalArgumentException e) {
                // a name with an unpaired surrogate has no JSON form
                throw Encoding.fail(member, "invalid member name: " + Messages.describe(e));
            }
        }
        encoding.exit(saved);
        return out.build();
    }
}
