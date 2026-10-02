package spola.json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Parsing and writing JSON.
 *
 * <h2>Parsing</h2>
 * The parser follows RFC 8259 strictly and has no extensions: no comments, no trailing commas,
 * no single quotes, no unescaped control characters in strings, no leading zeros, no {@code +}
 * before a number, no {@code NaN} or {@code Infinity}, nothing but whitespace after the value.
 * Any value may stand at the top level. Two members with the same name (after decoding escapes)
 * are rejected. An unpaired surrogate, escaped or not, is rejected. Byte input must be UTF-8
 * and is decoded strictly (RFC 3629). A byte order mark at the very start is skipped, in byte
 * and in text input; anywhere else it is an error. Input beyond a {@link JsonLimits} limit is
 * rejected; {@link JsonLimits#DEFAULT} applies when no limits are given.
 *
 * <p>Every rejected input gives a {@link JsonParseException} with the line, the column and the
 * offset, never another exception or an {@code Error}: the parser is not recursive, so deep
 * nesting cannot overflow the stack. A stream is read only as far as it must be (at most one
 * unit past {@link JsonLimits#maxInputSize()}) and is not closed.
 *
 * <h2>Writing</h2>
 * {@link #write(JsonValue)} writes compact JSON, {@link #writePretty(JsonValue)} indents with
 * two spaces, one member or element per line, {@code ": "} after a name and {@code \n} as line
 * break, without a final line break. Numbers are written with their text. Strings are escaped
 * minimally but safely: {@code "} and {@code \} always; U+0000..U+001F always (as {@code \b},
 * {@code \t}, {@code \n}, {@code \f}, {@code \r} or {@code \}{@code u00XX}); and U+007F,
 * U+2028 and U+2029 as {@code \}{@code u007f}, {@code \}{@code u2028} and
 * {@code \}{@code u2029}. Everything else, surrogate pairs included, is written as is.
 *
 * <p>The output is always valid JSON, for every tree: a tree cannot hold a duplicate name, an
 * unpaired surrogate, NaN or an infinity. {@code Json.parse(Json.write(v)).equals(v)} and
 * {@code Json.parse(Json.writePretty(v)).equals(v)} hold for every tree {@code v} that is within
 * the limits given to {@code parse} (a tree built in code may be deeper or larger than
 * {@link JsonLimits#DEFAULT} allows).
 *
 * <p>The class has no state; every method can be called from any number of threads.
 */
public final class Json {

    private Json() {
    }

    /**
     * Parses a document with {@link JsonLimits#DEFAULT}.
     *
     * @param text the document
     * @return the value
     * @throws JsonParseException if the text is not one JSON value or exceeds a limit
     */
    public static JsonValue parse(String text) {
        return parse(text, JsonLimits.DEFAULT);
    }

    /**
     * Parses a document.
     *
     * @param text   the document
     * @param limits the limits
     * @return the value
     * @throws JsonParseException if the text is not one JSON value or exceeds a limit
     */
    public static JsonValue parse(String text, JsonLimits limits) {
        Objects.requireNonNull(text, "text");
        return Parser.parse(Input.of(text, limits.maxInputSize()), limits);
    }

    /**
     * Parses a UTF-8 document with {@link JsonLimits#DEFAULT}.
     *
     * @param utf8 the document's bytes
     * @return the value
     * @throws JsonParseException if the bytes are not UTF-8, not one JSON value, or exceed a limit
     */
    public static JsonValue parse(byte[] utf8) {
        return parse(utf8, JsonLimits.DEFAULT);
    }

    /**
     * Parses a UTF-8 document.
     *
     * @param utf8   the document's bytes
     * @param limits the limits
     * @return the value
     * @throws JsonParseException if the bytes are not UTF-8, not one JSON value, or exceed a limit
     */
    public static JsonValue parse(byte[] utf8, JsonLimits limits) {
        Objects.requireNonNull(utf8, "utf8");
        return Parser.parse(Input.of(utf8, limits.maxInputSize()), limits);
    }

    /**
     * Parses a UTF-8 document from a stream with {@link JsonLimits#DEFAULT}. Reads to the end of
     * the stream (or one byte past the size limit), and does not close it.
     *
     * @param utf8 the stream
     * @return the value
     * @throws JsonParseException if the bytes are not UTF-8, not one JSON value, or exceed a limit
     * @throws IOException        if reading the stream fails
     */
    public static JsonValue parse(InputStream utf8) throws IOException {
        return parse(utf8, JsonLimits.DEFAULT);
    }

    /**
     * Parses a UTF-8 document from a stream. Reads to the end of the stream (or one byte past
     * {@link JsonLimits#maxInputSize()}), and does not close it.
     *
     * @param utf8   the stream
     * @param limits the limits
     * @return the value
     * @throws JsonParseException if the bytes are not UTF-8, not one JSON value, or exceed a limit
     * @throws IOException        if reading the stream fails
     */
    public static JsonValue parse(InputStream utf8, JsonLimits limits) throws IOException {
        Objects.requireNonNull(utf8, "utf8");
        try {
            return Parser.parse(Input.of(utf8, limits.maxInputSize()), limits);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Parses a document from a reader with {@link JsonLimits#DEFAULT}. Reads to the end (or one
     * character past the size limit), and does not close the reader.
     *
     * @param reader the reader
     * @return the value
     * @throws JsonParseException if the text is not one JSON value or exceeds a limit
     * @throws IOException        if reading fails
     */
    public static JsonValue parse(Reader reader) throws IOException {
        return parse(reader, JsonLimits.DEFAULT);
    }

    /**
     * Parses a document from a reader. Reads to the end (or one character past
     * {@link JsonLimits#maxInputSize()}), and does not close the reader.
     *
     * @param reader the reader
     * @param limits the limits
     * @return the value
     * @throws JsonParseException if the text is not one JSON value or exceeds a limit
     * @throws IOException        if reading fails
     */
    public static JsonValue parse(Reader reader, JsonLimits limits) throws IOException {
        Objects.requireNonNull(reader, "reader");
        try {
            return Parser.parse(Input.of(reader, limits.maxInputSize()), limits);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * The value as compact JSON, without any whitespace.
     *
     * @param value the value
     * @return the JSON text
     */
    public static String write(JsonValue value) {
        return toText(value, false);
    }

    /**
     * The value as indented JSON: two spaces per level, one member or element per line.
     *
     * @param value the value
     * @return the JSON text, without a final line break
     */
    public static String writePretty(JsonValue value) {
        return toText(value, true);
    }

    /**
     * Appends the value as compact JSON.
     *
     * @param value the value
     * @param out   where to write, such as a {@code StringBuilder} or a {@code Writer}; not
     *              flushed or closed
     * @throws IOException if {@code out} fails
     */
    public static void write(JsonValue value, Appendable out) throws IOException {
        TextWriter.write(Objects.requireNonNull(value, "value"), Objects.requireNonNull(out, "out"), false);
    }

    /**
     * Appends the value as indented JSON.
     *
     * @param value the value
     * @param out   where to write, such as a {@code StringBuilder} or a {@code Writer}; not
     *              flushed or closed
     * @throws IOException if {@code out} fails
     */
    public static void writePretty(JsonValue value, Appendable out) throws IOException {
        TextWriter.write(Objects.requireNonNull(value, "value"), Objects.requireNonNull(out, "out"), true);
    }

    /**
     * Writes the value as compact JSON in UTF-8. (A separate name from
     * {@link #write(JsonValue, Appendable)}, since a {@code PrintStream} is both.)
     *
     * @param value the value
     * @param out   the stream; flushed, not closed
     * @throws IOException if the stream fails
     */
    public static void writeUtf8(JsonValue value, OutputStream out) throws IOException {
        toStream(value, out, false);
    }

    /**
     * Writes the value as indented JSON in UTF-8.
     *
     * @param value the value
     * @param out   the stream; flushed, not closed
     * @throws IOException if the stream fails
     */
    public static void writePrettyUtf8(JsonValue value, OutputStream out) throws IOException {
        toStream(value, out, true);
    }

    private static String toText(JsonValue value, boolean pretty) {
        var out = new StringBuilder();
        try {
            TextWriter.write(Objects.requireNonNull(value, "value"), out, pretty);
        } catch (IOException e) {
            // a StringBuilder does not throw
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static void toStream(JsonValue value, OutputStream out, boolean pretty) throws IOException {
        Objects.requireNonNull(value, "value");
        // every JsonString is valid Unicode (no unpaired surrogates), so the encoding is exact
        var writer = new BufferedWriter(new OutputStreamWriter(Objects.requireNonNull(out, "out"),
                StandardCharsets.UTF_8));
        TextWriter.write(value, writer, pretty);
        writer.flush();
    }
}
