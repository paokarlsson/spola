package spola.json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * A strict parser for RFC 8259. It is not recursive: open arrays and objects are kept in a list
 * on the heap, so the nesting depth is bounded by {@link JsonLimits#maxDepth()} and never by the
 * thread's stack. Single use; one instance per document.
 */
final class Parser {

    private static final int EOF = Input.EOF;
    private static final JsonNumber[] DIGITS = new JsonNumber[10];

    static {
        for (int i = 0; i < DIGITS.length; i++) {
            DIGITS[i] = new JsonNumber(Integer.toString(i));
        }
    }

    private final Input input;
    private final JsonLimits limits;
    private final HashMap<String, String> names = new HashMap<>();

    /** The current character (the next to consume), or {@link #EOF}. */
    private int c;
    /** Position of {@link #c}: line and column from 1, offset from 0. */
    private long line = 1;
    private long column = 1;
    private long offset;
    /** The character consumed before {@link #c}, for {@code \r\n} and surrogate pairs. */
    private int previous = EOF;

    private Parser(Input input, JsonLimits limits) {
        this.input = input;
        this.limits = limits;
    }

    static JsonValue parse(Input input, JsonLimits limits) {
        return new Parser(input, limits).document();
    }

    // ---- document and values ------------------------------------------------------------

    private JsonValue document() {
        c = read();
        if (c == '\uFEFF') {
            // RFC 8259 §8.1: a parser may ignore a byte order mark at the start; it is not a column
            c = read();
        }
        skipWhitespace();
        if (c == EOF) {
            throw fail("expected a JSON value but the input is empty or only whitespace (RFC 8259 section 2)");
        }
        var value = value();
        skipWhitespace();
        if (c != EOF) {
            throw fail("expected the end of the input after the top-level value but found " + Text.found(c)
                    + " (RFC 8259 section 2: a document is one value)");
        }
        return value;
    }

    /** An open array or object. */
    private sealed interface Frame permits ArrayFrame, ObjectFrame {
        int size();
    }

    private static final class ArrayFrame implements Frame {
        final ArrayList<JsonValue> values = new ArrayList<>();

        @Override
        public int size() {
            return values.size();
        }
    }

    private static final class ObjectFrame implements Frame {
        final LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        String name;

        @Override
        public int size() {
            return members.size();
        }
    }

    /** One value starting at {@link #c} (whitespace skipped), with everything nested in it. */
    private JsonValue value() {
        var open = new ArrayList<Frame>();
        while (true) {
            JsonValue value;
            switch (c) {
                case '{' -> {
                    checkDepth(open);
                    advance();
                    skipWhitespace();
                    if (c == '}') {
                        advance();
                        value = JsonObject.of();
                    } else {
                        var frame = new ObjectFrame();
                        open.add(frame);
                        memberName(frame);
                        continue;
                    }
                }
                case '[' -> {
                    checkDepth(open);
                    advance();
                    skipWhitespace();
                    if (c == ']') {
                        advance();
                        value = JsonArray.of();
                    } else {
                        open.add(new ArrayFrame());
                        continue;
                    }
                }
                case '"' -> value = new JsonString(string());
                case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> value = number();
                case 't' -> value = literal("true", JsonBoolean.TRUE);
                case 'f' -> value = literal("false", JsonBoolean.FALSE);
                case 'n' -> value = literal("null", JsonNull.INSTANCE);
                default -> throw fail("expected a value but found " + Text.found(c) + " (RFC 8259 section 3)");
            }
            // a complete value: add it to the innermost open container, closing containers that end
            while (true) {
                if (open.isEmpty()) {
                    return value;
                }
                var frame = open.getLast();
                switch (frame) {
                    case ArrayFrame array -> array.values.add(value);
                    case ObjectFrame object -> object.members.put(object.name, value);
                }
                skipWhitespace();
                char close = frame instanceof ArrayFrame ? ']' : '}';
                if (c == ',') {
                    advance();
                    skipWhitespace();
                    if (frame.size() >= limits.maxEntries()) {
                        throw fail("more than maxEntries (" + limits.maxEntries() + ") "
                                + (frame instanceof ArrayFrame ? "elements in an array" : "members in an object"));
                    }
                    if (frame instanceof ObjectFrame object) {
                        memberName(object);
                    }
                    break;
                }
                if (c != close) {
                    throw fail("expected ',' or '" + close + "' but found " + Text.found(c) + " (RFC 8259 section "
                            + (close == ']' ? "5" : "4") + ")");
                }
                advance();
                open.removeLast();
                value = switch (frame) {
                    case ArrayFrame array -> JsonArray.parsed(array.values);
                    case ObjectFrame object -> JsonObject.parsed(object.members);
                };
            }
        }
    }

    private void checkDepth(ArrayList<Frame> open) {
        if (open.size() >= limits.maxDepth()) {
            throw fail("nesting deeper than maxDepth (" + limits.maxDepth() + ")");
        }
    }

    /** A member name, the colon and the whitespace before the value; the name is kept in the frame. */
    private void memberName(ObjectFrame frame) {
        if (c != '"') {
            throw fail("expected a member name (a string) but found " + Text.found(c) + " (RFC 8259 section 4)");
        }
        var at = position();
        var name = string();
        // one String per distinct name in the document, not one per occurrence
        var known = names.putIfAbsent(name, name);
        if (known != null) {
            name = known;
        }
        if (frame.members.containsKey(name)) {
            throw at.fail("duplicate member name " + Text.excerpt(name)
                    + " (RFC 8259 section 4 leaves duplicates open; Spola rejects them)");
        }
        frame.name = name;
        skipWhitespace();
        if (c != ':') {
            throw fail("expected ':' after the member name but found " + Text.found(c) + " (RFC 8259 section 4)");
        }
        advance();
        skipWhitespace();
    }

    private JsonValue literal(String word, JsonValue value) {
        for (int i = 0; i < word.length(); i++) {
            if (c != word.charAt(i)) {
                throw fail("expected '" + word + "' but found " + Text.found(c) + " (RFC 8259 section 3)");
            }
            advance();
        }
        return value;
    }

    // ---- strings ------------------------------------------------------------------------

    /** A string starting at the quote at {@link #c}; returns the decoded value. */
    private String string() {
        var start = position();
        advance();
        var out = new StringBuilder();
        // a high surrogate waiting for its low half, and whether it was escaped
        int high = -1;
        boolean highEscaped = false;
        Position highAt = null;
        while (true) {
            if (c == EOF) {
                throw fail("unterminated string (it starts at line " + start.line + ", column " + start.column
                        + "; RFC 8259 section 7)");
            }
            if (c == '"') {
                if (high >= 0) {
                    throw highAt.fail(unpaired(high));
                }
                advance();
                return out.toString();
            }
            if (c < 0x20) {
                throw fail("unescaped control character " + Text.codePoint(c)
                        + " in a string (RFC 8259 section 7: escape it, e.g. \\n or \\u001f)");
            }
            var at = position();
            int unit;
            boolean escaped = c == '\\';
            if (escaped) {
                advance();
                unit = switch (c) {
                    case '"', '\\', '/' -> c;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'u' -> hex4();
                    default -> throw fail("invalid escape: '\\' followed by " + Text.found(c)
                            + " (RFC 8259 section 7 allows \\\" \\\\ \\/ \\b \\f \\n \\r \\t \\uXXXX)");
                };
                // past the escape letter, or the last hex digit of \\uXXXX
                advance();
            } else {
                unit = c;
                advance();
            }
            if (high >= 0) {
                if (!Character.isLowSurrogate((char) unit) || escaped != highEscaped) {
                    throw highAt.fail(unpaired(high));
                }
                append(out, start, (char) high, (char) unit);
                high = -1;
            } else if (Character.isHighSurrogate((char) unit)) {
                high = unit;
                highEscaped = escaped;
                highAt = at;
            } else if (Character.isLowSurrogate((char) unit)) {
                throw at.fail(unpaired(unit));
            } else {
                append(out, start, (char) unit);
            }
        }
    }

    private static String unpaired(int surrogate) {
        return "unpaired surrogate " + Text.codePoint(surrogate)
                + " in a string (a string must be Unicode text; RFC 8259 section 7, section 8.2)";
    }

    private void append(StringBuilder out, Position start, char... units) {
        if (out.length() + units.length > limits.maxStringLength()) {
            throw start.fail("string longer than maxStringLength (" + limits.maxStringLength() + ")");
        }
        out.append(units);
    }

    /** The four hex digits after {@code \}{@code u} (at {@link #c}); stops at the last digit. */
    private int hex4() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            advance();
            int digit = Character.digit(c, 16);
            if (c > 0x7F || digit < 0) {
                throw fail("invalid \\u escape: expected four hex digits but found " + Text.found(c)
                        + " (RFC 8259 section 7)");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    // ---- numbers ------------------------------------------------------------------------

    /** A number after the grammar of RFC 8259 §6, starting at {@link #c}. */
    private JsonNumber number() {
        var start = position();
        var text = new StringBuilder();
        if (c == '-') {
            take(text, start);
        }
        if (c == '0') {
            take(text, start);
            if (isDigit(c)) {
                throw fail("leading zeros are not allowed (RFC 8259 section 6)");
            }
        } else if (isDigit(c)) {
            digits(text, start);
        } else {
            throw fail("expected a digit but found " + Text.found(c) + " (RFC 8259 section 6)");
        }
        if (c == '.') {
            take(text, start);
            if (!isDigit(c)) {
                throw fail("expected a digit after the decimal point but found " + Text.found(c) + " (RFC 8259 section 6)");
            }
            digits(text, start);
        }
        if (c == 'e' || c == 'E') {
            take(text, start);
            if (c == '+' || c == '-') {
                take(text, start);
            }
            if (!isDigit(c)) {
                throw fail("expected a digit in the exponent but found " + Text.found(c) + " (RFC 8259 section 6)");
            }
            digits(text, start);
        }
        if (text.length() == 1) {
            return DIGITS[text.charAt(0) - '0'];
        }
        return new JsonNumber(text.toString());
    }

    private void digits(StringBuilder text, Position start) {
        while (isDigit(c)) {
            take(text, start);
        }
    }

    private void take(StringBuilder text, Position start) {
        if (text.length() == limits.maxNumberLength()) {
            throw start.fail("number longer than maxNumberLength (" + limits.maxNumberLength() + ")");
        }
        text.append((char) c);
        advance();
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    // ---- characters and positions -------------------------------------------------------

    private void skipWhitespace() {
        // RFC 8259 §2: space, horizontal tab, line feed, carriage return
        while (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            advance();
        }
    }

    /** Consumes {@link #c} and reads the next character. */
    private void advance() {
        if (c == '\r') {
            line++;
            column = 1;
        } else if (c == '\n') {
            if (previous != '\r') {
                line++;
            }
            column = 1;
        } else if (!(Character.isLowSurrogate((char) c) && previous >= 0 && Character.isHighSurrogate((char) previous))) {
            column++;
        }
        previous = c;
        c = read();
    }

    private int read() {
        try {
            int next = input.read();
            offset = input.offset();
            return next;
        } catch (Input.Failure failure) {
            throw new JsonParseException(failure.getMessage(), line, column, failure.offset, input.countsBytes());
        }
    }

    private record Position(long line, long column, long offset, boolean bytes) {
        JsonParseException fail(String description) {
            return new JsonParseException(description, line, column, offset, bytes);
        }
    }

    private Position position() {
        return new Position(line, column, offset, input.countsBytes());
    }

    private JsonParseException fail(String description) {
        return position().fail(description);
    }
}
