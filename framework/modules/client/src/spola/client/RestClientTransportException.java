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
 * <p>A timeout has no cause. Otherwise the cause comes from {@code java.net.http}, and what the
 * message and the cause show of its text depends on where that text can come from:
 *
 * <ul>
 *   <li>A {@link java.net.SocketException} (such as a {@link java.net.ConnectException}), a
 *       {@link javax.net.ssl.SSLException} or a {@link java.net.http.HttpTimeoutException} (the
 *       connect timeout) has a text from the JDK or the operating system, never from the server.
 *       {@link #getCause()} is that exception and the message ends with its class and text (as
 *       ASCII, shortened), unless the text, or that of anything it carries, holds a header
 *       value, the query or the body of the request; then it is treated as below.</li>
 *   <li>Every other failure, above all a response that is not valid HTTP/1.1, can quote what
 *       the server sent ({@code java.net.http} quotes a bad status line or header), and so the
 *       request, if the server sends that back. The message has only the class of the failure
 *       and of its root cause, and {@link #getCause()} is a copy of the chain of causes without
 *       any text: an {@link java.io.IOException} per exception that names its class and has its
 *       stack trace.</li>
 * </ul>
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
