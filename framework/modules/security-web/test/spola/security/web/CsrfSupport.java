package spola.security.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import spola.http.HttpServer;

/**
 * Helpers for the tests of the CSRF protection (11c): requests over a raw socket and through a
 * "browser" ({@link HttpClient} with its own {@link CookieManager}, which sends
 * {@code X-XSRF-TOKEN} from its cookie as Angular does), and the record that
 * {@link CsrfLeakTests} reads at the end: every CSRF token and session id seen in a
 * {@code Set-Cookie} or made up by a test, and every exception message and {@code toString()}
 * the tests looked at.
 *
 * <p>Every reply also goes to {@link Support#REPLIES}, so {@link LeakTests} and
 * {@link CsrfLeakTests} check it, as a copy in which the values of the response's own
 * {@code XSRF-TOKEN} and {@code SESSION} (or {@code __Host-SESSION}) cookies are replaced by
 * {@link #OWN_VALUE}. Those values are random Base64url: about one in 200 runs, one of the many
 * hundred tokens would contain a four-character marker that {@code LeakTests} looks for
 * ({@code zPwd}, {@code zUsr}) and fail it by chance. The test that gets a reply sees it unchanged and checks the values' form.
 */
final class CsrfSupport {

    private CsrfSupport() {
    }

    /** The form of a token (and of a session id). */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** What the recorded copy of a reply has in place of the value of its own cookie. */
    static final String OWN_VALUE = "<own value>";

    /** A {@code Set-Cookie} line (or value) for the own cookies with a value: the prefix, then the value. */
    private static final Pattern OWN_LINE =
            Pattern.compile("(?im)^(Set-Cookie:\\s*(?:XSRF-TOKEN|(?:__Host-)?SESSION)=)([^;\\r\\n]+)");
    private static final Pattern OWN_FIELD = Pattern.compile("^((?:XSRF-TOKEN|(?:__Host-)?SESSION)=)([^;]+)");

    /** The exact {@code Set-Cookie} of {@code Csrf.cookie()}, with the token for {@code %s}. */
    static final String XSRF_COOKIE = "XSRF-TOKEN=%s; Path=/; SameSite=Lax";

    // ---- the record for the leak test ----------------------------------------------------------

    /** Every CSRF token seen in a {@code Set-Cookie}, and every well-formed token a test made up. */
    static final Set<String> TOKENS = ConcurrentHashMap.newKeySet();
    /** Every session id seen in a {@code Set-Cookie} of these tests. */
    static final Set<String> IDS = ConcurrentHashMap.newKeySet();
    /** Every exception message and {@code toString()} the tests looked at. */
    static final Queue<String> TEXTS = new ConcurrentLinkedQueue<>();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A well-formed token that the server never issued (a client or an attacker made it up), recorded. */
    static String madeUp() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        TOKENS.add(token);
        return token;
    }

    /** {@code String.valueOf(value)}, recorded. */
    static String text(Object value) {
        var text = String.valueOf(value);
        TEXTS.add(text);
        return text;
    }

    /** {@code exception}, with its message and its causes' messages recorded. */
    static <T extends Throwable> T thrown(T exception) {
        for (Throwable t = exception; t != null; t = t.getCause()) {
            TEXTS.add(String.valueOf(t.getMessage()));
            TEXTS.add(String.valueOf(t));
        }
        return exception;
    }

    // ---- Set-Cookie ----------------------------------------------------------------------------

    /** The value of a {@code Set-Cookie}: the text between the first {@code =} and the first {@code ;}. */
    static String valueOf(String setCookie) {
        int semicolon = setCookie.indexOf(';');
        return setCookie.substring(setCookie.indexOf('=') + 1, semicolon < 0 ? setCookie.length() : semicolon);
    }

    /** The {@code Set-Cookie} values of a reply for {@code XSRF-TOKEN}, in order. */
    static List<String> xsrfCookies(Support.Reply reply) {
        return reply.all("Set-Cookie").stream().filter(value -> value.startsWith("XSRF-TOKEN=")).toList();
    }

    /** The {@code Set-Cookie} values of a reply for {@code SESSION}, in order. */
    static List<String> sessionCookies(Support.Reply reply) {
        return reply.all("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")).toList();
    }

    /**
     * The new token of a reply that must set it: exactly one {@code Set-Cookie} for
     * {@code XSRF-TOKEN}, exactly {@link #XSRF_COOKIE} with a well-formed token.
     */
    static String issued(Support.Reply reply) {
        var cookies = xsrfCookies(reply);
        if (cookies.size() != 1) {
            throw new AssertionError("expected exactly one Set-Cookie for XSRF-TOKEN, got " + cookies.size() + " in a "
                    + reply.status());
        }
        var token = valueOf(cookies.getFirst());
        if (!TOKEN.matcher(token).matches() || !cookies.getFirst().equals(XSRF_COOKIE.formatted(token))) {
            throw new AssertionError("not the XSRF-TOKEN cookie of Csrf.cookie() in a " + reply.status());
        }
        return token;
    }

    /**
     * Records a reply: the tokens and session ids it sets, and a copy with their values replaced
     * by {@link #OWN_VALUE} in {@link Support#REPLIES}.
     */
    static void keep(Support.Reply reply) {
        record(reply);
        var head = OWN_LINE.matcher(reply.head()).replaceAll("$1" + OWN_VALUE);
        var headers = new ArrayList<String[]>();
        for (var field : reply.headers()) {
            headers.add(field[0].equalsIgnoreCase("Set-Cookie")
                    ? new String[] {field[0], OWN_FIELD.matcher(field[1]).replaceFirst("$1" + OWN_VALUE)} : field);
        }
        Support.recordReply(new Support.Reply(reply.status(), head, List.copyOf(headers), reply.body()));
    }

    /** Records the tokens and session ids a reply sets. */
    static void record(Support.Reply reply) {
        for (var setCookie : reply.all("Set-Cookie")) {
            var value = valueOf(setCookie);
            if (value.isEmpty()) {
                continue;
            }
            if (setCookie.startsWith("XSRF-TOKEN=")) {
                TOKENS.add(value);
            } else if (setCookie.startsWith("SESSION=") || setCookie.startsWith("__Host-SESSION=")) {
                IDS.add(value);
            }
        }
    }

    // ---- raw socket ----------------------------------------------------------------------------

    /**
     * One request on a new connection with {@code Connection: close}, {@code headers} (each
     * {@code Name: value}) and an optional body ({@code Content-Length} for every method but
     * {@code GET}, {@code HEAD}, {@code OPTIONS} and {@code TRACE}); the reply is recorded.
     */
    static Support.Reply raw(HttpServer server, String method, String target, String body, String... headers)
            throws IOException {
        var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        var head = new StringBuilder(method).append(' ').append(target)
                .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for (var header : headers) {
            head.append(header).append("\r\n");
        }
        if (body != null || !List.of("GET", "HEAD", "OPTIONS", "TRACE").contains(method)) {
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        head.append("\r\n");
        var request = new ByteArrayOutputStream();
        request.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        request.writeBytes(bytes);
        var reply = exchange(server.port(), request.toByteArray());
        keep(reply);
        return reply;
    }

    /** Sends {@code bytes} and reads the one response until the server closes (as {@link Support#raw}, unrecorded). */
    private static Support.Reply exchange(int port, byte[] bytes) throws IOException {
        var all = new ByteArrayOutputStream();
        try (var socket = new Socket(Support.LOCALHOST, port)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            var buffer = new byte[8_192];
            try {
                int n;
                while ((n = socket.getInputStream().read(buffer)) >= 0) {
                    all.write(buffer, 0, n);
                }
            } catch (SocketException e) {
                // reset after the response: closed as well
            }
        }
        var text = new String(all.toByteArray(), StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            throw new IOException("no complete response");
        }
        var head = text.substring(0, end);
        var lines = head.split("\r\n");
        var headers = new ArrayList<String[]>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.add(new String[] {lines[i].substring(0, colon), lines[i].substring(colon + 1).strip()});
        }
        var body = new String(all.toByteArray(), end + 4, all.size() - end - 4, StandardCharsets.UTF_8);
        return new Support.Reply(Integer.parseInt(lines[0].substring(9, 12)), head, List.copyOf(headers), body);
    }

    /** {@code Cookie: XSRF-TOKEN=<value>}. */
    static String cookie(String value) {
        return "Cookie: XSRF-TOKEN=" + value;
    }

    /** {@code X-XSRF-TOKEN: <value>}. */
    static String header(String value) {
        return "X-XSRF-TOKEN: " + value;
    }

    // ---- a browser ----------------------------------------------------------------------------

    /**
     * An HTTP/1.1 client with its own {@link CookieManager}, against one server. Like Angular's
     * {@code HttpClient} it reads {@code XSRF-TOKEN} from its cookies and sends it as
     * {@code X-XSRF-TOKEN} on every request that is not a {@code GET} or {@code HEAD} (unless the
     * test says otherwise). Every response is recorded.
     */
    static final class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).cookieHandler(cookies).build();
        private final HttpServer server;

        Browser(HttpServer server) {
            this.server = server;
        }

        /** The value of the {@code XSRF-TOKEN} cookie, as Angular reads it from {@code document.cookie}. */
        Optional<String> xsrf() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals(Csrf.COOKIE_NAME))
                    .map(HttpCookie::getValue).findFirst();
        }

        /** The value of the {@code SESSION} cookie, if the store has one. */
        Optional<String> session() {
            return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION"))
                    .map(HttpCookie::getValue).findFirst();
        }

        HttpResponse<String> get(String path) throws IOException, InterruptedException {
            return call(HttpRequest.newBuilder(uri(path)).GET());
        }

        /** {@code POST} with a JSON body (or none) and the header from the cookie, as Angular does. */
        HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
            return post(path, json, xsrf().orElse(null));
        }

        /** {@code POST} without {@code X-XSRF-TOKEN}, as a forged form or a {@code fetch} from another origin. */
        HttpResponse<String> postWithoutHeader(String path, String json) throws IOException, InterruptedException {
            return post(path, json, null);
        }

        /** {@code POST} with {@code X-XSRF-TOKEN: token}, or without it when {@code token} is {@code null}. */
        HttpResponse<String> post(String path, String json, String token) throws IOException, InterruptedException {
            var builder = HttpRequest.newBuilder(uri(path));
            if (json == null) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
            }
            if (token != null) {
                builder.header(Csrf.HEADER_NAME, token);
            }
            return call(builder);
        }

        /** {@code POST /api/auth/login} with the header from the cookie. */
        HttpResponse<String> login(String username, String password) throws IOException, InterruptedException {
            return post("/api/auth/login", LoginSupport.loginJson(username, password));
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.port() + path);
        }

        private HttpResponse<String> call(HttpRequest.Builder request) throws IOException, InterruptedException {
            var response = client.send(request.timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            var headers = new ArrayList<String[]>();
            var head = new StringBuilder("HTTP/1.1 ").append(response.statusCode());
            response.headers().map().forEach((name, values) -> {
                for (var value : values) {
                    headers.add(new String[] {name, value});
                    head.append("\r\n").append(name).append(": ").append(value);
                }
            });
            keep(new Support.Reply(response.statusCode(), head.toString(), List.copyOf(headers), response.body()));
            return response;
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** The {@code Set-Cookie} values of a response for {@code XSRF-TOKEN}. */
    static List<String> xsrfCookies(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("XSRF-TOKEN="))
                .toList();
    }

    /** The {@code Set-Cookie} values of a response for {@code SESSION}. */
    static List<String> sessionCookies(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
                .toList();
    }
}
