package spola.http;

import java.util.Arrays;
import java.util.Objects;

/**
 * The body of a {@link Response}: nothing, bytes known in advance, or a stream written by the
 * application. The server chooses the framing from the kind: {@code Content-Length} for
 * {@link Empty} and {@link Bytes}, chunked (HTTP/1.1) or closing the connection (HTTP/1.0) for
 * {@link Streaming}.
 */
public sealed interface ResponseBody {

    /** No body: {@code Content-Length: 0}, or no length at all for 204 and 304. */
    record Empty() implements ResponseBody {
    }

    /**
     * A body known in advance, sent with {@code Content-Length}. The array is copied in and out,
     * so neither the caller nor the server can change it afterwards.
     *
     * @param bytes the body
     */
    record Bytes(byte[] bytes) implements ResponseBody {

        /**
         * Copies the array.
         *
         * @throws NullPointerException if {@code bytes} is {@code null}
         */
        public Bytes {
            bytes = bytes.clone();
        }

        /**
         * A copy of the body.
         *
         * @return the bytes
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /** The length, without copying. */
        int length() {
            return bytes.length;
        }

        /** The array itself, for the server's write. */
        byte[] unsafeArray() {
            return bytes;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Bytes b && Arrays.equals(b.bytes, bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Bytes[" + bytes.length + " bytes]";
        }
    }

    /**
     * A body the application writes while it is sent.
     *
     * @param writer writes the body
     */
    record Streaming(BodyWriter writer) implements ResponseBody {

        /**
         * Checks the writer.
         *
         * @throws NullPointerException if {@code writer} is {@code null}
         */
        public Streaming {
            Objects.requireNonNull(writer, "writer");
        }
    }

    /** The empty body. */
    ResponseBody EMPTY = new Empty();
}
