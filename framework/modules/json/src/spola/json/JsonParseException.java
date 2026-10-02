package spola.json;

import java.io.Serial;

/**
 * The input is not JSON (RFC 8259), not UTF-8 (RFC 3629), or exceeds a {@link JsonLimits}
 * limit. The message has the position and a short description of what was expected:
 *
 * <pre>
 * line 3, column 7 (char offset 21): expected ',' or '}' but found '"'
 * line 1, column 513 (byte offset 512): nesting deeper than maxDepth (512)
 * </pre>
 *
 * <p>The message never quotes more than a few characters of the input, and writes every
 * character outside printable ASCII as {@code U+XXXX} or {@code \}{@code uXXXX}, so it can be
 * logged without forging log lines, whatever the input.
 */
public final class JsonParseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String description;
    private final long line;
    private final long column;
    private final long offset;
    private final boolean bytes;

    JsonParseException(String description, long line, long column, long offset, boolean bytes) {
        super("line " + line + ", column " + column + " (" + (bytes ? "byte" : "char") + " offset " + offset
                + "): " + description);
        this.description = description;
        this.line = line;
        this.column = column;
        this.offset = offset;
        this.bytes = bytes;
    }

    /**
     * What was wrong, without the position.
     *
     * @return the description
     */
    public String description() {
        return description;
    }

    /**
     * The line of the error, from 1. A line ends at {@code \n}, {@code \r} or {@code \r\n}.
     *
     * @return the line
     */
    public long line() {
        return line;
    }

    /**
     * The column of the error on its line, from 1, counted in Unicode code points (a character
     * outside the BMP counts once). A byte order mark at the start is not counted.
     *
     * @return the column
     */
    public long column() {
        return column;
    }

    /**
     * The offset of the error from the start of the input, from 0: in bytes for {@code byte[]}
     * and {@code InputStream} input, in UTF-16 code units for {@code String} and {@code Reader}
     * input (see {@link #isByteOffset()}). A byte order mark is counted.
     *
     * @return the offset
     */
    public long offset() {
        return offset;
    }

    /**
     * Whether {@link #offset()} counts bytes (UTF-8 input) rather than UTF-16 code units.
     *
     * @return {@code true} for {@code byte[]} and {@code InputStream} input
     */
    public boolean isByteOffset() {
        return bytes;
    }
}
