package spola.json.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import spola.json.JsonArray;
import spola.json.JsonValue;

/** {@link JsonCodecs#set}: a JSON array without duplicates as an unmodifiable, ordered {@code Set}. */
final class SetCodec<E> extends JsonCodec<Set<E>> {

    private final JsonCodec<E> element;

    SetCodec(JsonCodec<E> element) {
        this.element = element;
    }

    @Override
    Set<E> decodeAt(JsonValue json, Path path, Decoding decoding) {
        if (!(json instanceof JsonArray array)) {
            decoding.report(path, Messages.expected("array", json));
            return null;
        }
        int saved = decoding.enter(path);
        if (saved < 0) {
            return null;
        }
        int before = decoding.count();
        var values = array.values();
        var firstIndex = LinkedHashMap.<E, Integer>newLinkedHashMap(values.size());
        for (int i = 0; i < values.size(); i++) {
            int beforeElement = decoding.count();
            E value = element.decodeAt(values.get(i), path.element(i), decoding);
            if (decoding.count() != beforeElement) {
                continue;
            }
            Integer first = firstIndex.putIfAbsent(value, i);
            if (first != null) {
                decoding.report(path.element(i), "duplicate element; equal to the element at index " + first);
            }
        }
        decoding.exit(saved);
        if (decoding.count() != before) {
            return null;
        }
        // the map keeps the elements in document order, each once
        var out = LinkedHashSet.<E>newLinkedHashSet(firstIndex.size());
        out.addAll(firstIndex.sequencedKeySet());
        return Collections.unmodifiableSequencedSet(out);
    }

    @Override
    JsonValue encodeAt(Set<E> value, Path path, Encoding encoding) {
        if (value == null) {
            throw Encoding.nullValue(path);
        }
        int saved = encoding.enter(path);
        var out = new ArrayList<JsonValue>(value.size());
        int i = 0;
        for (var e : value) {
            out.add(element.encodeAt(e, path.element(i++), encoding));
        }
        encoding.exit(saved);
        return JsonArray.copyOf(out);
    }
}
