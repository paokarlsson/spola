package spola.client;

import java.io.Serial;
import java.net.URI;

/**
 * No usable response came: the counterpart of Spring's {@code ResourceAccessException}. It is
 * thrown when
 *
 * <ul>
 *   <li>the connection is refused or cannot be made;</li>
 *   <li>TLS fails (the handshake, the certificate, a server that does not speak TLS);</li>
 *   <li>the connection breaks, or the response is not HTTP/1.1 (garbage, a body cut short);</li>
 *   <li>{@link RestClient.Builder#timeout(java.time.Duration) the timeout} or
 *       {@link RestClient.Builder#connectTimeout(java.time.Duration) the connect timeout} runs out
 *       ({@link #timedOut()} is {@code true});</li>
 *   <li>the calling thread is interrupted while it waits: the request is aborted, the thread's
 *       interrupt flag is set again and {@link #getCause()} is the
 *       {@link InterruptedException}.</li>
 * </ul>
 *
 * <p>Otherwise {@link #getCause()} is the exception from {@code java.net.http}, such as a
 * {@link java.net.ConnectException} or a {@link javax.net.ssl.SSLException}, and the message ends
 * with its class and message (as ASCII, shortened). If its text (or that of anything it carries)
 * quotes a header value, the query or the body of the request, which a server that echoes the
 * request can cause, the cause is an {@link java.io.IOException} that names the original's class,
 * with its stack trace but without its message, and the message has only the class. A timeout
 * has no cause.
 */
public final class RestClientTransportException extends RestClientException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean timedOut;

    RestClientTransportException(String method, URI uri, String what, boolean timedOut, Throwable cause) {
        super(method, uri, what, cause);
        this.timedOut = timedOut;
    }

    /**
     * Whether the call ran out of time: the total timeout, or the connect timeout while
     * connecting.
     *
     * @return {@code true} for a timeout
     */
    public boolean timedOut() {
        return timedOut;
    }
}
