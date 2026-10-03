package spola.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javax.net.ssl.SSLException;

/** Servers on 127.0.0.1 port 0 with short timeouts, a raw socket client and a log that is captured. */
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

    /** Short timeouts so that a test that goes wrong ends quickly. */
    static final HttpTimeouts FAST = new HttpTimeouts(Duration.ofSeconds(3), Duration.ofSeconds(3),
            Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3));

    static HttpServerOptions options() {
        return HttpServerOptions.DEFAULT.withAddress(LOCALHOST).withPort(0).withTimeouts(FAST);
    }

    static HttpServer start(Handler handler) throws IOException {
        return start(options(), handler);
    }

    static HttpServer start(HttpServerOptions options, Handler handler) throws IOException {
        return HttpServer.start(options, handler, new CapturedLog());
    }

    static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /** "GET path HTTP/1.1" with a Host and the extra header lines. */
    static String get(String path, String... headerLines) {
        var out = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\nHost: localhost\r\n");
        for (var line : headerLines) {
            out.append(line).append("\r\n");
        }
        return out.append("\r\n").toString();
    }

    /** A System.Logger that keeps every record, rendered with its stack trace. */
    static final class CapturedLog implements System.Logger {
        private final List<String> records = new ArrayList<>();

        @Override
        public String getName() {
            return "spola.http";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            var text = new StringBuilder(level + " " + message);
            if (thrown != null) {
                var trace = new StringWriter();
                thrown.printStackTrace(new PrintWriter(trace));
                text.append('\n').append(trace);
            }
            synchronized (records) {
                records.add(text.toString());
            }
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            log(level, bundle, params == null || params.length == 0 ? format : MessageFormat.format(format, params),
                    (Throwable) null);
        }

        List<String> records() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }

    /**
     * A response as read from the wire.
     *
     * @param statusLine the status line without CRLF
     * @param headers    the header lines as name and value
     * @param body       the body bytes (decoded if chunked)
     */
    record Reply(String statusLine, List<String[]> headers, byte[] body) {
        int status() {
            return Integer.parseInt(statusLine.substring(9, 12));
        }

        String header(String name) {
            for (var h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        List<String> headerValues(String name) {
            var values = new ArrayList<String>();
            for (var h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    values.add(h[1]);
                }
            }
            return values;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        boolean closes() {
            return "close".equalsIgnoreCase(header("Connection"));
        }
    }

    /** A raw HTTP client on one socket, for exact bytes, broken requests and slow clients. */
    static final class Client implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;

        Client(int port) throws IOException {
            this(port, 5_000);
        }

        Client(int port, int readTimeoutMillis) throws IOException {
            this(new Socket(LOCALHOST, port), readTimeoutMillis);
        }

        private Client(Socket socket, int readTimeoutMillis) throws IOException {
            this.socket = socket;
            socket.setSoTimeout(readTimeoutMillis);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        /** A client on a connected socket (a TLS socket after its handshake). */
        static Client over(Socket socket, int readTimeoutMillis) throws IOException {
            return new Client(socket, readTimeoutMillis);
        }

        Client send(String text) throws IOException {
            return send(text.getBytes(StandardCharsets.ISO_8859_1));
        }

        Client send(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
            return this;
        }

        /** A line ending in CRLF, without it. */
        String line() throws IOException {
            var line = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("connection closed after \"" + ascii(line.toByteArray()) + "\"");
                }
                if (b == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        throw new IOException("CR without LF from the server");
                    }
                    return ascii(line.toByteArray());
                }
                if (b == '\n') {
                    throw new IOException("LF without CR from the server");
                }
                line.write(b);
            }
        }

        /** The status line and the headers of the next response, without the body. */
        Reply head() throws IOException {
            var status = line();
            var headers = new ArrayList<String[]>();
            String line;
            while (!(line = line()).isEmpty()) {
                int colon = line.indexOf(':');
                headers.add(new String[] {line.substring(0, colon), line.substring(colon + 1).strip()});
            }
            return new Reply(status, headers, new byte[0]);
        }

        Reply read() throws IOException {
            return read(false);
        }

        /** The next response, with its body framed as the headers say. */
        Reply read(boolean headRequest) throws IOException {
            var head = head();
            int status = head.status();
            if (headRequest || status == 204 || status == 304 || (status >= 100 && status < 200)) {
                return head;
            }
            byte[] body;
            var length = head.header("Content-Length");
            if ("chunked".equalsIgnoreCase(head.header("Transfer-Encoding"))) {
                body = dechunk();
            } else if (length != null) {
                body = exactly(Integer.parseInt(length));
            } else {
                body = rest();
            }
            return new Reply(head.statusLine(), head.headers(), body);
        }

        private byte[] dechunk() throws IOException {
            var body = new ByteArrayOutputStream();
            while (true) {
                int size = Integer.parseInt(line(), 16);
                if (size == 0) {
                    while (!line().isEmpty()) {
                        // trailers
                    }
                    return body.toByteArray();
                }
                body.write(exactly(size));
                if (!line().isEmpty()) {
                    throw new IOException("chunk data not followed by CRLF");
                }
            }
        }

        byte[] exactly(int n) throws IOException {
            var bytes = in.readNBytes(n);
            if (bytes.length != n) {
                throw new EOFException("expected " + n + " bytes, got " + bytes.length);
            }
            return bytes;
        }

        /** Everything until the server closes (a reset counts as closed). */
        byte[] rest() throws IOException {
            var all = new ByteArrayOutputStream();
            var buffer = new byte[8_192];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    all.write(buffer, 0, n);
                }
            } catch (SocketException | SSLException e) {
                // reset by the server: closed as well
            }
            return all.toByteArray();
        }

        /** Whether the server has closed the connection: end of stream (or reset) with no more bytes. */
        boolean closedByServer() throws IOException {
            try {
                return in.read() < 0;
            } catch (SocketTimeoutException e) {
                return false;
            } catch (SocketException | SSLException e) { // reset, or TLS ended without close_notify
                return true;
            }
        }

        /** Whether nothing arrives within {@code millis} (and the connection is still open). */
        boolean silentFor(int millis) throws IOException {
            int before = socket.getSoTimeout();
            socket.setSoTimeout(millis);
            try {
                in.mark(1);
                int b = in.read();
                if (b >= 0) {
                    in.reset();
                }
                return false;
            } catch (SocketTimeoutException e) {
                return true;
            } finally {
                socket.setSoTimeout(before);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** {@code text} with every char outside printable ASCII shown as \\uXXXX (for messages). */
    static String visible(String text) {
        var out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format(Locale.ROOT, "\\u%04x", (int) c));
        }
        return out.toString();
    }
}
