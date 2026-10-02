package spola.http;

/**
 * Character classes of RFC 9110, RFC 9112 and RFC 3986, and the escaping of untrusted text for
 * logs. All checks work on chars that hold one byte each (ISO-8859-1), as the parser decodes
 * the wire.
 */
final class Syntax {

    private Syntax() {
    }

    /** {@code tchar} (RFC 9110 §5.6.2). */
    static boolean isTchar(char c) {
        return c < 0x80 && (isAlpha(c) || isDigit(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0);
    }

    /** {@code token = 1*tchar} (RFC 9110 §5.6.2). */
    static boolean isToken(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isTchar(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** SP or HTAB: optional whitespace (OWS, RFC 9110 §5.6.3). */
    static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    /**
     * A char allowed in a field value (RFC 9110 §5.5): HTAB, SP, VCHAR or obs-text. Every other
     * control character (NUL, CR, LF, ESC, DEL, ...) and anything above U+00FF is not.
     */
    static boolean isFieldValueChar(char c) {
        return c == '\t' || (c >= 0x20 && c <= 0x7E) || (c >= 0x80 && c <= 0xFF);
    }

    /** {@code unreserved} (RFC 3986 §2.3). */
    static boolean isUnreserved(char c) {
        return isAlpha(c) || isDigit(c) || c == '-' || c == '.' || c == '_' || c == '~';
    }

    /** {@code sub-delims} (RFC 3986 §2.2). */
    static boolean isSubDelim(char c) {
        return "!$&'()*+,;=".indexOf(c) >= 0;
    }

    /**
     * Whether {@code s[from, to)} is made of {@code pchar}s plus the extra chars (RFC 3986 §3.3,
     * §3.4); every {@code %} must start a {@code pct-encoded} triplet.
     */
    static boolean isUriPart(String s, int from, int to, String extra) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= to || !isHexDigit(s.charAt(i + 1)) || !isHexDigit(s.charAt(i + 2))) {
                    return false;
                }
                i += 2;
            } else if (!(isUnreserved(c) || isSubDelim(c) || c == ':' || c == '@' || extra.indexOf(c) >= 0)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code uri-host [ ":" port ]} (RFC 9110 §7.2, RFC 3986 §3.2.2 and §3.2.3): an IP-literal in
     * brackets or a reg-name (possibly empty), then an optional port of digits. No userinfo.
     */
    static boolean isHostAndPort(String s) {
        int i;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 2) {
                return false;
            }
            for (int j = 1; j < close; j++) {
                char c = s.charAt(j);
                if (!(isUnreserved(c) || isSubDelim(c) || c == ':')) {
                    return false;
                }
            }
            i = close + 1;
        } else {
            i = 0;
            while (i < s.length() && s.charAt(i) != ':') {
                char c = s.charAt(i);
                if (c == '%') {
                    if (i + 2 >= s.length() || !isHexDigit(s.charAt(i + 1)) || !isHexDigit(s.charAt(i + 2))) {
                        return false;
                    }
                    i += 3;
                    continue;
                }
                if (!(isUnreserved(c) || isSubDelim(c))) {
                    return false;
                }
                i++;
            }
        }
        if (i == s.length()) {
            return true;
        }
        if (s.charAt(i) != ':') {
            return false;
        }
        for (int j = i + 1; j < s.length(); j++) {
            if (!isDigit(s.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    /** ASCII case-insensitive equality, the comparison for tokens and field names. */
    static boolean equalsIgnoreCase(String a, String b) {
        return a.equalsIgnoreCase(b);
    }

    /** At most this many chars of untrusted text appear in a log line. */
    static final int MAX_LOGGED = 200;

    /**
     * {@code text} as printable ASCII for a log line: {@code \} becomes {@code \\}, every char
     * outside U+0020–U+007E becomes {@code \xHH} or a backslash, {@code u} and four hex digits, and the result is cut after
     * {@link #MAX_LOGGED} chars of input (marked with {@code ...}). The single escape rule of the
     * module: nothing the server logs contains raw control characters from a request.
     */
    static String escape(String text) {
        if (text == null) {
            return "null";
        }
        var out = new StringBuilder(Math.min(text.length(), MAX_LOGGED) + 8);
        int n = Math.min(text.length(), MAX_LOGGED);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
            } else if (c <= 0xFF) {
                out.append("\\x").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        if (text.length() > n) {
            out.append("...");
        }
        return out.toString();
    }
}
