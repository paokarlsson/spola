package spola.http;

import java.time.Duration;
import java.util.Objects;

/**
 * The server's timeouts, as a value. Every timeout closes the connection; none of them is a
 * timeout per read call that a slow client could reset by sending one byte at a time, except
 * where noted. Start from {@link #DEFAULT} and change what you need:
 *
 * <pre>{@code
 * var timeouts = HttpTimeouts.DEFAULT.withIdleTimeout(Duration.ofSeconds(15));
 * }</pre>
 *
 * @param headerTimeout   total time from the first byte of a request to the end of its header
 *                        section, and how long a new connection may wait before sending its
 *                        first byte. Exceeded: 408 and the connection is closed. Default 10 s.
 * @param idleTimeout     how long a keep-alive connection may wait between two requests (from
 *                        the end of a response to the first byte of the next request). Exceeded:
 *                        the connection is closed without a response. Default 60 s.
 * @param readTimeout     longest wait without progress (no byte arrives) while the request body
 *                        is read, by the handler or by the server skipping what the handler did
 *                        not read. Exceeded: the body stream throws, the server answers 408 if
 *                        the response has not started, and the connection is closed. Default
 *                        30 s.
 * @param writeTimeout    longest wait without progress while the response is written (a client
 *                        that stops reading). Sockets have no write timeout, so a guard thread
 *                        closes the socket; the handler's write then throws. Default 30 s.
 * @param shutdownTimeout how long {@link HttpServer#close()} lets requests in progress finish
 *                        before it aborts them. Default 30 s.
 */
public record HttpTimeouts(Duration headerTimeout, Duration idleTimeout, Duration readTimeout,
                           Duration writeTimeout, Duration shutdownTimeout) {

    /** The longest timeout: what {@link java.net.Socket#setSoTimeout} can express. */
    static final Duration MAX = Duration.ofMillis(Integer.MAX_VALUE);

    /** The defaults; ARCHITECTURE.md explains each value. */
    public static final HttpTimeouts DEFAULT = new HttpTimeouts(Duration.ofSeconds(10), Duration.ofSeconds(60),
            Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofSeconds(30));

    /**
     * Checks the timeouts.
     *
     * @throws NullPointerException     if a timeout is {@code null}
     * @throws IllegalArgumentException if a timeout is shorter than 1 ms or longer than
     *                                  {@link Integer#MAX_VALUE} ms (about 24 days)
     */
    public HttpTimeouts {
        check("headerTimeout", headerTimeout);
        check("idleTimeout", idleTimeout);
        check("readTimeout", readTimeout);
        check("writeTimeout", writeTimeout);
        check("shutdownTimeout", shutdownTimeout);
    }

    private static void check(String name, Duration value) {
        Objects.requireNonNull(value, name);
        if (value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(name + " must be between 1 ms and " + Integer.MAX_VALUE
                    + " ms, was " + value);
        }
    }

    /**
     * A copy with another header timeout.
     *
     * @param headerTimeout total time from the first byte of a request to the end of its headers
     * @return the copy
     */
    public HttpTimeouts withHeaderTimeout(Duration headerTimeout) {
        return new HttpTimeouts(headerTimeout, idleTimeout, readTimeout, writeTimeout, shutdownTimeout);
    }

    /**
     * A copy with another idle timeout.
     *
     * @param idleTimeout how long a keep-alive connection may wait between requests
     * @return the copy
     */
    public HttpTimeouts withIdleTimeout(Duration idleTimeout) {
        return new HttpTimeouts(headerTimeout, idleTimeout, readTimeout, writeTimeout, shutdownTimeout);
    }

    /**
     * A copy with another read timeout.
     *
     * @param readTimeout longest wait without progress while the body is read
     * @return the copy
     */
    public HttpTimeouts withReadTimeout(Duration readTimeout) {
        return new HttpTimeouts(headerTimeout, idleTimeout, readTimeout, writeTimeout, shutdownTimeout);
    }

    /**
     * A copy with another write timeout.
     *
     * @param writeTimeout longest wait without progress while the response is written
     * @return the copy
     */
    public HttpTimeouts withWriteTimeout(Duration writeTimeout) {
        return new HttpTimeouts(headerTimeout, idleTimeout, readTimeout, writeTimeout, shutdownTimeout);
    }

    /**
     * A copy with another shutdown timeout.
     *
     * @param shutdownTimeout how long {@code close()} lets requests in progress finish
     * @return the copy
     */
    public HttpTimeouts withShutdownTimeout(Duration shutdownTimeout) {
        return new HttpTimeouts(headerTimeout, idleTimeout, readTimeout, writeTimeout, shutdownTimeout);
    }

    /** {@code duration} in whole milliseconds, at least 1 (for {@code setSoTimeout}). */
    static int millis(Duration duration) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, duration.toMillis()));
    }
}
