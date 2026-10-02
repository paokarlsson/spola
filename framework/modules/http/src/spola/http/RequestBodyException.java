package spola.http;

import java.io.IOException;
import java.io.Serial;

/**
 * Reading the request body failed: it is larger than {@link HttpLimits#maxBodySize()}, its
 * chunked framing is broken, it stopped arriving within {@link HttpTimeouts#readTimeout()}, or
 * it ended early. The message is a short description in printable ASCII without request data.
 *
 * <p>A handler may let it propagate: the server then answers {@link #status()} (if the response
 * has not started) and closes the connection. A handler that catches it and answers on its own
 * gets its response sent, but the connection is closed afterwards all the same, since the rest
 * of the body cannot be skipped.
 */
public final class RequestBodyException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    RequestBodyException(int status, String message) {
        super(message);
        this.status = status;
    }

    RequestBodyException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * The status the server answers with: 400 (broken framing or a body that ended early), 408
     * (read timeout), 413 (too large) or 431 (trailer section too large).
     *
     * @return the status code
     */
    public int status() {
        return status;
    }
}
