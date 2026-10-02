package spola.http;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An HTTP/1.1 server on a {@link ServerSocket}, with one virtual thread per connection and
 * blocking I/O.
 *
 * <pre>{@code
 * try (var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080),
 *         request -> Response.text(200, "hello"))) {
 *     System.out.println("listening on " + server.port());
 *     ...
 * } // graceful stop
 * }</pre>
 *
 * <p>One platform thread accepts connections; it is not a daemon thread, so a started server
 * keeps the JVM alive until {@link #close()}. It takes a connection only when fewer than
 * {@link HttpLimits#maxConnections()} are open; otherwise new connections wait in the operating
 * system's backlog. A guard thread enforces {@link HttpTimeouts#writeTimeout()}.
 *
 * <p>The server's only shared mutable state is the count of open connections and the set of
 * connections that {@link #close()} needs; everything else belongs to one connection's thread.
 */
public final class HttpServer implements AutoCloseable {

    /** The listen backlog: connections the operating system queues before they are accepted. */
    static final int BACKLOG = 1_024;

    /**
     * How long {@link #close()} waits for a connection thread it has aborted (socket closed,
     * thread interrupted) before it gives up on it. A handler that ignores interrupts and does not
     * touch the socket would otherwise keep {@code close()} from ever returning.
     */
    static final long ABORT_GRACE_MILLIS = 1_000;

    private final HttpServerOptions options;
    private final Handler handler;
    private final System.Logger log;
    private final ServerSocket serverSocket;
    private final Semaphore permits;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionIds = new AtomicLong();
    private final Thread acceptor;
    private final Thread guard;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile long shutdownDeadline; // System.nanoTime() when close() aborts; 0 before close()
    private volatile boolean guardStopped;

    private HttpServer(HttpServerOptions options, Handler handler, System.Logger log) throws IOException {
        this.options = options;
        this.handler = handler;
        this.log = log;
        this.permits = new Semaphore(options.limits().maxConnections());
        this.serverSocket = new ServerSocket();
        try {
            serverSocket.bind(new InetSocketAddress(options.address(), options.port()), BACKLOG);
        } catch (IOException | RuntimeException e) {
            serverSocket.close();
            throw e;
        }
        int port = serverSocket.getLocalPort();
        this.acceptor = Thread.ofPlatform().name("spola-http-acceptor-" + port).daemon(false).unstarted(this::accept);
        this.guard = Thread.ofVirtual().name("spola-http-write-guard-" + port).unstarted(this::guardWrites);
    }

    /**
     * Binds the address and port of {@code options} and starts serving with {@code handler}.
     * Logs to the {@link System.Logger} named {@code spola.http}.
     *
     * @param options where to listen, the limits and the timeouts
     * @param handler answers every request
     * @return the started server
     * @throws IOException if the address cannot be bound (for example, the port is taken)
     */
    public static HttpServer start(HttpServerOptions options, Handler handler) throws IOException {
        return start(options, handler, System.getLogger("spola.http"));
    }

    /** As {@link #start(HttpServerOptions, Handler)} with another logger (the tests capture the log). */
    static HttpServer start(HttpServerOptions options, Handler handler, System.Logger log) throws IOException {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(log, "log");
        var server = new HttpServer(options, handler, log);
        server.guard.start();
        server.acceptor.start();
        if (log.isLoggable(Level.INFO)) {
            log.log(Level.INFO, "listening on " + server.serverSocket.getLocalSocketAddress());
        }
        return server;
    }

    /**
     * The port the server listens on (the one chosen by the operating system for port 0).
     *
     * @return the port
     */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * The local address the server listens on.
     *
     * @return the address
     */
    public InetAddress address() {
        return serverSocket.getInetAddress();
    }

    HttpServerOptions options() {
        return options;
    }

    Handler handler() {
        return handler;
    }

    System.Logger log() {
        return log;
    }

    boolean closing() {
        return closing.get();
    }

    /** {@link System#nanoTime()} at which {@link #close()} aborts what is left, or 0 before close(). */
    long shutdownDeadline() {
        return shutdownDeadline;
    }

    /** The number of open connections (for tests). */
    int openConnections() {
        return connections.size();
    }

    private void accept() {
        while (!closing.get()) {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                return;
            }
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException | RuntimeException | Error e) {
                permits.release();
                if (closing.get()) {
                    return;
                }
                log.log(Level.WARNING, "accepting a connection failed", LoggedThrowable.of(e));
                pause();
                continue;
            }
            if (closing.get()) {
                permits.release();
                closeQuietly(socket);
                return;
            }
            var connection = new Connection(this, socket);
            try {
                connections.add(connection);
                connection.start("spola-http-" + serverSocket.getLocalPort() + "-" + connectionIds.incrementAndGet());
            } catch (RuntimeException | Error e) {
                connections.remove(connection);
                permits.release();
                closeQuietly(socket);
                log.log(Level.ERROR, "starting a connection thread failed", LoggedThrowable.of(e));
                pause();
            }
        }
    }

    /** A short pause after a failed accept, so that running out of file descriptors does not spin. */
    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void connectionEnded(Connection connection) {
        if (connections.remove(connection)) {
            permits.release();
        }
    }

    private void guardWrites() {
        long timeout = options.timeouts().writeTimeout().toNanos();
        long period = Math.clamp(timeout / 4 / 1_000_000, 5, 1_000);
        while (!guardStopped) {
            try {
                Thread.sleep(period);
            } catch (InterruptedException e) {
                continue; // guardStopped decides
            }
            long now = System.nanoTime();
            for (var connection : connections) {
                connection.checkWrite(now, timeout);
            }
        }
    }

    /**
     * Stops the server gracefully: stops accepting connections, closes connections that wait
     * for a request, lets requests in progress finish (their responses get
     * {@code Connection: close}), and after {@link HttpTimeouts#shutdownTimeout()} aborts what is
     * left (closes the sockets and interrupts the threads). Returns when every connection thread
     * has ended, or at the latest {@link #ABORT_GRACE_MILLIS} after the abort.
     *
     * <p>Only the first call does the work. A later call from a handler (a connection's thread)
     * returns at once, so that its response can go out (with {@code Connection: close}); a later
     * call from any other thread waits until the first call has returned. The first call never
     * waits for the caller's own connection, so a handler may call it too.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            if (!onConnectionThread()) {
                awaitUninterruptibly(closed);
            }
            return;
        }
        try {
            shutdown();
        } finally {
            closed.countDown();
        }
    }

    private boolean onConnectionThread() {
        var self = Thread.currentThread();
        return connections.stream().anyMatch(c -> c.thread() == self);
    }

    private void shutdown() {
        long deadline = System.nanoTime() + options.timeouts().shutdownTimeout().toNanos();
        shutdownDeadline = deadline | 1; // never 0
        boolean interrupted = false;
        closeQuietly(serverSocket);
        acceptor.interrupt();
        interrupted |= joinUninterruptibly(acceptor);
        // The acceptor has ended: no connection is added from here on. The caller's own connection
        // (a handler that calls close()) is not waited for: it would wait for itself.
        var self = Thread.currentThread();
        List<Connection> open = connections.stream().filter(c -> c.thread() != self).toList();
        for (var connection : open) {
            connection.closeIfIdle();
        }
        for (var connection : open) {
            interrupted |= joinUntil(connection.thread(), deadline);
        }
        var left = open.stream().filter(c -> c.thread().isAlive()).toList();
        if (!left.isEmpty()) {
            // a connection that only lingers after its last response ends at the deadline by itself
            long busy = left.stream().filter(c -> !c.lingering()).count();
            if (busy > 0) {
                log.log(Level.WARNING, "shutdown timeout: aborting " + busy + " connection(s) still in progress");
            }
            left.forEach(Connection::abort);
            long grace = System.nanoTime() + ABORT_GRACE_MILLIS * 1_000_000L;
            for (var connection : left) {
                interrupted |= joinUntil(connection.thread(), grace);
            }
            long stuck = left.stream().filter(c -> c.thread().isAlive()).count();
            if (stuck > 0) {
                log.log(Level.WARNING, "shutdown: " + stuck + " aborted connection thread(s) did not end; not waiting");
            }
        }
        guardStopped = true;
        guard.interrupt();
        interrupted |= joinUninterruptibly(guard);
        if (log.isLoggable(Level.INFO)) {
            log.log(Level.INFO, "stopped listening on port " + serverSocket.getLocalPort());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Joins {@code thread} until {@code deadline} ({@link System#nanoTime()}); returns whether the caller was interrupted. */
    private static boolean joinUntil(Thread thread, long deadline) {
        boolean interrupted = false;
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return interrupted;
            }
            try {
                thread.join(Duration.ofNanos(left));
                return interrupted;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Joins {@code thread}; returns whether the caller was interrupted on the way. */
    private static boolean joinUninterruptibly(Thread thread) {
        boolean interrupted = false;
        while (true) {
            try {
                thread.join();
                return interrupted;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
    }

    static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // nothing more to do
        }
    }
}
