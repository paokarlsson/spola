package spola.json.codec;

import java.util.Collection;
import spola.json.JsonArray;
import spola.json.JsonBoolean;
import spola.json.JsonNull;
import spola.json.JsonNumber;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;

/**
 * Safe rendering of outside text (values from the document, member names, exception messages)
 * in problem messages: short, and never a raw control character. The rules are those of
 * {@code spola.json}'s parser messages: printable ASCII only, everything else as
 * {@code \}{@code uXXXX}.
 */
final class Messages {

    /** Longest excerpt of a value or member name quoted in a message, in characters. */
    static final int MAX_EXCERPT = 40;

    /** Longest excerpt of an exception message quoted in a problem, in characters. */
    static final int MAX_EXCEPTION_MESSAGE = 200;

    /** Longest list of allowed names (tags, enum names, member names) in a message, in characters. */
    static final int MAX_CHOICES = 200;

    private Messages() {
    }

    /**
     * {@code text} quoted, with {@code "} and {@code \} escaped, every character outside
     * printable ASCII written as {@code \}{@code uXXXX}, and cut after {@link #MAX_EXCERPT}
     * characters with the full length appended.
     */
    static String excerpt(String text) {
        var out = new StringBuilder(MAX_EXCERPT + 32).append('"');
        int shown = Math.min(text.length(), MAX_EXCERPT);
        appendEscaped(out, text, shown, true);
        out.append('"');
        if (shown < text.length()) {
            out.append("... (").append(text.length()).append(" characters)");
        }
        return out.toString();
    }

    /**
     * Free text from outside (an exception message) without quotes: every character outside
     * printable ASCII escaped, cut after {@code max} characters with the full length appended.
     */
    static String clean(String text, int max) {
        var out = new StringBuilder(Math.min(text.length(), max) + 32);
        int shown = Math.min(text.length(), max);
        appendEscaped(out, text, shown, false);
        if (shown < text.length()) {
            out.append("... (").append(text.length()).append(" characters)");
        }
        return out.toString();
    }

    /** A JSON number's text (printable ASCII by grammar), cut after {@link #MAX_EXCERPT} characters. */
    static String number(JsonNumber number) {
        var text = number.text();
        if (text.length() <= MAX_EXCERPT) {
            return text;
        }
        return text.substring(0, MAX_EXCERPT) + "... (" + text.length() + " characters)";
    }

    /**
     * The first {@code count} characters of {@code text}; characters outside printable ASCII as
     * {@code \}{@code uXXXX}, and {@code "} and {@code \} escaped when {@code quoted}.
     */
    static void appendEscaped(StringBuilder out, String text, int count, boolean quoted) {
        for (int i = 0; i < count; i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                if (quoted && (c == '"' || c == '\\')) {
                    out.append('\\');
                }
                out.append(c);
            } else {
                out.append("\\u");
                String hex = Integer.toHexString(c);
                out.append("0".repeat(4 - hex.length())).append(hex);
            }
        }
    }

    /** {@code "a", "b", "c"}: each quoted as {@link #excerpt}, at most about {@link #MAX_CHOICES} characters. */
    static String choices(Collection<String> names) {
        var out = new StringBuilder();
        int listed = 0;
        for (var name : names) {
            var quoted = excerpt(name);
            if (listed > 0 && out.length() + quoted.length() + 2 > MAX_CHOICES) {
                out.append(", ... (").append(names.size()).append(" in total)");
                return out.toString();
            }
            if (listed > 0) {
                out.append(", ");
            }
            out.append(quoted);
            listed++;
        }
        return out.toString();
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

    /** {@code expected <what> but found <type>}. */
    static String expected(String what, JsonValue found) {
        return "expected " + what + " but found " + typeName(found);
    }

    /** The problem message for an exception thrown by application code (a constructor, a mapping). */
    static String rejected(RuntimeException e) {
        return "invalid value: " + describe(e);
    }

    /** An exception's message, cleaned and cut; {@code (no message)} when it has none. */
    static String describe(RuntimeException e) {
        var message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return "(no message)";
        }
        return clean(message, MAX_EXCEPTION_MESSAGE);
    }
}
