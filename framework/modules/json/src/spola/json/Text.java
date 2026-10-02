package spola.json;

/** Safe rendering of input text in error messages: short, and never a raw control character. */
final class Text {

    /** Longest excerpt of a member name or number quoted in a message, in characters. */
    static final int MAX_EXCERPT = 40;

    private Text() {
    }

    /** {@code U+0009}: a character as its code point, safe in any message. */
    static String codePoint(int c) {
        return String.format("U+%04X", c);
    }

    /**
     * A character found in the input as the parser reports it: {@code 'x'} for printable ASCII,
     * {@code U+0009} for everything else, {@code end of input} for -1.
     */
    static String found(int c) {
        if (c < 0) {
            return "end of input";
        }
        if (c >= 0x20 && c < 0x7F) {
            return c == '\'' ? "\"'\"" : "'" + (char) c + "'";
        }
        return codePoint(c);
    }

    /**
     * {@code text} quoted, with every character outside printable ASCII (controls, DEL, line and
     * paragraph separators, bidi controls, ...) written as {@code \}{@code uXXXX}, and cut after
     * {@link #MAX_EXCERPT} characters with the full length appended.
     */
    static String excerpt(String text) {
        var out = new StringBuilder("\"");
        int shown = Math.min(text.length(), MAX_EXCERPT);
        appendEscaped(out, text, shown);
        out.append('"');
        if (shown < text.length()) {
            out.append("... (").append(text.length()).append(" characters)");
        }
        return out.toString();
    }

    /** The first {@code count} characters of {@code text}, non-printable ones escaped. */
    static void appendEscaped(StringBuilder out, String text, int count) {
        for (int i = 0; i < count; i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\') {
                out.append(c);
            } else if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
    }

    /** The JSON type of a value, as named in messages. */
    static String typeName(JsonValue value) {
        return switch (value) {
            case JsonObject _ -> "object";
            case JsonArray _ -> "array";
            case JsonString _ -> "string";
            case JsonNumber _ -> "number";
            case JsonBoolean _ -> "boolean";
            case JsonNull _ -> "null";
        };
    }
}
