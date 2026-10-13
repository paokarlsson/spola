package spola.web;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import spola.http.HttpServer;
import spola.http.Response;

/**
 * Helpers for the session tests, and the record the leak test (criterion 13) reads at the end:
 * every session id seen in a {@code Set-Cookie}, every body, every {@code toString()} and every
 * exception message the tests looked at, and every log line of the routers ({@link #LOG}).
 */
final class SessionSupport {

    private SessionSupport() {
    }

    /** The form of an id. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Every router of the session tests logs here. */
    static final Support.CapturedLog LOG = new Support.CapturedLog();

    /** Every session id seen in a {@code Set-Cookie}. */
    static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    /** Every body, {@code toString()} and exception message the tests saw. */
    static final Queue<String> TEXTS = new ConcurrentLinkedQueue<>();

    static final AttributeKey<Integer> COUNT = AttributeKey.of("count", Integer.class);

    /** A router builder that logs to {@link #LOG}. */
    static RouterBuilder router() {
        return Router.builder().log(LOG);
    }

    /**
     * The routes most tests use, behind {@code sessions.filter()}: {@code /plain} never touches the
     * session, {@code /find} only calls {@code find}, {@code /count} counts with
     * {@code getOrCreate}, {@code /renew} and {@code /invalidate} do what they say.
     */
    static Router app(Sessions sessions) {
        return routes(router().filter(sessions.filter())).build();
    }

    /** The routes of {@link #app} on {@code builder}, without a filter. */
    static RouterBuilder routes(RouterBuilder builder) {
        return builder
                .get("/plain", r -> Response.text(200, "plain"))
                .get("/find", r -> Response.text(200, Sessions.find(r).map(s -> "found:" + s.get(COUNT).orElse(0))
                        .orElse("none")))
                .post("/count", r -> {
                    var session = Sessions.getOrCreate(r);
                    int n = session.get(COUNT).orElse(0) + 1;
                    session.put(COUNT, n);
                    return Response.text(200, String.valueOf(n));
                })
                .post("/renew", r -> Response.text(200, "renewed:" + Sessions.renew(r).get(COUNT).orElse(0)))
                .post("/invalidate", r -> {
                    Sessions.invalidate(r);
                    return Response.text(200, "invalidated");
                });
    }

    /** A clock the tests move by hand. */
    static final class TestClock implements InstantSource {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-13T12:00:00Z"));

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration duration) {
            now.updateAndGet(instant -> instant.plus(duration));
        }
    }

    // ---- raw socket ---------------------------------------------------------------------------

    /** One request over a raw socket ({@code Content-Length: 0} for {@code POST}); the reply is recorded. */
    static Support.Reply send(HttpServer server, String method, String target, String... headers) throws IOException {
        var lines = new ArrayList<>(List.of(headers));
        if (method.equals("POST")) {
            lines.add("Content-Length: 0");
        }
        var reply = Support.send(server.port(), method, target, lines.toArray(String[]::new));
        record(reply);
        return reply;
    }

    /** {@code Cookie: SESSION=<value>}. */
    static String cookie(String value) {
        return "Cookie: SESSION=" + value;
    }

    /** Every {@code Set-Cookie} value of a reply, in order. */
    static List<String> setCookies(Support.Reply reply) {
        return values(reply, "Set-Cookie");
    }

    /** Every value of a header of a reply, in order. */
    static List<String> values(Support.Reply reply, String name) {
        var values = new ArrayList<String>();
        for (var header : reply.headers()) {
            if (header[0].equalsIgnoreCase(name)) {
                values.add(header[1]);
            }
        }
        return values;
    }

    /** The id of the reply's one {@code Set-Cookie}; fails if there is not exactly one or it has no id. */
    static String issued(Support.Reply reply) {
        var cookies = setCookies(reply);
        if (cookies.size() != 1) {
            throw new AssertionError("expected one Set-Cookie, got " + cookies + " in " + reply);
        }
        var id = idOf(cookies.getFirst());
        if (!ID.matcher(id).matches()) {
            throw new AssertionError("not an id: " + cookies.getFirst());
        }
        return id;
    }

    /** The value of a {@code Set-Cookie}: the text between the first {@code =} and the first {@code ;}. */
    static String idOf(String setCookie) {
        int semicolon = setCookie.indexOf(';');
        return setCookie.substring(setCookie.indexOf('=') + 1, semicolon < 0 ? setCookie.length() : semicolon);
    }

    static void record(Support.Reply reply) {
        setCookies(reply).forEach(SessionSupport::recordSetCookie);
        TEXTS.add(reply.body());
    }

    static void recordSetCookie(String setCookie) {
        var id = idOf(setCookie);
        if (!id.isEmpty()) {
            IDS.add(id);
        }
    }

    // ---- HttpClient ---------------------------------------------------------------------------

    /** An HTTP/1.1 client with its own {@link CookieManager}. */
    static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    /** One request without a body through {@code client}; the {@code Set-Cookie} values and the body are recorded. */
    static HttpResponse<String> call(HttpClient client, HttpServer server, String method, String target)
            throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + target))
                .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        response.headers().allValues("Set-Cookie").forEach(SessionSupport::recordSetCookie);
        TEXTS.add(response.body());
        return response;
    }

    // ---- texts --------------------------------------------------------------------------------

    /** {@code String.valueOf(value)}, recorded. */
    static String text(Object value) {
        var text = String.valueOf(value);
        TEXTS.add(text);
        return text;
    }

    /** {@code exception}, with its message (and its causes' messages) recorded. */
    static <T extends Throwable> T thrown(T exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            TEXTS.add(String.valueOf(t.getMessage()));
            TEXTS.add(String.valueOf(t));
        }
        return exception;
    }
}
