package spola.http;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSocket;

/**
 * One connection, served on its own virtual thread: requests are read, handled and answered in
 * order (keep-alive and pipelining), until the client closes, an error or a timeout ends the
 * connection, or the server stops.
 *
 * <p>The state is shared with the server's {@link HttpServer#close()} only: a connection that
 * waits for its next request ({@link State#IDLE}) may be closed at once; one with a request in
 * progress ({@link State#ACTIVE}) is left to finish, and one that lingers after its last response
 * ({@link State#LINGERING}) ends on its own.
 *
 * <p>With TLS, the thread first layers an {@link SSLSocket} over the accepted socket and runs
 * the handshake, within {@link HttpTimeouts#handshakeTimeout()} from accept (the guard thread
 * closes the socket at the deadline, so a handshake sent one byte at a time ends too). Then it
 * serves requests over the TLS socket exactly as over plain TCP. Aborts (timeouts, the
 * shutdown deadline) close the TCP socket underneath, which never blocks; an orderly close goes
 * through the TLS socket and sends {@code close_notify}, watched by the guard like a write.
 * Over TLS, the guard also enforces the read limits (header, idle, read and linger timeouts) as
 * deadlines, since an {@code SSLSocket} read can outlast its {@code soTimeout} (see
 * {@link ConnectionInput}).
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
     *     went out with {@code Connection: close} (only then); it drops input before closing</td></tr>
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
    private final Socket socket; // the accepted TCP socket; with TLS, the one underneath
    private final long acceptedAt;
    private final InetSocketAddress remote; // the TCP peer, taken at accept; Request.remoteAddress()
    private final String peer;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private volatile Thread thread;
    private volatile boolean aborted;
    private volatile boolean writeTimedOut;
    private volatile GuardedOutput guarded; // read by the guard thread
    private volatile boolean handshaking; // read by the guard
    private volatile long handshakeDeadline; // System.nanoTime(), valid while handshaking; read by the guard
    private volatile boolean handshakeTimedOut;
    private volatile SSLSocket ssl; // the TLS socket after a completed handshake, else null; read by the guard
    private volatile ConnectionInput in; // read by the guard
    private BufferedOutputStream wire;
    private ResponseWriter writer;
    private boolean linger;

    Connection(HttpServer server, Socket socket) {
        this.server = server;
        this.socket = socket;
        this.acceptedAt = System.nanoTime();
        this.remote = remoteAddress(socket);
        this.peer = String.valueOf(remote);
    }

    /**
     * The address and port of the TCP peer of an accepted socket (with TLS, the TCP socket under
     * it). {@code new InetSocketAddress(InetAddress, int)} looks up no name, and nothing here asks
     * for one.
     *
     * @throws IllegalStateException if the socket is not connected (never after accept; the
     *                               server then closes it instead of serving it)
     */
    private static InetSocketAddress remoteAddress(Socket socket) {
        var address = socket.getInetAddress();
        if (address == null) {
            throw new IllegalStateException("the accepted socket has no remote address");
        }
        return new InetSocketAddress(address, socket.getPort());
    }

    void start(String name) {
        thread = Thread.ofVirtual().name(name).unstarted(this);
        thread.start();
    }

    Thread thread() {
        return thread;
    }

    /** Whether the connection was aborted (by close() after the deadline, a write timeout or a failure). */
    boolean aborted() {
        return aborted;
    }

    /** Whether the last response is done and the connection only drops input before closing. */
    boolean lingering() {
        return state.get() == State.LINGERING;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            Socket io = server.tls() == null ? socket : handshake();
            if (io == null) {
                return; // the handshake failed; logged
            }
            in = new ConnectionInput(io);
            guarded = new GuardedOutput(io.getOutputStream());
            wire = new BufferedOutputStream(guarded, 8_192);
            writer = new ResponseWriter(wire);
            serve();
        } catch (IOException e) {
            debug("connection from " + peer + " ended: " + LoggedThrowable.describe(e));
        } catch (Exception | Error e) {
            server.log().log(Level.ERROR, "connection from " + peer + " failed", LoggedThrowable.of(e));
        } finally {
            try {
                closeSocket();
            } finally {
                server.connectionEnded(this);
            }
        }
    }

    /**
     * Layers TLS over the socket and runs the handshake, until the deadline
     * {@link HttpTimeouts#handshakeTimeout()} after accept: every read waits at most the time left,
     * and the guard thread closes the socket when the deadline passes. Returns the TLS socket, or
     * null when the handshake failed or timed out (logged at {@code DEBUG}, escaped; the JDK's
     * messages hold no input bytes).
     */
    private Socket handshake() {
        long deadline = acceptedAt + server.options().timeouts().handshakeTimeout().toNanos();
        handshakeDeadline = deadline;
        handshaking = true;
        try {
            if (state.get() == State.CLOSED) {
                return null; // close() came first
            }
            var layered = server.tls().layer(socket);
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new SocketTimeoutException("handshake timeout");
            }
            socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, Math.max(1, (left + 999_999) / 1_000_000)));
            layered.startHandshake();
            ssl = layered;
            return layered;
        } catch (IOException | RuntimeException e) {
            if (handshakeTimedOut || e instanceof SocketTimeoutException) {
                debug("TLS handshake timeout on the connection from " + peer);
            } else {
                debug("TLS handshake with " + peer + " failed: " + LoggedThrowable.describe(e));
            }
            return null;
        } finally {
            handshaking = false;
        }
    }

    /** The negotiated TLS of this connection, for the request; empty for plain HTTP. */
    private Optional<TlsInfo> tlsInfo() {
        return ssl == null ? Optional.empty() : Optional.of(TlsInfo.of(ssl.getSession()));
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
        var request = new Request(head, body, tlsInfo(), remote);
        boolean headRequest = head.method().equals("HEAD");
        var outcome = skipUnreadBodyOrFail(handle(request, body), request, head, body);
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

    /**
     * Before a buffered response goes out, reads and drops what the handler left of the request
     * body; if that fails, the body's error replaces the handler's response. Leaves the outcome
     * as it is when the handler failed, the body already failed, the response streams, or the
     * client waits for {@code 100 Continue} (see {@link #exchange}). {@link BodyStream#skipRest()}
     * is idempotent: once the body has ended or failed, calling it again only reports that.
     */
    private Outcome skipUnreadBodyOrFail(Outcome outcome, Request request, RequestParser.Head head, BodyStream body) {
        boolean skip = !outcome.failed() && body.failure() == null
                && !streams(outcome.response(), head.method().equals("HEAD")) && !awaitsContinue(head, body);
        if (skip && !body.skipRest()) {
            return new Outcome(bodyFailureResponse(request, body), true);
        }
        return outcome;
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

    /**
     * Closes the connection now if it waits for a request or is in its TLS handshake (graceful
     * stop). With TLS the TCP input is shut down instead: the connection's blocked read ends, and
     * its own thread closes the TLS socket with {@code close_notify} (a write from this thread
     * could block on a client that does not read).
     */
    void closeIfIdle() {
        if (state.compareAndSet(State.IDLE, State.CLOSED)) {
            if (server.tls() == null) {
                HttpServer.closeQuietly(socket);
                return;
            }
            try {
                socket.shutdownInput();
            } catch (IOException e) {
                HttpServer.closeQuietly(socket);
            }
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

    /** Called by the guard thread: closes the socket of a TLS handshake that has passed its deadline. */
    void checkHandshake(long now) {
        if (handshaking && now - handshakeDeadline > 0) {
            handshakeTimedOut = true;
            HttpServer.closeQuietly(socket);
        }
    }

    /**
     * Called by the guard thread: over TLS, closes the TCP socket under a read that has passed
     * its deadline (a plain socket's {@code soTimeout} already bounds the read; see
     * {@link ConnectionInput}).
     */
    void checkRead(long now) {
        var input = in;
        if (ssl != null && input != null && input.overdue(now)) {
            input.cutOff();
            HttpServer.closeQuietly(socket);
        }
    }

    private void closeSocket() {
        if (linger && !aborted) {
            lingerBeforeClose();
        }
        state.set(State.CLOSED);
        var output = guarded;
        if (ssl != null && output != null && !aborted && !in.wasCutOff()) {
            try {
                output.guard(ssl::close); // close_notify; the guard closes the socket if it blocks
            } catch (IOException e) {
                // the client is gone; the socket is closed below
            }
        }
        HttpServer.closeQuietly(socket);
    }

    /**
     * Half-closes and drops input until the client closes, at most {@link #LINGER_MILLIS} and
     * {@link #LINGER_BYTES}, and during a graceful stop no longer than the shutdown deadline.
     */
    private void lingerBeforeClose() {
        try {
            wire.flush();
            if (ssl != null) {
                guarded.guard(ssl::shutdownOutput); // close_notify, then the TCP half-close
            } else {
                socket.shutdownOutput();
            }
            if (enterLingering()) {
                drainInput(lingerDeadline());
            }
        } catch (IOException e) {
            // the client is gone or the server closed the socket; either way we are done
        }
    }

    /** ACTIVE or IDLE to LINGERING; false if close() or abort() has closed the connection already. */
    private boolean enterLingering() {
        var current = state.get();
        return (current == State.ACTIVE || current == State.IDLE) && state.compareAndSet(current, State.LINGERING);
    }

    /** {@link #LINGER_MILLIS} from now, or the shutdown deadline if the server stops before that. */
    private long lingerDeadline() {
        long deadline = System.nanoTime() + LINGER_MILLIS * 1_000_000L;
        long stop = server.shutdownDeadline(); // 0 while the server runs
        return stop != 0 && stop - deadline < 0 ? stop : deadline;
    }

    /** Reads and drops input until end of stream, {@code deadline} or {@link #LINGER_BYTES}. */
    private void drainInput(long deadline) throws IOException {
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
    }
}
