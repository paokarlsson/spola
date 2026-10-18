package spola.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.Handler;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.HttpTimeouts;

/** Servers on 127.0.0.1 port 0 (spola.http and raw sockets) and helpers for the client's tests. */
final class Support {

    private Support() {
    }

    static final InetAddress LOCALHOST;

    static {
        try {
            LOCALHOST = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Five seconds for every server timeout. ConcurrencyTests relies on the idleTimeout: in about
     * one run of three, a call among 200 concurrent ones completes only when the server closes an
     * idle connection (the known stall in PROGRESS.md, also seen with java.net.http alone), so a
     * longer idleTimeout makes that test slower.
     */
    static final HttpTimeouts FAST = new HttpTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));

    /** A spola.http server on 127.0.0.1, port 0. */
    static HttpServer serve(Handler handler) throws IOException {
        return HttpServer.start(HttpServerOptions.DEFAULT.withAddress(LOCALHOST).withPort(0).withTimeouts(FAST), handler);
    }

    static URI base(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }

    static RestClient client(int port) {
        return RestClient.builder().baseUri(base(port)).build();
    }

    /** Milliseconds since {@code start} (a {@link System#nanoTime()}). */
    static long since(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** The stack trace of {@code t}, with causes and suppressed, as printed. */
    static String printed(Throwable t) {
        var out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /** True if every character is printable ASCII (no line break, no control). */
    static boolean printableAscii(String text) {
        return text.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    /** A raw HTTP/1.1 response head with the given status, headers (name, value, ...) and an end. */
    static String head(int status, String... headers) {
        var out = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n");
        for (int i = 0; i < headers.length; i += 2) {
            out.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
        }
        return out.append("\r\n").toString();
    }

    /** A complete raw response with {@code Content-Length}. */
    static byte[] response(int status, String contentType, byte[] body) {
        var head = contentType == null
                ? head(status, "Content-Length", String.valueOf(body.length), "Connection", "close")
                : head(status, "Content-Type", contentType, "Content-Length", String.valueOf(body.length),
                        "Connection", "close");
        var out = new ByteArrayOutputStream();
        out.writeBytes(ascii(head));
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** What a raw server does with one connection. */
    @FunctionalInterface
    interface Script {
        void run(Socket socket, RawServer server) throws Exception;
    }

    /**
     * A server on a raw {@link ServerSocket} (127.0.0.1, port 0): every connection runs the
     * script on its own virtual thread. It records the requests it read, the first byte of each
     * connection when asked to, and when a connection was seen to close.
     */
    static final class RawServer implements AutoCloseable {

        private final ServerSocket socket;
        private final Script script;
        private final Set<Socket> open = ConcurrentHashMap.newKeySet();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<Long> closedAt = new CopyOnWriteArrayList<>(); // System.nanoTime() when the client closed
        final AtomicInteger connections = new AtomicInteger();
        final List<Integer> firstBytes = new CopyOnWriteArrayList<>();

        RawServer(Script script) throws IOException {
            this.script = script;
            this.socket = new ServerSocket(0, 50, LOCALHOST);
            Thread.ofVirtual().start(this::accept);
        }

        int port() {
            return socket.getLocalPort();
        }

        URI base() {
            return Support.base(port());
        }

        private void accept() {
            while (!socket.isClosed()) {
                try {
                    var connection = socket.accept();
                    connections.incrementAndGet();
                    open.add(connection);
                    Thread.ofVirtual().start(() -> {
                        try (connection) {
                            script.run(connection, this);
                        } catch (Exception e) {
                            // the client went away; the scripts record what they need
                        } finally {
                            open.remove(connection);
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        }

        /**
         * Reads one request (head and a {@code Content-Length} body) and records it; null at the
         * end of the stream.
         */
        String readRequest(InputStream in) throws IOException {
            var head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = in.read();
                if (b < 0) {
                    return null;
                }
                head.write(b);
                matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
            }
            var text = head.toString(StandardCharsets.ISO_8859_1);
            int length = 0;
            for (var line : text.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).strip());
                }
            }
            var body = in.readNBytes(length);
            var request = text + new String(body, StandardCharsets.UTF_8);
            requests.add(request);
            return request;
        }

        /** Blocks until the client closes the connection, then records the time. */
        void awaitClose(InputStream in) {
            try {
                while (in.read() >= 0) {
                    // drain
                }
            } catch (IOException e) {
                // reset by the client: closed as well
            }
            closedAt.add(System.nanoTime());
        }

        /** Writes {@code chunk} every {@code everyMillis} until the client closes, then records the time. */
        void trickle(OutputStream out, byte[] chunk, long everyMillis) {
            try {
                while (true) {
                    out.write(chunk);
                    out.flush();
                    if (everyMillis > 0) {
                        Thread.sleep(everyMillis);
                    }
                }
            } catch (IOException e) {
                closedAt.add(System.nanoTime());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (var connection : open) {
                connection.close();
            }
        }
    }

    /** A raw server that reads one request per connection and answers with {@code response}. */
    static RawServer answering(byte[] response) throws IOException {
        return new RawServer((socket, server) -> {
            var in = socket.getInputStream();
            if (server.readRequest(in) != null) {
                socket.getOutputStream().write(response);
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                server.awaitClose(in);
            }
        });
    }
}
