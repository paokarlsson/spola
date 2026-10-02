package spola.http;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One connection, served on its own virtual thread: requests are read, handled and answered in
 * order (keep-alive and pipelining), until the client closes, an error or a timeout ends the
 * connection, or the server stops.
 *
 * <p>The state is shared with the server's {@link HttpServer#close()} only: a connection that
 * waits for its next request ({@link State#IDLE}) may be closed at once; one with a request in
 * progress ({@link State#ACTIVE}) is left to finish, and one that lingers after its last response
 * ({@link State#LINGERING}) ends on its own.
 */
final class Connection implements Runnable {

    /**
     * Where the connection is. Every change is a compare-and-set, so the connection's thread and
     * {@link HttpServer#close()} never both act on the same state:
     *
     * <table>
     * <caption>State transitions</caption>
     * <tr><th>From</th><th>To</th><th>By</th><th>When</th></tr>
     * <tr><td>IDLE</td><td>ACTIVE</td><td>connection thread</td><td>the first bytes of a request
     *     are there</td></tr>
     * <tr><td>ACTIVE</td><td>IDLE</td><td>connection thread</td><td>a response is done and the
     *     connection carries another request</td></tr>
     * <tr><td>ACTIVE or IDLE</td><td>LINGERING</td><td>connection thread</td><td>the last response
     *     is done; it drops input before closing</td></tr>
     * <tr><td>IDLE</td><td>CLOSED</td><td>{@link #closeIfIdle()} (close())</td><td>graceful stop;
     *     the connection thread's next compare-and-set fails and it ends</td></tr>
     * <tr><td>any</td><td>CLOSED</td><td>{@link #abort()}</td><td>close() after the shutdown
     *     timeout, or the connection itself after a failed response</td></tr>
     * <tr><td>any</td><td>CLOSED</td><td>connection thread</td><td>the connection ends</td></tr>
     * </table>
     */
    enum State { IDLE, ACTIVE, LINGERING, CLOSED }

    /**
     * After a response with {@code Connection: close}, the server stops sending and reads (and
     * drops) what the client still sends, for at most this long and this many bytes, before it
     * closes the socket. Closing a socket with unread input makes TCP send a reset, which can
     * destroy the response before the client has read it (RFC 9112 §9.6).
     */
    static final int LINGER_MILLIS = 2_000;
    static final int LINGER_BYTES = 1 << 20;

    private final HttpServer server;
    private final Socket socket;
    private final String peer;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private volatile Thread thread;
    private volatile boolean aborted;
    private volatile boolean writeTimedOut;
    private volatile GuardedOutput guarded; // read by the guard thread
    private ConnectionInput in;
    private BufferedOutputStream wire;
    private ResponseWriter writer;
    private boolean linger;

    Connection(HttpServer server, Socket socket) {
        this.server = server;
        this.socket = socket;
        this.peer = String.valueOf(socket.getRemoteSocketAddress());
    }

    void start(String name) {
        thread = Thread.ofVirtual().name(name).unstarted(this);
        thread.start();
    }

    Thread thread() {
        return thread;
    }

    /** Whether the last response is done and the connection only drops input before closing. */
    boolean lingering() {
        return state.get() == State.LINGERING;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            in = new ConnectionInput(socket);
            guarded = new GuardedOutput(socket.getOutputStream());
            wire = new BufferedOutputStream(guarded, 8_192);
            writer = new ResponseWriter(wire);
            serve();
        } catch (IOException e) {
            debug("connection from " + peer + " ended: " + LoggedThrowable.describe(e));
        } catch (Exception | Error e) {
            server.log().log(Level.ERROR, "connection from " + peer + " failed", LoggedThrowable.of(e));
        } finally {
            closeSocket();
            server.connectionEnded(this);
        }
    }

    private void serve() throws IOException {
        var limits = server.options().limits();
        var timeouts = server.options().timeouts();
        boolean first = true;
        while (true) {
            if (in.buffered() == 0) {
                if (!becomeIdle()) {
                    return;
                }
                in.timeoutPerRead(HttpTimeouts.millis(first ? timeouts.headerTimeout() : timeouts.idleTimeout()));
                try {
                    if (!in.await()) {
                        return;
                    }
                } catch (IOException e) { // idle timeout, or closed by close()
                    return;
                }
            }
            if (!state.compareAndSet(State.IDLE, State.ACTIVE) && state.get() != State.ACTIVE) {
                return;
            }
            in.deadline(System.nanoTime() + timeouts.headerTimeout().toNanos());
            RequestParser.Head head;
            try {
                head = RequestParser.parse(in, limits);
            } catch (ProtocolException e) {
                debug("rejected a request from " + peer + ": " + e.status() + " (" + e.getMessage() + ")");
                sendError(e.status());
                return;
            } catch (SocketTimeoutException e) {
                debug("header timeout on the connection from " + peer);
                sendError(408);
                return;
            } catch (EOFException e) {
                return;
            }
            if (!exchange(head)) {
                return;
            }
            first = false;
        }
    }

    /** IDLE between requests; false if the connection must end (closed, or the server stops). */
    private boolean becomeIdle() {
        if (!state.compareAndSet(State.ACTIVE, State.IDLE) && state.get() != State.IDLE) {
            return false;
        }
        return !server.closing();
    }

    /** What the handler produced: its response, or an error response if it failed. */
    private record Outcome(Response response, boolean failed) {
    }

    /**
     * Handles one request; returns whether the connection can carry another.
     *
     * <p>When the handler returns without reading the whole body, the server reads and drops the
     * rest <em>before</em> it writes the response, so that a body with broken framing (or one
     * over the limits) is answered with its own status and {@code Connection: close} instead of
     * the handler's response. Two cases cannot do that and keep the handler's response, with the
     * connection closed after it: a streaming response (its body writer may still read the
     * request body), and a request with {@code Expect: 100-continue} whose body was never asked
     * for (the client may or may not send it, RFC 9110 §10.1.1). A handler that caught a
     * {@link RequestBodyException} and answered on its own also keeps its response.
     */
    private boolean exchange(RequestParser.Head head) throws IOException {
        var options = server.options();
        writer.reset();
        var body = new BodyStream(in, head, options.limits(), HttpTimeouts.millis(options.timeouts().readTimeout()),
                () -> {
                    if (head.expectContinue() && !writer.committed()) {
                        writer.sendContinue();
                    }
                });
        var request = new Request(head, body);
        boolean headRequest = head.method().equals("HEAD");
        var outcome = handle(request, body);
        if (!outcome.failed() && body.failure() == null && !streams(outcome.response(), headRequest)
                && !awaitsContinue(head, body) && !body.skipRest()) {
            outcome = new Outcome(bodyFailureResponse(request, body), true);
        }
        boolean close = shouldClose(head, body, outcome.failed());
        try {
            writer.send(outcome.response(), headRequest, head.version(), close);
        } catch (ResponseWriter.BodyWriterFailed e) {
            body.end();
            bodyWriterFailed(request, body, e.getCause());
            return false;
        }
        body.end();
        if (close) {
            linger = true;
            return false;
        }
        return body.skipRest(); // after a streaming response: the body framing decides
    }

    /** Calls the handler; a failure (or a {@code null} response) becomes a 500 or the body's status. */
    private Outcome handle(Request request, BodyStream body) {
        Throwable failure;
        try {
            var response = server.handler().handle(request);
            if (response != null) {
                return new Outcome(response, false);
            }
            failure = new NullPointerException("the handler returned null");
        } catch (Exception | Error e) {
            failure = e;
        }
        if (body.failure() != null) {
            return new Outcome(bodyFailureResponse(request, body), true);
        }
        logFailure(request, "the handler failed", failure);
        return new Outcome(errorResponse(500), true);
    }

    /** Whether the response body is written by a {@link BodyWriter} (which may read the request body). */
    private static boolean streams(Response response, boolean headRequest) {
        return !headRequest && response.body() instanceof ResponseBody.Streaming;
    }

    /** Whether the client waits for {@code 100 Continue} that was never sent. */
    private static boolean awaitsContinue(RequestParser.Head head, BodyStream body) {
        return head.expectContinue() && !body.started() && !body.finished();
    }

    /** Whether the response must carry {@code Connection: close} and end the connection. */
    private boolean shouldClose(RequestParser.Head head, BodyStream body, boolean failed) {
        return failed
                || head.closeRequested()
                || head.version() == HttpVersion.HTTP_1_0
                || body.failure() != null      // the rest of the body cannot be skipped
                || awaitsContinue(head, body)  // RFC 9110 10.1.1: the client may or may not send the body now
                || server.closing();
    }

    /** A streaming body writer threw: a clean error if nothing went out yet, else an aborted connection. */
    private void bodyWriterFailed(Request request, BodyStream body, Throwable cause) {
        if (!writer.committed()) {
            logFailure(request, "the body writer failed before writing", cause);
            sendError(body.failure() != null ? body.failure().status() : 500);
            return;
        }
        if (writeTimedOut) {
            server.log().log(Level.WARNING, "write timeout: closed the connection from " + peer + " during " + request);
        } else {
            logFailure(request, "the body writer failed after the response started; connection aborted", cause);
        }
        abort();
    }

    private Response bodyFailureResponse(Request request, BodyStream body) {
        debug("request body failed on " + request + " from " + peer + ": " + body.failure().status()
                + " (" + body.failure().getMessage() + ")");
        return errorResponse(body.failure().status());
    }

    private void logFailure(Request request, String what, Throwable failure) {
        if (failure instanceof IOException && (writeTimedOut || aborted)) {
            debug(what + " on " + request + " from " + peer + ": " + LoggedThrowable.describe(failure));
            return;
        }
        server.log().log(Level.ERROR, what + " on " + request + " from " + peer, LoggedThrowable.of(failure));
    }

    /** A short plain-text error with {@code Connection: close}; the connection then lingers and closes. */
    private void sendError(int status) {
        writer.reset();
        linger = true;
        try {
            writer.send(errorResponse(status), false, HttpVersion.HTTP_1_1, true);
        } catch (IOException | ResponseWriter.BodyWriterFailed e) {
            linger = false;
        }
    }

    static Response errorResponse(int status) {
        return Response.text(status, ResponseWriter.reason(status) + "\n");
    }

    private void debug(String message) {
        var log = server.log();
        if (log.isLoggable(Level.DEBUG)) {
            log.log(Level.DEBUG, message);
        }
    }

    // ---- shutdown and timeouts -----------------------------------------------------------

    /** Closes the connection now if it waits for a request (graceful stop). */
    void closeIfIdle() {
        if (state.compareAndSet(State.IDLE, State.CLOSED)) {
            HttpServer.closeQuietly(socket);
        }
    }

    /** Ends the connection now, whatever it is doing; the client sees an aborted response. */
    void abort() {
        aborted = true;
        state.set(State.CLOSED);
        HttpServer.closeQuietly(socket);
        var t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    /** Called by the guard thread: closes the socket if a write has made no progress for too long. */
    void checkWrite(long now, long timeoutNanos) {
        var output = guarded;
        if (output != null && output.stalled(now, timeoutNanos)) {
            writeTimedOut = true;
            aborted = true;
            HttpServer.closeQuietly(socket);
        }
    }

    private void closeSocket() {
        if (linger && !aborted) {
            lingerBeforeClose();
        }
        state.set(State.CLOSED);
        HttpServer.closeQuietly(socket);
    }

    /**
     * Half-closes and drops input until the client closes, at most {@link #LINGER_MILLIS} and
     * {@link #LINGER_BYTES}, and during a graceful stop no longer than the shutdown deadline.
     */
    private void lingerBeforeClose() {
        try {
            wire.flush();
            socket.shutdownOutput();
            if (!state.compareAndSet(State.ACTIVE, State.LINGERING) && !state.compareAndSet(State.IDLE,
                    State.LINGERING)) {
                return;
            }
            long deadline = System.nanoTime() + LINGER_MILLIS * 1_000_000L;
            long stop = server.shutdownDeadline(); // 0 while the server runs
            if (stop != 0 && stop - deadline < 0) {
                deadline = stop;
            }
            long dropped = 0;
            var sink = new byte[8_192];
            while (dropped < LINGER_BYTES) {
                long left = (deadline - System.nanoTime()) / 1_000_000;
                if (left <= 0) {
                    return;
                }
                in.timeoutPerRead((int) left);
                int n = in.read(sink, 0, sink.length);
                if (n < 0) {
                    return;
                }
                dropped += n;
            }
        } catch (IOException e) {
            // the client is gone or the server closed the socket; either way we are done
        }
    }
}
