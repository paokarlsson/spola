package spola.web;

/** Escaping and shortening of text that may hold request data, for messages and log lines. */
final class Text {

    private Text() {
    }

    /** The longest excerpt of a value in a problem detail (characters before escaping). */
    static final int MAX_QUOTED = 40;

    /** The longest excerpt of a path or a message in a log line (characters before escaping). */
    static final int MAX_LOGGED = 200;

    /**
     * {@code text} with everything outside printable ASCII escaped ({@code \xHH} up to U+00FF,
     * {@code \}{@code uHHHH} above), {@code \} as {@code \\} and {@code "} as {@code \"}, cut
     * after {@code max} characters with {@code ...} appended. The result is printable ASCII.
     */
    static String escape(String text, int max) {
        if (text == null) {
            return "null";
        }
        int n = Math.min(text.length(), max);
        var out = new StringBuilder(n + 8);
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '"') {
                out.append("\\\"");
            } else if (c >= 0x20 && c <= 0x7E) {
                out.append(c);
            } else if (c <= 0xFF) {
                out.append("\\x").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xF, 16));
            } else {
                out.append("\\u");
                for (int shift = 12; shift >= 0; shift -= 4) {
                    out.append(Character.forDigit((c >> shift) & 0xF, 16));
                }
            }
        }
        if (text.length() > n) {
            out.append("...");
        }
        return out.toString();
    }

    /** {@code value} escaped, shortened to {@link #MAX_QUOTED} and in double quotes. */
    static String quote(String value) {
        return "\"" + escape(value, MAX_QUOTED) + "\"";
    }

    /**
     * {@code text} cut to at most {@code max} characters (the last three being {@code ...} when
     * cut), never between the two halves of a surrogate pair; unpaired surrogates become
     * U+FFFD so that the text can be written as JSON.
     */
    static String truncate(String text, int max) {
        String result = text;
        if (text.length() > max) {
            int cut = max - 3;
            if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                cut--;
            }
            result = text.substring(0, cut) + "...";
        }
        return wellFormed(result);
    }

    /** {@code text} with every unpaired surrogate replaced by U+FFFD. */
    static String wellFormed(String text) {
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean paired = Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1));
            if (paired) {
                if (out != null) {
                    out.append(c).append(text.charAt(i + 1));
                }
                i++;
            } else if (Character.isSurrogate(c)) {
                if (out == null) {
                    out = new StringBuilder(text.length()).append(text, 0, i);
                }
                out.append('�');
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? text : out.toString();
    }
}
