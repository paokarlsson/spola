package spola.web;

import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * The strict parser behind {@link MediaType#parse} and {@link AcceptHeader#parse}: RFC 9110
 * §8.3.1 ({@code type "/" subtype *( OWS ";" OWS parameter )}), with {@code token} (§5.6.2)
 * and {@code quoted-string} (§5.6.4) values, and for an {@code Accept} element the weight
 * ({@code q}, §12.5.1) followed by ignored extensions. One instance parses one text, on one
 * thread.
 */
final class MediaTypeParser {

    /** The longest media type (or {@code Accept} element) accepted, in characters. */
    static final int MAX_LENGTH = 1_024;

    /** The most parameters of one media type (or {@code Accept} element, extensions included). */
    static final int MAX_PARAMETERS = 64;

    /** A parsed media type, and for an {@code Accept} element its quality in thousandths. */
    record Parsed(MediaType mediaType, int quality) {
    }

    private final String text;
    private final boolean accept;
    private int pos;

    private MediaTypeParser(String text, boolean accept) {
        this.text = text;
        this.accept = accept;
    }

    /** Parses a whole media type; {@code accept} also reads a weight and extensions. */
    static Parsed parse(String text, boolean accept) {
        return new MediaTypeParser(text, accept).parse();
    }

    private Parsed parse() {
        if (text.length() > MAX_LENGTH) {
            throw fail("longer than " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F) {
                throw fail("non-ASCII character at index " + i);
            }
            if (c < 0x20 && c != '\t' || c == 0x7F) {
                throw fail("control character at index " + i);
            }
        }
        if (text.isEmpty()) {
            throw fail("empty");
        }
        var type = token().toLowerCase(Locale.ROOT);
        if (type.isEmpty()) {
            throw fail("missing type");
        }
        if (pos == text.length()) {
            throw fail("missing '/' and subtype");
        }
        if (text.charAt(pos) != '/') {
            throw fail("expected '/' after the type at index " + pos);
        }
        pos++;
        var subtype = token().toLowerCase(Locale.ROOT);
        if (subtype.isEmpty()) {
            throw fail("missing subtype");
        }
        if (type.equals("*") && !subtype.equals("*")) {
            throw fail("a wildcard type needs a wildcard subtype (*/*)");
        }
        var parameters = new LinkedHashMap<String, String>();
        int quality = -1;
        int count = 0;
        while (true) {
            int before = pos;
            skipWhitespace();
            if (pos == text.length()) {
                if (pos != before) {
                    throw fail("whitespace at the end");
                }
                break;
            }
            if (text.charAt(pos) != ';') {
                throw fail("expected ';' at index " + pos);
            }
            pos++;
            skipWhitespace();
            if (++count > MAX_PARAMETERS) {
                throw fail("more than " + MAX_PARAMETERS + " parameters");
            }
            var name = token().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                throw fail("empty parameter at index " + pos);
            }
            boolean extension = accept && quality >= 0;
            if (pos == text.length() || text.charAt(pos) != '=') {
                if (extension) {
                    continue; // accept-ext: token [ "=" ( token / quoted-string ) ]
                }
                throw fail("parameter " + Text.quote(name) + " without '='");
            }
            pos++;
            boolean quoted = pos < text.length() && text.charAt(pos) == '"';
            var value = quoted ? quotedString() : token();
            if (!quoted && value.isEmpty()) {
                throw fail("parameter " + Text.quote(name) + " without a value");
            }
            if (extension) {
                continue; // ignored, as RFC 9110 lets a recipient do
            }
            if (accept && name.equals("q")) {
                if (quoted) {
                    throw fail("the weight q must not be quoted");
                }
                quality = qvalue(value);
                continue;
            }
            if (parameters.containsKey(name)) {
                throw fail("parameter " + Text.quote(name) + " given twice");
            }
            parameters.put(name, name.equals("charset") ? value.toLowerCase(Locale.ROOT) : value);
        }
        return new Parsed(new MediaType(type, subtype, parameters), quality < 0 ? 1_000 : quality);
    }

    /** {@code *tchar} from {@code pos}, as written; empty if none. */
    private String token() {
        int start = pos;
        while (pos < text.length() && isTchar(text.charAt(pos))) {
            pos++;
        }
        return text.substring(start, pos);
    }

    /** The content of a {@code quoted-string} starting at {@code pos} (at its opening quote). */
    private String quotedString() {
        int start = pos;
        pos++;
        var out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (pos == text.length()) {
                    break;
                }
                c = text.charAt(pos++); // quoted-pair: HTAB / SP / VCHAR (others were rejected already)
            }
            out.append(c);
        }
        throw fail("unterminated quoted string at index " + start);
    }

    /** {@code qvalue = ( "0" [ "." 0*3DIGIT ] ) / ( "1" [ "." 0*3("0") ] )}, in thousandths. */
    private int qvalue(String value) {
        boolean valid = value.length() >= 1 && value.length() <= 5
                && (value.charAt(0) == '0' || value.charAt(0) == '1')
                && (value.length() == 1 || value.charAt(1) == '.');
        if (valid) {
            for (int i = 2; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < '0' || c > '9' || value.charAt(0) == '1' && c != '0') {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw fail("invalid weight q=" + Text.escape(value, Text.MAX_QUOTED)
                    + " (0 to 1 with at most three decimals)");
        }
        int thousandths = (value.charAt(0) - '0') * 1_000;
        int scale = 100;
        for (int i = 2; i < value.length(); i++, scale /= 10) {
            thousandths += (value.charAt(i) - '0') * scale;
        }
        return thousandths;
    }

    private void skipWhitespace() {
        while (pos < text.length() && (text.charAt(pos) == ' ' || text.charAt(pos) == '\t')) {
            pos++;
        }
    }

    private IllegalArgumentException fail(String reason) {
        return new IllegalArgumentException((accept ? "invalid media range " : "invalid media type ")
                + Text.quote(text) + ": " + reason);
    }

    /** Whether {@code c} is a {@code tchar} (RFC 9110 §5.6.2). */
    static boolean isTchar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /** Whether {@code text} is a {@code token}. */
    static boolean isToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isTchar(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
