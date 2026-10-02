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
 * waits for its next request ({@link State#IDLE}) or lingers after its last response
 * ({@link State#LINGERING}) may be closed at once; one with a request in progress
 * ({@link State#ACTIVE}) is left to finish.
 */
final class Connection implements Runnable {

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
    private GuardedOutput guarded;
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

    /** Handles one request; returns whether the connection can carry another. */
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
        Response response = null;
        Throwable failure = null;
        try {
            response = server.handler().handle(request);
            if (response == null) {
                failure = new NullPointerException("the handler returned null");
            }
        } catch (Exception | Error e) {
            failure = e;
        }
        boolean close = head.closeRequested() || head.version() == HttpVersion.HTTP_1_0;
        if (failure != null) {
            response = failureResponse(request, body, failure);
            close = true;
        }
        if (body.failure() != null) {
            close = true; // the rest of the body cannot be skipped
        }
        if (head.expectContinue() && !body.started() && !body.finished()) {
            close = true; // RFC 9110 10.1.1: the client may or may not send the body now
        }
        if (server.closing()) {
            close = true;
        }
        boolean headRequest = head.method().equals("HEAD");
        try {
            writer.send(response, headRequest, head.version(), close);
        } catch (ResponseWriter.BodyWriterFailed e) {
            body.end();
            var cause = e.getCause();
            if (!writer.committed()) {
                logFailure(request, "the body writer failed before writing", cause);
                sendError(body.failure() != null ? body.failure().status() : 500);
                return false;
            }
            if (writeTimedOut) {
                server.log().log(Level.WARNING, "write timeout: closed the connection from " + peer + " during "
                        + request);
            } else {
                logFailure(request, "the body writer failed after the response started; connection aborted", cause);
            }
            abort();
            return false;
        }
        body.end();
        if (close) {
            linger = true;
            return false;
        }
        return body.skipRest();
    }

    private Response failureResponse(Request request, BodyStream body, Throwable failure) {
        if (body.failure() != null) {
            debug("request body failed on " + request + " from " + peer + ": " + body.failure().status()
                    + " (" + body.failure().getMessage() + ")");
            return errorResponse(body.failure().status());
        }
        logFailure(request, "the handler failed", failure);
        return errorResponse(500);
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

    /** Closes the connection now if it waits for a request or lingers (graceful stop). */
    void closeIfIdle() {
        if (state.compareAndSet(State.IDLE, State.CLOSED) || state.compareAndSet(State.LINGERING, State.CLOSED)) {
            closeQuietly();
        }
    }

    /** Ends the connection now, whatever it is doing; the client sees an aborted response. */
    void abort() {
        aborted = true;
        state.set(State.CLOSED);
        closeQuietly();
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
            closeQuietly();
        }
    }

    private void closeSocket() {
        if (linger && !aborted && !server.closing()) {
            lingerBeforeClose();
        }
        state.set(State.CLOSED);
        closeQuietly();
    }

    /** Half-closes and drops input until the client closes, at most {@link #LINGER_MILLIS} and {@link #LINGER_BYTES}. */
    private void lingerBeforeClose() {
        try {
            wire.flush();
            socket.shutdownOutput();
            if (!state.compareAndSet(State.ACTIVE, State.LINGERING) && !state.compareAndSet(State.IDLE,
                    State.LINGERING)) {
                return;
            }
            long deadline = System.nanoTime() + LINGER_MILLIS * 1_000_000L;
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

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException e) {
            // nothing more to do
        }
    }
}
