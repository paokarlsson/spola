package spola.json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

/**
 * Writes a tree as JSON text, compact or indented with two spaces. Not recursive: open
 * containers are kept in a list on the heap, so a tree of any depth can be written.
 *
 * <p>Strings are escaped minimally but safely: {@code "} and {@code \} as {@code \"} and
 * {@code \\}; U+0008, U+0009, U+000A, U+000C and U+000D as {@code \b}, {@code \t}, {@code \n},
 * {@code \f} and {@code \r}; every other character in U+0000..U+001F, and U+007F, U+2028 and
 * U+2029, as {@code \}{@code u} with four lowercase hex digits. Everything else, surrogate pairs
 * included, is written as is.
 */
final class TextWriter {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final Appendable out;
    private final boolean pretty;

    private TextWriter(Appendable out, boolean pretty) {
        this.out = out;
        this.pretty = pretty;
    }

    static void write(JsonValue value, Appendable out, boolean pretty) throws IOException {
        new TextWriter(out, pretty).value(value);
    }

    /** An open container: what remains to be written, and whether anything was written yet. */
    private sealed interface Frame permits ArrayFrame, ObjectFrame {
    }

    private record ArrayFrame(Iterator<JsonValue> remaining) implements Frame {
    }

    private record ObjectFrame(Iterator<Map.Entry<String, JsonValue>> remaining) implements Frame {
    }

    private void value(JsonValue root) throws IOException {
        var open = new ArrayList<Frame>();
        JsonValue next = root;
        while (true) {
            if (next != null) {
                switch (next) {
                    case JsonObject object when object.isEmpty() -> out.append("{}");
                    case JsonArray array when array.isEmpty() -> out.append("[]");
                    case JsonObject object -> {
                        out.append('{');
                        open.add(new ObjectFrame(object.members().entrySet().iterator()));
                    }
                    case JsonArray array -> {
                        out.append('[');
                        open.add(new ArrayFrame(array.values().iterator()));
                    }
                    case JsonString string -> string(string.value());
                    case JsonNumber number -> out.append(number.text());
                    case JsonBoolean bool -> out.append(bool.value() ? "true" : "false");
                    case JsonNull _ -> out.append("null");
                }
            }
            if (open.isEmpty()) {
                return;
            }
            var frame = open.getLast();
            boolean first = next instanceof JsonObject o && !o.isEmpty() || next instanceof JsonArray a && !a.isEmpty();
            next = switch (frame) {
                case ArrayFrame array -> array.remaining().hasNext() ? array.remaining().next() : null;
                case ObjectFrame object -> {
                    if (!object.remaining().hasNext()) {
                        yield null;
                    }
                    var member = object.remaining().next();
                    separator(first, open.size());
                    string(member.getKey());
                    out.append(pretty ? ": " : ":");
                    yield member.getValue();
                }
            };
            if (next == null) {
                open.removeLast();
                if (pretty) {
                    newline(open.size());
                }
                out.append(frame instanceof ArrayFrame ? ']' : '}');
            } else if (frame instanceof ArrayFrame) {
                separator(first, open.size());
            }
        }
    }

    /** Before an element or member: a comma unless it is the first, then a new line when pretty. */
    private void separator(boolean first, int depth) throws IOException {
        if (!first) {
            out.append(',');
        }
        if (pretty) {
            newline(depth);
        }
    }

    private void newline(int depth) throws IOException {
        out.append('\n');
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }

    private void string(String value) throws IOException {
        out.append('"');
        int length = value.length();
        int plain = 0;
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            String escape = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\b' -> "\\b";
                case '\t' -> "\\t";
                case '\n' -> "\\n";
                case '\f' -> "\\f";
                case '\r' -> "\\r";
                default -> c < 0x20 || c == 0x7F || c == 0x2028 || c == 0x2029 ? unicodeEscape(c) : null;
            };
            if (escape != null) {
                out.append(value, plain, i).append(escape);
                plain = i + 1;
            }
        }
        out.append(value, plain, length).append('"');
    }

    private static String unicodeEscape(char c) {
        return new String(new char[] {'\\', 'u', HEX[c >> 12], HEX[(c >> 8) & 0xF], HEX[(c >> 4) & 0xF], HEX[c & 0xF]});
    }
}
