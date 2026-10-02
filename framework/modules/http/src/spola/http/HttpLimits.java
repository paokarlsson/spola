package spola.http;

/**
 * The server's size and count limits, as a value. A request beyond a limit is rejected before
 * the handler is called (or, for a chunked body, when the handler reads past the limit). Start
 * from {@link #DEFAULT} and change what you need:
 *
 * <pre>{@code
 * var limits = HttpLimits.DEFAULT.withMaxBodySize(64 * 1024).withMaxConnections(500);
 * }</pre>
 *
 * @param maxRequestLineLength the longest request-line in bytes, without its CRLF. Longer gives
 *                             414 (URI Too Long). Default 8 192.
 * @param maxHeaderSectionSize the most bytes of all header field lines together, each with its
 *                             CRLF (not the request-line and not the empty line that ends the
 *                             section). More gives 431 (Request Header Fields Too Large). The
 *                             trailer section of a chunked body has the same limit of its own.
 *                             Default 16 384.
 * @param maxHeaderCount       the most header field lines. More gives 431. Also applies to the
 *                             trailer section. Default 100.
 * @param maxBodySize          the largest request body in bytes, after chunked decoding. A
 *                             larger {@code Content-Length} gives 413 (Content Too Large) before
 *                             the handler is called; a chunked body that grows past it makes the
 *                             body stream throw and the server answer 413. Default 10 MiB.
 * @param maxConnections       the most connections served at the same time. When the limit is
 *                             reached the server stops accepting; new connections wait in the
 *                             operating system's backlog until a connection closes. Default
 *                             10 000.
 */
public record HttpLimits(int maxRequestLineLength, int maxHeaderSectionSize, int maxHeaderCount,
                         long maxBodySize, int maxConnections) {

    /** The defaults; ARCHITECTURE.md explains each value. */
    public static final HttpLimits DEFAULT = new HttpLimits(8_192, 16_384, 100, 10L << 20, 10_000);

    /**
     * Checks the limits.
     *
     * @throws IllegalArgumentException if a limit is less than 1
     */
    public HttpLimits {
        positive("maxRequestLineLength", maxRequestLineLength);
        positive("maxHeaderSectionSize", maxHeaderSectionSize);
        positive("maxHeaderCount", maxHeaderCount);
        positive("maxBodySize", maxBodySize);
        positive("maxConnections", maxConnections);
    }

    private static void positive(String name, long value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, was " + value);
        }
    }

    /**
     * A copy with another request-line limit.
     *
     * @param maxRequestLineLength the longest request-line in bytes
     * @return the copy
     */
    public HttpLimits withMaxRequestLineLength(int maxRequestLineLength) {
        return new HttpLimits(maxRequestLineLength, maxHeaderSectionSize, maxHeaderCount, maxBodySize,
                maxConnections);
    }

    /**
     * A copy with another header section limit.
     *
     * @param maxHeaderSectionSize the most bytes of all header field lines together
     * @return the copy
     */
    public HttpLimits withMaxHeaderSectionSize(int maxHeaderSectionSize) {
        return new HttpLimits(maxRequestLineLength, maxHeaderSectionSize, maxHeaderCount, maxBodySize,
                maxConnections);
    }

    /**
     * A copy with another header count limit.
     *
     * @param maxHeaderCount the most header field lines
     * @return the copy
     */
    public HttpLimits withMaxHeaderCount(int maxHeaderCount) {
        return new HttpLimits(maxRequestLineLength, maxHeaderSectionSize, maxHeaderCount, maxBodySize,
                maxConnections);
    }

    /**
     * A copy with another body size limit.
     *
     * @param maxBodySize the largest request body in bytes
     * @return the copy
     */
    public HttpLimits withMaxBodySize(long maxBodySize) {
        return new HttpLimits(maxRequestLineLength, maxHeaderSectionSize, maxHeaderCount, maxBodySize,
                maxConnections);
    }

    /**
     * A copy with another connection limit.
     *
     * @param maxConnections the most connections served at the same time
     * @return the copy
     */
    public HttpLimits withMaxConnections(int maxConnections) {
        return new HttpLimits(maxRequestLineLength, maxHeaderSectionSize, maxHeaderCount, maxBodySize,
                maxConnections);
    }
}
