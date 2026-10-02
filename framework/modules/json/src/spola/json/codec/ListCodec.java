package spola.json.codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import spola.json.JsonArray;
import spola.json.JsonValue;

/** {@link JsonCodecs#list}: a JSON array as an unmodifiable {@code List}. */
final class ListCodec<E> extends JsonCodec<List<E>> {

    private final JsonCodec<E> element;

    ListCodec(JsonCodec<E> element) {
        this.element = element;
    }

    @Override
    List<E> decodeAt(JsonValue json, Path path, Decoding decoding) {
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
        var out = new ArrayList<E>(values.size());
        for (int i = 0; i < values.size(); i++) {
            out.add(element.decodeAt(values.get(i), path.element(i), decoding));
        }
        decoding.exit(saved);
        // an ArrayList that never escapes, behind an unmodifiable view: elements may be null (nullable)
        return decoding.count() == before ? Collections.unmodifiableList(out) : null;
    }

    @Override
    JsonValue encodeAt(List<E> value, Path path, Encoding encoding) {
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
