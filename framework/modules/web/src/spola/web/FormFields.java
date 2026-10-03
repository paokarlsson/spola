package spola.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import spola.http.Headers;
import spola.validation.Validated;

/**
 * The fields of one form or query as a {@link FormBinder} reads them, and the collector of
 * every binding error. It lives for a single binding, like {@code spola.validation.Errors}
 * for a single validation: it is not thread-safe, and using it after the binding has returned
 * throws {@link IllegalStateException}.
 *
 * <p>A method never throws for bad input: it records an error and returns a placeholder
 * ({@code null}, an empty {@code Optional}, {@code false} or a shorter list), so the binder goes
 * on and every error of the form is reported at once, as Spring's {@code BindingResult} does.
 * When binding is over and errors were recorded, the binding ends in a {@link WebException}
 * 400 whose {@link WebException#errors()} lists them, and the value the binder built is
 * thrown away; a handler never gets a half-bound value.
 *
 * <p><b>Codes and messages.</b> Each error has a path, a message and a code:
 *
 * <ul>
 *   <li>{@code required}, {@code is required}: a {@link #required} field is missing;</li>
 *   <li>{@code type}, {@code expected <type>} (the {@link Param#typeName()}, such as
 *       {@code expected int}; {@code expected boolean} for {@link #flag}): a value does not
 *       convert;</li>
 *   <li>{@code single}, {@code must have a single value}: a field read with {@link #required},
 *       {@link #optional} or {@link #flag} has more than one value. The first one is not taken
 *       silently, as a guard against parameter pollution.</li>
 * </ul>
 *
 * <p><b>No value is ever echoed.</b> Messages hold the expected type, never the value: a field
 * can be a password. (The messages of {@link Param} for path variables and query parameters do
 * quote the value; these do not.)
 *
 * <p><b>Empty values.</b> An empty value ({@code age=}, which an HTML form sends for an empty
 * input) counts as missing for every {@code Param} except {@link Param#STRING}
 * ({@code param == Param.STRING}): {@code required} reports {@code required}, {@code optional}
 * gives {@code Optional.empty()}, {@code all} leaves the element out (an error of a later
 * element keeps the element's position in the form as its index), and {@code flag} gives
 * {@code false}. For {@code Param.STRING}, {@code ""} is a value, so that a validator's
 * {@code notBlank()} decides about it. A field that is present only with an empty value is not
 * "more than one value" unless it is sent more than once.
 *
 * <p><b>Paths</b> use the notation of {@code spola.json.codec} and {@code spola.validation}, so
 * binding errors and violations read alike: {@code $.email} for a name that is an identifier
 * ({@code [A-Za-z_$][A-Za-z0-9_$]*}), {@code $["first name"]} for any other name (escaped like a
 * JSON string, every character outside printable ASCII as {@code \}{@code uXXXX}) and
 * {@code $.interests[2]} for an element of {@link #all}. Names come from the binder's code, so
 * they are written in full.
 *
 * <p><b>Order and limit.</b> Errors are kept in the order the binder calls the methods, not in
 * the order of the form. At most {@value Validated#MAX_VIOLATIONS} are kept (the limit of
 * validation and of {@link Problem#MAX_ERRORS}); later ones are only counted, and their path is
 * never rendered. Unknown fields (a CSRF token, the submit button) are ignored.
 */
public final class FormFields {

    /** The subject of the detail for a form body. */
    static final String BODY = "Request body";

    /** The subject of the detail for a query. */
    static final String QUERY = "Query";

    private static final String REQUIRED = "required";
    private static final String TYPE = "type";
    private static final String SINGLE = "single";

    private final Map<String, List<String>> fields;
    private final List<Problem.FieldError> kept = new ArrayList<>();
    private int count;
    private boolean closed;

    private FormFields(Map<String, List<String>> fields) {
        this.fields = fields;
    }

    /**
     * Runs {@code binder} over {@code fields} with a fresh collector: the value, or the 400 with
     * the collected errors ({@code subject} names the source in the detail), or the binder's
     * exception if it threw without collected errors.
     */
    static <T> T bind(FormBinder<T> binder, Map<String, List<String>> fields, String subject) {
        Objects.requireNonNull(binder, "binder");
        var collector = new FormFields(fields);
        T value;
        try {
            value = binder.bind(collector);
        } catch (RuntimeException e) {
            if (collector.count > 0) {
                throw collector.failure(subject);
            }
            throw e;
        } finally {
            collector.closed = true;
        }
        if (collector.count > 0) {
            throw collector.failure(subject);
        }
        return value;
    }

    /**
     * The one value of a field that must be present, converted with {@code param}.
     *
     * @param name  the field's decoded name
     * @param param the conversion
     * @param <V>   the converted type
     * @return the value, or {@code null} after recording {@code required} (missing, or empty for
     *         a {@code param} other than {@code Param.STRING}), {@code single} (more than one
     *         value) or {@code type} (the value does not convert)
     * @throws NullPointerException  if {@code name} or {@code param} is {@code null}
     * @throws IllegalStateException if the binding is over, or if {@code param}'s parser
     *                               returned {@code null}
     */
    public <V> V required(String name, Param<V> param) {
        Objects.requireNonNull(param, "param");
        if (values(name).isEmpty()) {
            reject(name, -1, REQUIRED, "is required");
            return null;
        }
        var text = single(name);
        if (text == null) {
            return null;
        }
        if (missing(text, param)) {
            reject(name, -1, REQUIRED, "is required");
            return null;
        }
        return convert(name, -1, text, param);
    }

    /**
     * The one value of a field that may be absent, converted with {@code param}.
     *
     * @param name  the field's decoded name
     * @param param the conversion
     * @param <V>   the converted type
     * @return the value; empty if the field is missing or empty (for a {@code param} other than
     *         {@code Param.STRING}), or after recording {@code single} or {@code type}
     * @throws NullPointerException  if {@code name} or {@code param} is {@code null}
     * @throws IllegalStateException if the binding is over, or if {@code param}'s parser
     *                               returned {@code null}
     */
    public <V> Optional<V> optional(String name, Param<V> param) {
        Objects.requireNonNull(param, "param");
        var text = single(name);
        if (text == null || missing(text, param)) {
            return Optional.empty();
        }
        return Optional.ofNullable(convert(name, -1, text, param));
    }

    /**
     * Every value of a field, in the order of the form, each converted with {@code param}.
     * An element that does not convert is recorded as {@code type} at {@code $.name[i]}, where
     * {@code i} is its position among the field's values, and left out. Empty values are left
     * out for a {@code param} other than {@code Param.STRING}.
     *
     * @param name  the field's decoded name
     * @param param the conversion
     * @param <V>   the converted type
     * @return the converted values, unmodifiable; empty (and valid) if the field is missing
     * @throws NullPointerException  if {@code name} or {@code param} is {@code null}
     * @throws IllegalStateException if the binding is over, or if {@code param}'s parser
     *                               returned {@code null}
     */
    public <V> List<V> all(String name, Param<V> param) {
        Objects.requireNonNull(param, "param");
        var values = values(name);
        var result = new ArrayList<V>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var text = values.get(i);
            if (missing(text, param)) {
                continue;
            }
            var value = convert(name, i, text, param);
            if (value != null) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    /**
     * A checkbox: {@code false} if the field is missing or empty (a browser sends nothing for an
     * unchecked box), {@code true} for {@code on} (what a browser sends for a checked box without
     * a {@code value}), {@code true} or {@code 1}, {@code false} for {@code false} or {@code 0}.
     * Anything else is recorded as {@code type} ({@code expected boolean}), more than one value
     * as {@code single}; both give {@code false}.
     *
     * @param name the field's decoded name
     * @return the checkbox's state
     * @throws NullPointerException  if {@code name} is {@code null}
     * @throws IllegalStateException if the binding is over
     */
    public boolean flag(String name) {
        var text = single(name);
        if (text == null || text.isEmpty()) {
            return false;
        }
        return switch (text) {
            case "on", "true", "1" -> true;
            case "false", "0" -> false;
            default -> {
                reject(name, -1, TYPE, "expected boolean");
                yield false;
            }
        };
    }

    /** The fields this collector reads: names in the order they first appear, values in order. */
    @Override
    public String toString() {
        return "FormFields" + fields.keySet().stream().limit(10).map(Text::quote).toList();
    }

    /** The values of {@code name} (empty if it is missing); throws once the binding is over. */
    private List<String> values(String name) {
        Objects.requireNonNull(name, "name");
        if (closed) {
            throw new IllegalStateException("this FormFields belongs to a binding that is over");
        }
        return fields.getOrDefault(name, List.of());
    }

    /** The one value of {@code name}; {@code null} if it is missing (nothing recorded) or after recording {@code single}. */
    private String single(String name) {
        var values = values(name);
        if (values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            reject(name, -1, SINGLE, "must have a single value");
            return null;
        }
        return values.getFirst();
    }

    /** Whether {@code text} counts as missing: an empty value does for every {@code param} except {@code Param.STRING}. */
    private static boolean missing(String text, Param<?> param) {
        return text.isEmpty() && param != Param.STRING;
    }

    private <V> V convert(String name, int index, String text, Param<V> param) {
        var value = param.convertOrNull(text);
        if (value == null) {
            reject(name, index, TYPE, "expected " + Text.escape(param.typeName(), Text.MAX_QUOTED));
        }
        return value;
    }

    /** Records an error; the path is rendered only if the error is kept. */
    private void reject(String name, int index, String code, String message) {
        if (kept.size() < Validated.MAX_VIOLATIONS) {
            kept.add(new Problem.FieldError(path(name, index), message, Optional.of(code)));
        }
        if (count < Integer.MAX_VALUE) {
            count++;
        }
    }

    private WebException failure(String subject) {
        var detail = subject + " has " + count + (count == 1 ? " problem." : " problems.")
                + (count > kept.size() ? " The first " + kept.size() + " are listed." : "");
        return new WebException(400, null, detail, Headers.EMPTY, kept);
    }

    /**
     * {@code $.name}, {@code $["name"]}, then {@code [index]} if {@code index >= 0}: a private
     * copy of the member and element rendering of {@code spola.validation.Path} (itself the
     * algorithm of {@code spola.json.codec.Path}), since neither module exports it. A test
     * compares the two for the same names.
     */
    static String path(String name, int index) {
        var out = new StringBuilder(name.length() + 16).append('$');
        if (isIdentifier(name)) {
            out.append('.').append(name);
        } else {
            out.append("[\"");
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c >= 0x20 && c < 0x7F) {
                    if (c == '"' || c == '\\') {
                        out.append('\\');
                    }
                    out.append(c);
                } else {
                    var hex = Integer.toHexString(c);
                    out.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                }
            }
            out.append("\"]");
        }
        if (index >= 0) {
            out.append('[').append(index).append(']');
        }
        return out.toString();
    }

    /** {@code [A-Za-z_$][A-Za-z0-9_$]*}. */
    private static boolean isIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
            if (!letter && !(i > 0 && c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }
}
