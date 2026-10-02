package spola.http;

import java.io.Serial;

/**
 * A request the server refuses before calling the handler: the status to answer and a short
 * description for the debug log. The description is a fixed text written by the parser, never
 * request data.
 */
final class ProtocolException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    ProtocolException(int status, String description) {
        super(description, null, false, false);
        this.status = status;
    }

    int status() {
        return status;
    }
}
