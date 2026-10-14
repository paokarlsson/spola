package spola.client;

import static spola.client.Support.ascii;
import static spola.client.Support.head;
import static spola.client.Support.response;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import spola.json.JsonObject;
import spola.json.JsonString;

/**
 * Every kind of failure of criteria 6–9, each made against its own local server with a given
 * setup: default headers, a request header, a path (with a query) and a JSON body. The message
 * and secret tests run them all.
 */
final class Failures {

    private Failures() {
    }

    /**
     * How a failing call is made.
     *
     * @param defaults      default headers, as name and value pairs
     * @param requestHeader a header on the request: name and value
     * @param path          the path, with a query
     * @param body          the JSON body
     */
    record Setup(List<String> defaults, List<String> requestHeader, String path, JsonObject body) {

        static final Setup PLAIN = new Setup(List.of("x-api-key", "plain-key"), List.of("X-Trace", "t1"),
                "/v1/items?page=2", JsonObject.of("q", new JsonString("tea")));

        RestClient.Builder builder(URI base) {
            var builder = RestClient.builder().baseUri(base);
            for (int i = 0; i < defaults.size(); i += 2) {
                builder.defaultHeader(defaults.get(i), defaults.get(i + 1));
            }
            return builder;
        }

        RestRequest request(RestClient client) {
            return client.post(path).header(requestHeader.get(0), requestHeader.get(1)).json(body);
        }

        /** The URI that messages may show: the base and the path without the query. */
        String shownUri(URI base) {
            int query = path.indexOf('?');
            return base + (query >= 0 ? path.substring(0, query) : path);
        }
    }

    /** One way to fail. */
    @FunctionalInterface
    interface Make {
        RestClientException make(Setup setup) throws Exception;
    }

    /**
     * A kind of failure.
     *
     * @param name     what it is
     * @param status   the status for a response failure, -1 for a transport failure
     * @param timedOut for a transport failure, whether it timed out
     * @param make     makes it
     */
    record Kind(String name, int status, boolean timedOut, Make make) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** Runs {@code call} on {@code request} and returns what it threw. */
    static RestClientException thrown(RestRequest request, boolean json) {
        try {
            if (json) {
                request.retrieveJson();
            } else {
                request.retrieve(ResponseTests.POINT);
            }
        } catch (RestClientException e) {
            return e;
        }
        throw new AssertionError("nothing thrown");
    }

    /** Against a raw server answering every request with {@code response}. */
    static RestClientException against(byte[] response, Setup setup, boolean json) throws IOException {
        try (var server = Support.answering(response); var client = setup.builder(server.base()).build()) {
            return thrown(setup.request(client), json);
        }
    }

    /** Against a raw server running {@code script}, with {@code timeout} and {@code maxResponseSize}. */
    static RestClientException against(Support.Script script, Setup setup, Duration timeout, int maxResponseSize)
            throws IOException {
        try (var server = new Support.RawServer(script);
                var client = setup.builder(server.base()).timeout(timeout).maxResponseSize(maxResponseSize).build()) {
            return thrown(setup.request(client), true);
        }
    }

    static final Support.Script NEVER_ANSWERS = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            raw.awaitClose(in);
        }
    };

    static final Support.Script TRICKLES = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            var out = socket.getOutputStream();
            out.write(ascii(head(200, "Content-Type", "application/json", "Content-Length", "1000000")));
            raw.trickle(out, ascii(" "), 100);
        }
    };

    static final Support.Script GARBAGE = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            socket.getOutputStream().write(ascii("SSH-2.0-OpenSSH_9.6 garbage\r\n\r\n"));
            socket.getOutputStream().flush();
        }
    };

    static final Support.Script CLOSES_IN_BODY = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            socket.getOutputStream().write(ascii(head(200, "Content-Type", "application/json", "Content-Length", "100")
                    + "{\"x\":1,"));
            socket.getOutputStream().flush();
        }
    };

    static final Support.Script ECHOES = (socket, raw) -> {
        var in = socket.getInputStream();
        var request = raw.readRequest(in);
        if (request != null) {
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
        }
    };

    static final Support.Script ECHOES_BODY = (socket, raw) -> {
        var in = socket.getInputStream();
        var request = raw.readRequest(in);
        if (request != null) {
            var body = request.substring(request.indexOf("\r\n\r\n") + 4);
            socket.getOutputStream().write((body + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }
    };

    static final Support.Script READS_FIRST_BYTE = (socket, raw) -> {
        raw.firstBytes.add(socket.getInputStream().read());
    };

    static final Support.Script ENDLESS_CHUNKED = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            var out = socket.getOutputStream();
            out.write(ascii(head(200, "Content-Type", "application/json", "Transfer-Encoding", "chunked")));
            raw.trickle(out, ascii("40\r\n" + "1,".repeat(32) + "\r\n"), 1);
        }
    };

    static final Support.Script ENDLESS_ERROR = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            var out = socket.getOutputStream();
            out.write(ascii(head(500, "Content-Type", "text/plain", "Transfer-Encoding", "chunked")));
            raw.trickle(out, ascii("400\r\n" + "x".repeat(1024) + "\r\n"), 0);
        }
    };

    static final Support.Script HUGE_LENGTH = (socket, raw) -> {
        var in = socket.getInputStream();
        if (raw.readRequest(in) != null) {
            socket.getOutputStream().write(ascii(head(200, "Content-Length", "10000000000")));
            socket.getOutputStream().flush();
            raw.awaitClose(in);
        }
    };

    /** A port that nothing listens on. */
    static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0, 1, Support.LOCALHOST)) {
            return socket.getLocalPort();
        }
    }

    /** The call on another thread, interrupted after 200 ms. */
    static RestClientException interrupted(Setup setup) throws Exception {
        var thrown = new AtomicReference<RestClientException>();
        try (var server = new Support.RawServer(NEVER_ANSWERS); var client = setup.builder(server.base()).build()) {
            var caller = Thread.ofVirtual().start(() -> thrown.set(thrown(setup.request(client), true)));
            Thread.sleep(200);
            caller.interrupt();
            caller.join(5_000);
        }
        return thrown.get();
    }

    static final Duration SHORT = Duration.ofMillis(500);

    static List<Kind> all() {
        return List.of(
                new Kind("302 to another server", 302, false, setup -> against(ascii(head(302,
                        "Location", "http://127.0.0.1:1/elsewhere", "Content-Length", "0", "Connection", "close")),
                        setup, true)),
                new Kind("400 with a JSON error", 400, false, setup -> against(response(400, "application/json",
                        ascii("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\"}}")), setup, true)),
                new Kind("200 with an empty body", 200, false, setup -> against(response(200, "application/json",
                        new byte[0]), setup, false)),
                new Kind("200 with HTML", 200, false, setup -> against(response(200, "text/html",
                        ascii("<html><body>Oops</body></html>")), setup, false)),
                new Kind("200 with broken JSON", 200, false, setup -> against(response(200, "application/json",
                        ascii("{\"x\":")), setup, false)),
                new Kind("200 with JSON that does not fit the codec", 200, false, setup -> against(response(200,
                        "application/json", ascii("{\"x\":\"one\"}")), setup, false)),
                new Kind("Content-Length over the limit", 200, false,
                        setup -> against(HUGE_LENGTH, setup, Duration.ofSeconds(30), 1024)),
                new Kind("a chunked body that never ends", 200, false,
                        setup -> against(ENDLESS_CHUNKED, setup, Duration.ofSeconds(30), 1024)),
                new Kind("a 500 with a body that never ends", 500, false,
                        setup -> against(ENDLESS_ERROR, setup, Duration.ofSeconds(30), 1024)),
                new Kind("a server that never answers", -1, true,
                        setup -> against(NEVER_ANSWERS, setup, SHORT, 1024)),
                new Kind("a body of one byte every 100 ms", -1, true,
                        setup -> against(TRICKLES, setup, SHORT, 1 << 20)),
                new Kind("a refused connection", -1, false, setup -> {
                    try (var client = setup.builder(Support.base(closedPort())).build()) {
                        return thrown(setup.request(client), true);
                    }
                }),
                new Kind("garbage instead of HTTP", -1, false,
                        setup -> against(GARBAGE, setup, Duration.ofSeconds(10), 1024)),
                new Kind("a server that echoes the request", -1, false,
                        setup -> against(ECHOES, setup, Duration.ofSeconds(10), 1024)),
                new Kind("a server that echoes the request body", -1, false,
                        setup -> against(ECHOES_BODY, setup, Duration.ofSeconds(10), 1024)),
                new Kind("a connection closed inside a body with Content-Length", -1, false,
                        setup -> against(CLOSES_IN_BODY, setup, Duration.ofSeconds(10), 1024)),
                new Kind("https against a server that does not speak TLS", -1, false, setup -> {
                    try (var server = new Support.RawServer(READS_FIRST_BYTE);
                            var client = setup.builder(URI.create("https://127.0.0.1:" + server.port())).build()) {
                        return thrown(setup.request(client), true);
                    }
                }),
                new Kind("an interrupted caller", -1, false, Failures::interrupted));
    }
}
