package spola.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Serial;
import java.io.UncheckedIOException;

/**
 * The parser's source of UTF-16 code units: text ({@code String}, {@code Reader}) or strictly
 * decoded UTF-8 ({@code byte[]}, {@code InputStream}). Counts what it reads against
 * {@link JsonLimits#maxInputSize()} and never reads a stream further than one unit past it.
 * Read failures of a stream or reader are thrown as {@link UncheckedIOException}.
 */
abstract sealed class Input {

    static final int EOF = -1;

    /**
     * The next UTF-16 code unit, or {@link #EOF}.
     *
     * @throws Failure if the input is malformed or larger than the limit
     */
    abstract int read();

    /** The offset of the unit the last {@link #read()} returned (or of the end of input). */
    abstract long offset();

    /** Whether offsets count bytes. */
    abstract boolean countsBytes();

    static Input of(String text, long maxSize) {
        return new Chars(new StringSource(text), maxSize);
    }

    static Input of(Reader reader, long maxSize) {
        return new Chars(new ReaderSource(reader, maxSize), maxSize);
    }

    static Input of(byte[] bytes, long maxSize) {
        return new Utf8(bytes, null, maxSize);
    }

    static Input of(InputStream stream, long maxSize) {
        return new Utf8(new byte[8192], stream, maxSize);
    }

    /** Malformed input or input beyond the size limit; the parser adds line and column. */
    static final class Failure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        final long offset;

        Failure(String description, long offset) {
            super(description, null, false, false);
            this.offset = offset;
        }
    }

    static Failure tooLarge(long maxSize, long offset, String unit) {
        return new Failure("input larger than maxInputSize (" + maxSize + " " + unit + ")", offset);
    }

    // ---- text ---------------------------------------------------------------------------

    /** Characters from a {@code String} or a {@code Reader}. */
    private sealed interface CharSource permits StringSource, ReaderSource {
        /** The next char, or {@link #EOF}. */
        int next();
    }

    private static final class StringSource implements CharSource {
        private final String text;
        private int index;

        StringSource(String text) {
            this.text = text;
        }

        @Override
        public int next() {
            return index < text.length() ? text.charAt(index++) : EOF;
        }
    }

    private static final class ReaderSource implements CharSource {
        private final Reader reader;
        private final long maxSize;
        private final char[] buffer = new char[8192];
        private int position;
        private int end;
        /** Chars taken from the reader so far; never more than one past the limit. */
        private long taken;

        ReaderSource(Reader reader, long maxSize) {
            this.reader = reader;
            this.maxSize = maxSize;
        }

        @Override
        public int next() {
            while (position == end) {
                long left = maxSize - taken;
                int wanted = left < buffer.length ? (int) left + 1 : buffer.length;
                try {
                    int count = reader.read(buffer, 0, wanted);
                    if (count < 0) {
                        return EOF;
                    }
                    position = 0;
                    end = count;
                    taken += count;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return buffer[position++];
        }
    }

    private static final class Chars extends Input {
        private final CharSource source;
        private final long maxSize;
        private long count;
        private boolean ended;

        Chars(CharSource source, long maxSize) {
            this.source = source;
            this.maxSize = maxSize;
        }

        @Override
        int read() {
            if (ended) {
                return EOF;
            }
            if (count == maxSize) {
                // one more unit decides between "exactly at the limit" and "too large"
                if (source.next() == EOF) {
                    ended = true;
                    return EOF;
                }
                throw tooLarge(maxSize, count, "chars");
            }
            int c = source.next();
            if (c == EOF) {
                ended = true;
                return EOF;
            }
            count++;
            return c;
        }

        @Override
        long offset() {
            return ended ? count : count - 1;
        }

        @Override
        boolean countsBytes() {
            return false;
        }
    }

    // ---- UTF-8 --------------------------------------------------------------------------

    /**
     * Strict UTF-8 after RFC 3629 §4: exactly the byte sequences of its grammar are accepted.
     * Overlong forms (C0, C1, E0 80..9F, F0 80..8F), encoded surrogates (ED A0..BF), code
     * points above U+10FFFF (F4 90..BF, F5..FF), stray continuation bytes and sequences cut
     * short are rejected. A code point above U+FFFF is returned as a surrogate pair; both units
     * have the offset of the sequence's first byte.
     */
    private static final class Utf8 extends Input {
        private final byte[] buffer;
        private final InputStream stream;
        private final long maxSize;
        /** Bytes consumed so far (the offset of the next byte). */
        private long consumed;
        private int position;
        private int end;
        private long offset;
        private int pendingLow = -1;
        private boolean ended;

        Utf8(byte[] buffer, InputStream stream, long maxSize) {
            this.buffer = buffer;
            this.stream = stream;
            this.maxSize = maxSize;
            this.end = stream == null ? buffer.length : 0;
        }

        @Override
        int read() {
            if (pendingLow >= 0) {
                int low = pendingLow;
                pendingLow = -1;
                return low;
            }
            offset = consumed;
            int first = nextByte();
            if (first < 0) {
                return EOF;
            }
            if (first < 0x80) {
                return first;
            }
            int codePoint;
            if (first >= 0xC2 && first <= 0xDF) {
                codePoint = ((first & 0x1F) << 6) | continuation(first, 0x80, 0xBF);
            } else if (first >= 0xE0 && first <= 0xEF) {
                int low = first == 0xE0 ? 0xA0 : 0x80;
                int high = first == 0xED ? 0x9F : 0xBF;
                codePoint = ((first & 0x0F) << 12) | (continuation(first, low, high) << 6)
                        | continuation(first, 0x80, 0xBF);
            } else if (first >= 0xF0 && first <= 0xF4) {
                int low = first == 0xF0 ? 0x90 : 0x80;
                int high = first == 0xF4 ? 0x8F : 0xBF;
                codePoint = ((first & 0x07) << 18) | (continuation(first, low, high) << 12)
                        | (continuation(first, 0x80, 0xBF) << 6) | continuation(first, 0x80, 0xBF);
            } else {
                throw new Failure(invalidStart(first), offset);
            }
            if (codePoint >= 0x10000) {
                pendingLow = Character.lowSurrogate(codePoint);
                return Character.highSurrogate(codePoint);
            }
            return codePoint;
        }

        private static String invalidStart(int first) {
            var why = first < 0xC0 ? "a continuation byte cannot start a character"
                    : first < 0xC2 ? "overlong encoding of an ASCII character"
                    : "would encode a code point above U+10FFFF";
            return String.format("invalid UTF-8: byte 0x%02X (%s; RFC 3629 section 4)", first, why);
        }

        /** The low six bits of the next byte, which must be in {@code low..high}. */
        private int continuation(int first, int low, int high) {
            long at = consumed;
            int b = nextByte();
            if (b < 0) {
                throw new Failure("invalid UTF-8: the input ends inside a character (RFC 3629 section 4)", offset);
            }
            if (b < low || b > high) {
                String why;
                if (b < 0x80 || b > 0xBF) {
                    why = "not a continuation byte";
                } else if (first == 0xED) {
                    why = "would encode a surrogate code point";
                } else if (first == 0xF4) {
                    why = "would encode a code point above U+10FFFF";
                } else {
                    why = "overlong encoding";
                }
                throw new Failure(String.format("invalid UTF-8: byte 0x%02X after 0x%02X (%s; RFC 3629 section 4)",
                        b, first, why), at);
            }
            return b & 0x3F;
        }

        private int nextByte() {
            if (ended) {
                return EOF;
            }
            if (position == end && !fill()) {
                ended = true;
                return EOF;
            }
            if (consumed == maxSize) {
                throw tooLarge(maxSize, consumed, "bytes");
            }
            consumed++;
            return buffer[position++] & 0xFF;
        }

        /** Reads more of the stream, never further than one byte past the limit. */
        private boolean fill() {
            if (stream == null) {
                return false;
            }
            long left = maxSize - consumed;
            int wanted = left < buffer.length ? (int) left + 1 : buffer.length;
            try {
                int count = stream.read(buffer, 0, wanted);
                while (count == 0) {
                    count = stream.read(buffer, 0, wanted);
                }
                if (count < 0) {
                    return false;
                }
                position = 0;
                end = count;
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        long offset() {
            return offset;
        }

        @Override
        boolean countsBytes() {
            return true;
        }
    }
}
