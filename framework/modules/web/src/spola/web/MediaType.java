package spola.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A media type (RFC 9110 §8.3.1) such as {@code application/json;charset=utf-8}, or a media
 * range with wildcards ({@code text/*}, {@code *}{@code /*}): the counterpart of Spring's
 * {@code MediaType}, parsed strictly.
 *
 * <p><b>Syntax.</b> {@code type "/" subtype *( OWS ";" OWS parameter )}, where the type, the
 * subtype and every parameter name is a {@code token} (RFC 9110 §5.6.2), and a parameter value
 * is a {@code token} or a {@code quoted-string} (§5.6.4, with {@code \} escapes). OWS is spaces
 * and tabs. The type, the subtype and the parameter names are made lowercase; parameter values
 * keep their case, except {@code charset}, whose value is made lowercase (charset names are
 * compared without regard to case). Everything else is rejected with an
 * {@link IllegalArgumentException} whose message is short printable ASCII: a missing or empty
 * type or subtype, a wildcard type with a concrete subtype ({@code *}{@code /json}), an empty
 * parameter ({@code ;;}, a trailing {@code ;}), a parameter without {@code =} or without a
 * value, the same parameter name twice, control characters (other than tab), non-ASCII
 * characters, an unterminated quoted string, whitespace at either end, more than
 * 64 parameters, and more than
 * 1 024 characters.
 *
 * <p>Two media types are equal when type, subtype and parameters are equal (parameter order
 * does not matter). {@link #toString()} is the canonical form: lowercase type and subtype,
 * {@code ;name=value} without spaces, values written as a token when they are one and quoted
 * otherwise.
 *
 * @param type       the type, lowercase, {@code *} for any
 * @param subtype    the subtype, lowercase, {@code *} for any
 * @param parameters the parameters in their order, names lowercase, unmodifiable
 */
public record MediaType(String type, String subtype, Map<String, String> parameters) {

    /** {@code application/json}. */
    public static final MediaType APPLICATION_JSON = of("application", "json");

    /** {@code application/problem+json} (RFC 9457). */
    public static final MediaType APPLICATION_PROBLEM_JSON = of("application", "problem+json");

    /** {@code text/plain}. */
    public static final MediaType TEXT_PLAIN = of("text", "plain");

    /** {@code *}{@code /*}: every media type. */
    public static final MediaType ALL = of("*", "*");

    /**
     * Checks and normalizes the components: type, subtype and parameter names must be tokens
     * and are made lowercase, as is the value of {@code charset}; values must be printable ASCII,
     * spaces or tabs.
     *
     * @throws NullPointerException     if a component, a name or a value is {@code null}
     * @throws IllegalArgumentException if a part is not a token, the type is {@code *} with a
     *                                  concrete subtype, a value has other characters, two names
     *                                  are equal without regard to case, or there are more than
     *                                  64 parameters
     */
    public MediaType {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subtype, "subtype");
        Objects.requireNonNull(parameters, "parameters");
        if (!MediaTypeParser.isToken(type) || !MediaTypeParser.isToken(subtype)) {
            throw new IllegalArgumentException("the type and the subtype must be tokens (RFC 9110 5.6.2): "
                    + Text.quote(type) + "/" + Text.quote(subtype));
        }
        type = type.toLowerCase(Locale.ROOT);
        subtype = subtype.toLowerCase(Locale.ROOT);
        if (type.equals("*") && !subtype.equals("*")) {
            throw new IllegalArgumentException("a wildcard type needs a wildcard subtype (*/*), not */"
                    + Text.escape(subtype, Text.MAX_QUOTED));
        }
        if (parameters.size() > MediaTypeParser.MAX_PARAMETERS) {
            throw new IllegalArgumentException("more than " + MediaTypeParser.MAX_PARAMETERS + " parameters");
        }
        var copy = new LinkedHashMap<String, String>();
        for (var entry : parameters.entrySet()) {
            var name = Objects.requireNonNull(entry.getKey(), "parameter name");
            var value = Objects.requireNonNull(entry.getValue(), "parameter value");
            if (!MediaTypeParser.isToken(name)) {
                throw new IllegalArgumentException("parameter name " + Text.quote(name) + " is not a token");
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c < 0x20 && c != '\t') || c > 0x7E) {
                    throw new IllegalArgumentException("parameter " + Text.quote(name)
                            + ": the value must be printable ASCII, spaces or tabs");
                }
            }
            name = name.toLowerCase(Locale.ROOT);
            if (copy.put(name, name.equals("charset") ? value.toLowerCase(Locale.ROOT) : value) != null) {
                throw new IllegalArgumentException("parameter " + Text.quote(name) + " given twice");
            }
        }
        parameters = Collections.unmodifiableMap(copy);
    }

    /**
     * Parses a media type strictly; see the class documentation for the syntax and the errors.
     * A {@code q} parameter has no special meaning here (it does in {@link AcceptHeader}).
     *
     * @param text the text, such as a {@code Content-Type} value
     * @return the media type
     * @throws IllegalArgumentException if the text is not a media type; the message is short,
     *                                  printable ASCII and quotes at most 40 characters of it
     * @throws NullPointerException     if {@code text} is {@code null}
     */
    public static MediaType parse(String text) {
        return MediaTypeParser.parse(Objects.requireNonNull(text, "text"), false).mediaType();
    }

    /**
     * A media type without parameters.
     *
     * @param type    the type, a token, or {@code *}
     * @param subtype the subtype, a token, or {@code *}
     * @return the media type, lowercase
     * @throws IllegalArgumentException if a part is not a token or the type is {@code *} with a
     *                                  concrete subtype
     */
    public static MediaType of(String type, String subtype) {
        return new MediaType(type, subtype, Map.of());
    }

    /**
     * Whether this media type, as a range, includes {@code other}: the type is {@code *} or
     * equal, the subtype is {@code *} or equal, and every parameter of this one is in
     * {@code other} with an equal value ({@code text/html;level=1} includes
     * {@code text/html;level=1;charset=utf-8} but not {@code text/html}). {@code other}'s extra
     * parameters do not matter. A concrete type never includes a wildcard.
     *
     * <p>One exception: a range's {@code charset=utf-8} (any case, quoted or not) is also
     * satisfied by a JSON media type ({@link #isJson()}) that has no {@code charset}, because
     * JSON is UTF-8 by definition (RFC 8259 §8.1) and Spola always writes it as UTF-8. So
     * {@code application/json;charset=utf-8} includes {@code application/json}, which is what
     * many clients (OkHttp/Retrofit, Spring's {@code APPLICATION_JSON_UTF8_VALUE}) send. Any
     * other charset still excludes it: {@code application/json;charset=iso-8859-1} does not
     * include {@code application/json}.
     *
     * @param other the media type
     * @return whether it is included
     */
    public boolean includes(MediaType other) {
        Objects.requireNonNull(other, "other");
        if (!type.equals("*") && !type.equals(other.type)) {
            return false;
        }
        if (!subtype.equals("*") && !subtype.equals(other.subtype)) {
            return false;
        }
        for (var entry : parameters.entrySet()) {
            var value = other.parameters.get(entry.getKey());
            if (value == null && entry.getKey().equals("charset") && entry.getValue().equals("utf-8")
                    && other.isJson()) {
                continue; // JSON without a charset is UTF-8 (RFC 8259 8.1)
            }
            if (!entry.getValue().equals(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this is JSON: {@code application/json} or {@code application/<name>+json} (a
     * structured syntax suffix, RFC 6839), whatever the parameters.
     *
     * @return whether it is JSON
     */
    public boolean isJson() {
        return type.equals("application")
                && (subtype.equals("json") || (subtype.length() > 5 && subtype.endsWith("+json")));
    }

    /**
     * Whether the type or the subtype is {@code *}.
     *
     * @return whether this is a range rather than one media type
     */
    public boolean isWildcard() {
        return type.equals("*") || subtype.equals("*");
    }

    /**
     * The {@code charset} parameter, lowercase.
     *
     * @return the charset, or empty
     */
    public Optional<String> charset() {
        return Optional.ofNullable(parameters.get("charset"));
    }

    /**
     * The canonical form: {@code type/subtype}, then {@code ;name=value} for every parameter,
     * the value quoted (with {@code "} and {@code \} escaped) unless it is a token.
     *
     * @return the media type as text, parseable by {@link #parse}
     */
    @Override
    public String toString() {
        var out = new StringBuilder(type).append('/').append(subtype);
        for (var entry : parameters.entrySet()) {
            out.append(';').append(entry.getKey()).append('=');
            var value = entry.getValue();
            if (MediaTypeParser.isToken(value)) {
                out.append(value);
            } else {
                out.append('"');
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c == '"' || c == '\\') {
                        out.append('\\');
                    }
                    out.append(c);
                }
                out.append('"');
            }
        }
        return out.toString();
    }
}
