package spola.json.codec;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import spola.json.JsonObject;
import spola.json.JsonValue;

/**
 * A codec between a Java value (usually a record) and a JSON object with named members, made
 * with {@link JsonCodec#object(Constructors.Of2, Field, Field)} (1 to 16 fields) or
 * {@link JsonCodec#object(List, Function)} (any number).
 *
 * <p><b>Decoding</b> goes through the object's members in document order: each known member is
 * decoded by its field's codec, and each unknown member is a problem (unless
 * {@link #ignoringUnknown()}). Then every missing member is handled: a problem for a required
 * field, the default for {@link Field#orElse(Object)}, {@code Optional.empty()} for an
 * {@code optionalField}. The constructor is called only when every field decoded without a
 * problem; an exception it throws (such as an {@code IllegalArgumentException} from a compact
 * constructor) becomes a problem on the object's path with the exception's message, cleaned
 * and cut to 200 characters.
 *
 * <p><b>Encoding</b> writes the members in the order of the fields, leaving out empty
 * {@code optionalField}s.
 *
 * <p>Unknown members are rejected by default, unlike Spring Boot's Jackson
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}): a typo in a member name or a member the client
 * should not set (mass assignment) is reported instead of passing silently.
 *
 * @param <R> the type of the value
 */
public final class ObjectCodec<R> extends JsonCodec<R> {

    private final List<Field<R, ?>> fields;
    private final Map<String, Integer> byName;
    private final IdentityHashMap<Field<R, ?>, Integer> byField;
    private final Function<? super FieldValues<R>, ? extends R> constructor;
    private final boolean ignoreUnknown;
    private final String unknownMessage;

    ObjectCodec(List<? extends Field<R, ?>> fields, Function<? super FieldValues<R>, ? extends R> constructor,
            boolean ignoreUnknown) {
        this.fields = List.copyOf(fields);
        this.constructor = Objects.requireNonNull(constructor, "constructor");
        this.ignoreUnknown = ignoreUnknown;
        this.byName = HashMap.newHashMap(this.fields.size());
        this.byField = new IdentityHashMap<>(this.fields.size());
        for (int i = 0; i < this.fields.size(); i++) {
            var field = this.fields.get(i);
            if (byName.putIfAbsent(field.name(), i) != null) {
                throw new IllegalArgumentException("two fields have the member name " + Messages.excerpt(field.name()));
            }
            byField.put(field, i);
        }
        this.unknownMessage = this.fields.isEmpty() ? "unknown member; the object has no members"
                : "unknown member; expected one of " + Messages.choices(names());
    }

    /**
     * This codec, but accepting (and skipping) members it has no field for. Use it where the
     * other side may add members you do not read, such as a response from another service.
     *
     * @return a new codec with the same fields and constructor
     */
    public ObjectCodec<R> ignoringUnknown() {
        return new ObjectCodec<>(fields, constructor, true);
    }

    /** The members' names in field order. */
    List<String> names() {
        return fields.stream().map(Field::name).toList();
    }

    boolean hasMember(String name) {
        return byName.containsKey(name);
    }

    @Override
    R decodeAt(JsonValue json, Path path, Decoding decoding) {
        if (!(json instanceof JsonObject object)) {
            decoding.report(path, Messages.expected("object", json));
            return null;
        }
        return decodeObject(object, path, decoding, null);
    }

    /** Decodes {@code object}, skipping the member {@code skip} (a sealed codec's discriminator). */
    R decodeObject(JsonObject object, Path path, Decoding decoding, String skip) {
        int saved = decoding.enter(path);
        if (saved < 0) {
            return null;
        }
        int before = decoding.count();
        int n = fields.size();
        var values = new Object[n];
        var seen = new boolean[n];
        for (var member : object.members().entrySet()) {
            var name = member.getKey();
            Integer at = byName.get(name);
            if (at == null) {
                if (!ignoreUnknown && !name.equals(skip)) {
                    decoding.report(path.member(name), unknownMessage);
                }
                continue;
            }
            seen[at] = true;
            values[at] = fields.get(at).slot().present(member.getValue(), path.member(name), decoding);
        }
        for (int i = 0; i < n; i++) {
            if (!seen[i]) {
                var field = fields.get(i);
                values[i] = field.slot().missing(path.member(field.name()), decoding);
            }
        }
        decoding.exit(saved);
        if (decoding.count() != before) {
            return null;
        }
        try {
            return constructor.apply(new FieldValues<>(byField, values));
        } catch (FieldValues.NotAField e) {
            throw e;
        } catch (RuntimeException e) {
            decoding.report(path, Messages.rejected(e));
            return null;
        }
    }

    @Override
    JsonValue encodeAt(R value, Path path, Encoding encoding) {
        return encodeObject(value, path, encoding, null, null);
    }

    /** Encodes {@code value}, with the member {@code tag} first when it is not {@code null}. */
    JsonObject encodeObject(R value, Path path, Encoding encoding, String tag, JsonValue tagValue) {
        if (value == null) {
            throw Encoding.nullValue(path);
        }
        int saved = encoding.enter(path);
        var out = JsonObject.builder();
        if (tag != null) {
            out.put(tag, tagValue);
        }
        for (var field : fields) {
            var member = encodeField(field, value, path.member(field.name()), encoding);
            if (member != null) {
                out.put(field.name(), member);
            }
        }
        encoding.exit(saved);
        return out.build();
    }

    private static <R, T> JsonValue encodeField(Field<R, T> field, R value, Path path, Encoding encoding) {
        return field.slot().encode(field.get(value, path), path, encoding);
    }
}
