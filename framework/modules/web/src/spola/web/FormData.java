package spola.web;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The fields of an {@code application/x-www-form-urlencoded} body, from
 * {@link WebRequest#readForm()}: the counterpart of the form fields Spring binds to
 * {@code @RequestParam} and {@code @ModelAttribute}. Immutable and thread-safe.
 *
 * <pre>{@code
 * FormData form = request.readForm();
 * String name = form.first("name").orElse("");
 * int quantity = form.required("quantity", Param.INT);
 * List<String> tags = form.all("tag");
 * }</pre>
 *
 * <p>Names and values are decoded ({@code +} is a space, strict percent-decoding to UTF-8);
 * names keep the order they first appear in, and the values of a name keep theirs. The form
 * holds only the body: the query is read with {@link WebRequest#query(String)}, never merged
 * in. A value that does not convert, or a missing required field, is a {@link WebException}
 * 400 whose detail says {@code form field} and the name (and the value, escaped and at most 40
 * characters).
 */
public final class FormData {

    private final Map<String, List<String>> fields;

    FormData(Map<String, List<String>> fields) {
        this.fields = fields;
    }

    /**
     * The first value of a field.
     *
     * @param name the decoded name
     * @return the value (empty text for {@code name} or {@code name=}), or empty if absent
     */
    public Optional<String> first(String name) {
        var values = fields.get(Objects.requireNonNull(name, "name"));
        return values == null ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Every value of a field, in the order of the body.
     *
     * @param name the decoded name
     * @return the values, unmodifiable, empty if absent
     */
    public List<String> all(String name) {
        return fields.getOrDefault(Objects.requireNonNull(name, "name"), List.of());
    }

    /**
     * The first value of a field converted with {@code param}.
     *
     * @param name  the decoded name
     * @param param the conversion
     * @param <T>   the converted type
     * @return the value, or empty if the field is absent
     * @throws WebException 400 if the value does not convert
     */
    public <T> Optional<T> get(String name, Param<T> param) {
        Objects.requireNonNull(param, "param");
        return first(name).map(value -> param.convert("form field", name, value));
    }

    /**
     * The first value of a field that must be present, converted with {@code param}.
     *
     * @param name  the decoded name
     * @param param the conversion
     * @param <T>   the converted type
     * @return the value
     * @throws WebException 400 if the field is absent or the value does not convert
     */
    public <T> T required(String name, Param<T> param) {
        return get(name, param).orElseThrow(() -> new WebException(400, "Bad Request",
                "Missing form field " + Text.quote(name) + "."));
    }

    /**
     * Every field: names in the order they first appear, values in order.
     *
     * @return the fields, unmodifiable
     */
    public Map<String, List<String>> asMap() {
        return fields;
    }

    /**
     * Whether the form has no fields.
     *
     * @return whether it is empty
     */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** Equal when the fields, names and values in order, are equal. */
    @Override
    public boolean equals(Object other) {
        return other instanceof FormData form && form.fields.equals(fields)
                && List.copyOf(form.fields.keySet()).equals(List.copyOf(fields.keySet()));
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    /**
     * The field names, escaped and shortened, without values (values may be secrets and do not
     * belong in log lines).
     */
    @Override
    public String toString() {
        var out = new StringBuilder("FormData[");
        int shown = 0;
        for (var name : fields.keySet()) {
            if (shown == 10) {
                out.append(", ...");
                break;
            }
            out.append(shown++ == 0 ? "" : ", ").append(Text.quote(name));
        }
        return out.append(']').toString();
    }
}
