package spola.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.HttpTimeouts;
import spola.json.Json;
import spola.json.JsonObject;

/** Servers on 127.0.0.1 port 0, a raw socket client, an HttpClient and a captured log. */
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

    static final HttpTimeouts FAST = new HttpTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));

    static HttpServerOptions options() {
        return HttpServerOptions.DEFAULT.withAddress(LOCALHOST).withPort(0).withTimeouts(FAST);
    }

    static HttpServer serve(Router router) throws IOException {
        return HttpServer.start(options(), router);
    }

    static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** Sends {@code method} with an optional body to {@code target} through {@link HttpClient}. */
    static HttpResponse<String> call(HttpClient client, HttpServer server, String method, String target, String body)
            throws Exception {
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + target))
                .method(method, publisher).timeout(Duration.ofSeconds(10)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A response as read from the wire.
     *
     * @param status  the status code
     * @param headers the header lines as name and value
     * @param body    the body as UTF-8 text
     */
    record Reply(int status, List<String[]> headers, String body) {
        String header(String name) {
            for (var h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        JsonObject json() {
            return (JsonObject) Json.parse(body);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    /** One request on a new connection with {@code Connection: close}; the whole response. */
    static Reply send(int port, String method, String target, String... headerLines) throws IOException {
        var request = new StringBuilder(method).append(' ').append(target)
                .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for (var line : headerLines) {
            request.append(line).append("\r\n");
        }
        request.append("\r\n");
        return raw(port, request.toString().getBytes(StandardCharsets.ISO_8859_1), method.equals("HEAD"));
    }

    /** Sends {@code bytes} and reads the one response until the server closes. */
    static Reply raw(int port, byte[] bytes, boolean head) throws IOException {
        try (var socket = new Socket(LOCALHOST, port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            var all = new ByteArrayOutputStream();
            var buffer = new byte[8_192];
            try {
                int n;
                while ((n = socket.getInputStream().read(buffer)) >= 0) {
                    all.write(buffer, 0, n);
                }
            } catch (SocketException e) {
                // reset after the response: closed as well
            }
            return parse(all.toByteArray());
        }
    }

    private static Reply parse(byte[] bytes) throws IOException {
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            throw new IOException("no complete response: \"" + text + "\"");
        }
        var lines = text.substring(0, end).split("\r\n");
        int status = Integer.parseInt(lines[0].substring(9, 12));
        var headers = new ArrayList<String[]>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.add(new String[] {lines[i].substring(0, colon), lines[i].substring(colon + 1).strip()});
        }
        var body = new String(bytes, end + 4, bytes.length - end - 4, StandardCharsets.UTF_8);
        return new Reply(status, List.copyOf(headers), body);
    }

    /** A System.Logger that keeps every record, rendered with its stack trace. */
    static final class CapturedLog implements System.Logger {
        private final List<String> records = new ArrayList<>();

        @Override
        public String getName() {
            return "spola.web";
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
}
