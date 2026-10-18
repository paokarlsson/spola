package spola.client;

import static spola.client.Support.ascii;
import static spola.client.Support.head;
import static spola.client.Support.response;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import spola.json.Json;
import spola.json.JsonObject;
import spola.json.JsonString;

/**
 * Every kind of failure of criteria 6–9, each made against its own local server with a given
 * setup: default headers, a request header, a path (with a query) and a JSON body. The message
 * and secret tests run them all. Some servers send back what they got, as ordinary servers do (a
 * validation error with the input, an echo endpoint, a problem with the path, a 401 that names
 * the credentials of Authorization on their own) or as a broken one might (the request as the
 * status line, a part of a header value in a bad header).
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

        /** The credentials of the default Authorization header (after its scheme), or {@code plain-token}. */
        String token() {
            for (int i = 0; i < defaults.size(); i += 2) {
                if (defaults.get(i).equalsIgnoreCase("Authorization")) {
                    var value = defaults.get(i + 1);
                    return value.substring(value.indexOf(' ') + 1);
                }
            }
            return "plain-token";
        }

        /**
         * This setup without a default Authorization header, so that its value and credentials are
         * not among the request's values and only the request's own Authorization can hide them.
         */
        Setup withoutAuthorization() {
            var kept = new ArrayList<String>();
            for (int i = 0; i < defaults.size(); i += 2) {
                if (!defaults.get(i).equalsIgnoreCase("Authorization")) {
                    kept.add(defaults.get(i));
                    kept.add(defaults.get(i + 1));
                }
            }
            return new Setup(List.copyOf(kept), requestHeader, path, body);
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

    /** Sends {@code request} with {@code retrieveJson()} ({@code json}) or {@code retrieve(POINT)} and returns what it threw. */
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

    /** A server that reads a request and writes what {@code answer} makes of it (the request's head and body as text). */
    static Support.Script sendsBack(Function<String, String> answer) {
        return (socket, raw) -> {
            var in = socket.getInputStream();
            var request = raw.readRequest(in);
            if (request != null) {
                socket.getOutputStream().write(answer.apply(request).getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                socket.shutdownOutput();
                raw.awaitClose(in);
            }
        };
    }

    static String requestLine(String request) {
        return request.substring(0, request.indexOf("\r\n"));
    }

    static String requestBody(String request) {
        return request.substring(request.indexOf("\r\n\r\n") + 4);
    }

    /** The value of the first header {@code name} of the request, in any case. */
    static String headerValue(String request, String name) {
        var value = headerValueOrNull(request, name);
        if (value == null) {
            throw new AssertionError("no header " + name + " in " + request);
        }
        return value;
    }

    /** The value of the first header {@code name} of the request, in any case, or {@code null}. */
    static String headerValueOrNull(String request, String name) {
        for (var line : request.substring(0, request.indexOf("\r\n\r\n")).split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT) + ":")) {
                return line.substring(name.length() + 1).strip();
            }
        }
        return null;
    }

    /** A JSON response with {@code status} and {@code body} (UTF-8), as text. */
    static String jsonResponse(int status, String body) {
        return head(status, "Content-Type", "application/json", "Content-Length",
                String.valueOf(body.getBytes(StandardCharsets.UTF_8).length), "Connection", "close") + body;
    }

    /** The whole request back, as if it were the response. */
    static final Support.Script ECHOES = sendsBack(request -> request);

    /** The request body back as the status line. */
    static final Support.Script ECHOES_BODY = sendsBack(request -> requestBody(request) + "\r\n\r\n");

    /** The request line without its end, then the connection closes: the JDK quotes the part it got. */
    static final Support.Script ECHOES_PART_OF_LINE = sendsBack(Failures::requestLine);

    /**
     * A header with the x-api-key value without its first three characters (so not the whole
     * value) and a control character inside, which the JDK refuses and quotes (it would trim one at
     * the end).
     */
    static final Support.Script ECHOES_PART_OF_KEY = sendsBack(request -> {
        var key = headerValue(request, "x-api-key");
        return "HTTP/1.1 200 OK\r\nX-Echo: [" + key.substring(Math.min(3, key.length())) + "\u0001]\r\n\r\n";
    });

    /** FastAPI's 422 for a missing member: the request body is the {@code input}. */
    static final Support.Script VALIDATION_ECHO = sendsBack(request -> jsonResponse(422,
            "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\",\"max_tokens\"],\"msg\":\"Field required\","
                    + "\"input\":" + requestBody(request) + "}]}"));

    /** An echo endpoint (httpbin's /anything): 200 with the request's headers as JSON. */
    static final Support.Script HEADER_ECHO = sendsBack(request -> {
        var headers = JsonObject.builder();
        for (var line : request.substring(request.indexOf("\r\n") + 2, request.indexOf("\r\n\r\n")).split("\r\n")) {
            int colon = line.indexOf(':');
            headers.put(line.substring(0, colon), line.substring(colon + 1).strip());
        }
        return jsonResponse(200, Json.write(JsonObject.of("headers", headers.build(),
                "method", new JsonString("POST"))));
    });

    /** A 404 problem whose {@code instance} is the request's target with its query. */
    static final Support.Script PROBLEM_ECHO = sendsBack(request -> jsonResponse(404, Json.write(JsonObject.of(
            "title", new JsonString("Not Found"), "status", spola.json.JsonNumber.of(404),
            "instance", new JsonString(requestLine(request).split(" ")[1])))));

    /**
     * A 401 that names the credentials of the request's header {@code name} on their own, as some
     * APIs do: the text after the scheme ("Invalid token: sk-..."), or for Basic the decoded
     * user:password. Without the header: "Missing credentials".
     */
    static Support.Script namesCredentials(String name) {
        return sendsBack(request -> {
            var value = headerValueOrNull(request, name);
            String message;
            if (value == null) {
                message = "Missing credentials";
            } else {
                var credentials = value.substring(value.indexOf(' ') + 1).strip();
                message = value.regionMatches(true, 0, "Basic ", 0, 6)
                        ? "Invalid credentials for " + new String(Base64.getDecoder().decode(credentials),
                                StandardCharsets.UTF_8)
                        : "Invalid token: " + credentials;
            }
            return jsonResponse(401, Json.write(JsonObject.of("error", JsonObject.of(
                    "type", new JsonString("authentication_error"), "message", new JsonString(message)))));
        });
    }

    static final Support.Script NAMES_CREDENTIALS = namesCredentials("Authorization");

    /**
     * Against {@link #NAMES_CREDENTIALS} with {@code authorization} as the request's own
     * Authorization header, and the setup's default one left out.
     */
    static RestClientException namedCredentials(Setup setup, String authorization) throws IOException {
        var withoutDefault = setup.withoutAuthorization();
        try (var server = new Support.RawServer(NAMES_CREDENTIALS);
                var client = withoutDefault.builder(server.base()).build()) {
            return thrown(withoutDefault.request(client).header("Authorization", authorization), true);
        }
    }

    static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

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
                new Kind("a 422 that sends the request body back", 422, false,
                        setup -> against(VALIDATION_ECHO, setup, Duration.ofSeconds(10), 1 << 20)),
                new Kind("a 200 that sends the request headers back, with a codec that does not fit", 200, false,
                        setup -> {
                            try (var server = new Support.RawServer(HEADER_ECHO);
                                    var client = setup.builder(server.base()).build()) {
                                return thrown(setup.request(client), false);
                            }
                        }),
                new Kind("a 404 problem that sends the path and query back", 404, false,
                        setup -> against(PROBLEM_ECHO, setup, Duration.ofSeconds(10), 1 << 20)),
                new Kind("a 401 that names the bearer token on its own", 401, false,
                        setup -> against(NAMES_CREDENTIALS, setup, Duration.ofSeconds(10), 1 << 20)),
                new Kind("a 401 that names the token after a lower-case bearer", 401, false,
                        setup -> namedCredentials(setup, "bearer " + setup.token())),
                new Kind("a 401 that names the decoded user:password from Basic", 401, false,
                        setup -> namedCredentials(setup, "Basic " + base64("anna:" + setup.token()))),
                new Kind("a server that echoes the request", -1, false,
                        setup -> against(ECHOES, setup, Duration.ofSeconds(10), 1024)),
                new Kind("a server that echoes the request body", -1, false,
                        setup -> against(ECHOES_BODY, setup, Duration.ofSeconds(10), 1024)),
                new Kind("a server that sends part of the request line and closes", -1, false,
                        setup -> against(ECHOES_PART_OF_LINE, setup, Duration.ofSeconds(10), 1024)),
                new Kind("a server that sends part of a header value back in a bad header", -1, false,
                        setup -> against(ECHOES_PART_OF_KEY, setup, Duration.ofSeconds(10), 1024)),
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
