package spola.security.web;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import spola.http.HttpServer;
import spola.security.Authenticator;
import spola.security.InMemoryUserStore;
import spola.security.User;

/**
 * Helpers for the tests of the login with a session (11b): users, a clock, requests over a raw
 * socket and through {@link HttpClient}, and the record that {@link LoginLeakTests} reads at the
 * end: every session id seen in a {@code Set-Cookie} or made up by a test, and every exception
 * message and {@code toString()} the tests looked at. Every reply also goes to
 * {@link Support#REPLIES}, so {@link LeakTests} checks it as well.
 */
final class LoginSupport {

    private LoginSupport() {
    }

    // ---- users -------------------------------------------------------------------------------

    // No user marker in the names: the handlers answer with the name. Every password holds
    // Support.PASSWORD_MARKER, which no response, log line or message may hold.
    static final String ANNA = "anna";
    static final String ANNA_PASSWORD = "zPwdAnna-correct horse";
    /** The administrator: USER and ADMIN. */
    static final String BERTIL = "bertil";
    static final String BERTIL_PASSWORD = "zPwdBertil-battery staple";
    static final String CECILIA = "cecilia";
    static final String CECILIA_PASSWORD = "zPwdCecilia-hunter2";
    static final String DAVID = "david";
    static final String DAVID_PASSWORD = "zPwdDavid-tr0ub4dor";
    static final String ERIK = "erik";
    static final String ERIK_PASSWORD = "zPwdErik-open sesame";

    /** The five users, in order. */
    static final List<String> NAMES = List.of(ANNA, BERTIL, CECILIA, DAVID, ERIK);
    static final Map<String, String> PASSWORDS = Map.of(ANNA, ANNA_PASSWORD, BERTIL, BERTIL_PASSWORD, CECILIA,
            CECILIA_PASSWORD, DAVID, DAVID_PASSWORD, ERIK, ERIK_PASSWORD);

    /** The users: bertil has USER and ADMIN, the others USER. */
    static final InMemoryUserStore USERS = users();

    private static InMemoryUserStore users() {
        var users = new InMemoryUserStore();
        for (var name : NAMES) {
            var roles = name.equals(BERTIL) ? Set.of("USER", "ADMIN") : Set.of("USER");
            users.add(new User(name, Support.HASHER.hash(PASSWORDS.get(name)), roles, true));
        }
        return users;
    }

    /** An authenticator over {@link #USERS} with {@code Pbkdf2PasswordHasher.of(10_000, 16, 32)}. */
    static Authenticator authenticator() {
        return Authenticator.of(USERS, Support.HASHER);
    }

    /** The JSON body of a login, written with the example's codec. */
    static String loginJson(String username, String password) {
        return LoginExample.LOGIN.write(new LoginExample.Login(username, password));
    }

    // ---- the record for the leak test ----------------------------------------------------------

    /** The form of a session id. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** Every session id seen in a {@code Set-Cookie}, and every well-formed id a test made up. */
    static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    /** Every exception message and {@code toString()} the tests looked at. */
    static final Queue<String> TEXTS = new ConcurrentLinkedQueue<>();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A well-formed id that the server never issued (a client made it up), recorded. */
    static String madeUp() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        var id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        IDS.add(id);
        return id;
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

    // ---- Set-Cookie ----------------------------------------------------------------------------

    /** The {@code Set-Cookie} values of a reply for the session cookie, in order. */
    static List<String> sessionCookies(Support.Reply reply) {
        return reply.all("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")).toList();
    }

    /** The value of a {@code Set-Cookie}: the text between the first {@code =} and the first {@code ;}. */
    static String idOf(String setCookie) {
        int semicolon = setCookie.indexOf(';');
        return setCookie.substring(setCookie.indexOf('=') + 1, semicolon < 0 ? setCookie.length() : semicolon);
    }

    /**
     * The new id of a reply that must set the session cookie: exactly one {@code Set-Cookie} in
     * all, for {@code SESSION}, with a well-formed id.
     */
    static String issued(Support.Reply reply) {
        var all = reply.all("Set-Cookie");
        if (all.size() != 1 || !all.getFirst().startsWith("SESSION=")) {
            throw new AssertionError("expected exactly one Set-Cookie for SESSION, got " + all.size() + " in a "
                    + reply.status());
        }
        var id = idOf(all.getFirst());
        if (!ID.matcher(id).matches()) {
            throw new AssertionError("not a session id in Set-Cookie");
        }
        return id;
    }

    /** Records the ids a reply sets. */
    static void record(Support.Reply reply) {
        for (var setCookie : sessionCookies(reply)) {
            var id = idOf(setCookie);
            if (!id.isEmpty()) {
                IDS.add(id);
            }
        }
    }

    // ---- raw socket ----------------------------------------------------------------------------

    /**
     * One request on a new connection with {@code Connection: close}, {@code headers} (each
     * {@code Name: value}) and an optional body; the reply is recorded.
     */
    static Support.Reply raw(HttpServer server, String method, String target, String body, String... headers)
            throws IOException {
        var bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        var head = new StringBuilder(method).append(' ').append(target)
                .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for (var header : headers) {
            head.append(header).append("\r\n");
        }
        if (body != null || method.equals("POST")) {
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        head.append("\r\n");
        var request = new java.io.ByteArrayOutputStream();
        request.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        request.writeBytes(bytes);
        var reply = Support.raw(server.port(), request.toByteArray());
        record(reply);
        return reply;
    }

    /** {@code Cookie: SESSION=<id>}. */
    static String cookie(String id) {
        return "Cookie: SESSION=" + id;
    }

    /** A request with the session cookie {@code id}, or none when {@code id} is {@code null}. */
    static Support.Reply send(HttpServer server, String method, String target, String id) throws IOException {
        return id == null ? raw(server, method, target, null) : raw(server, method, target, null, cookie(id));
    }

    /** {@code POST /api/auth/login} with a JSON body, and the session cookie {@code id} unless it is {@code null}. */
    static Support.Reply login(HttpServer server, String username, String password, String id) throws IOException {
        var json = loginJson(username, password);
        return id == null ? raw(server, "POST", "/api/auth/login", json, "Content-Type: application/json")
                : raw(server, "POST", "/api/auth/login", json, "Content-Type: application/json", cookie(id));
    }

    // ---- HttpClient ----------------------------------------------------------------------------

    /** An HTTP/1.1 client with its own {@link CookieManager}. */
    static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    /** {@code GET path} through {@code client}; the response is recorded. */
    static HttpResponse<String> get(HttpClient client, HttpServer server, String path)
            throws IOException, InterruptedException {
        return call(client, HttpRequest.newBuilder(uri(server, path)).GET());
    }

    /** {@code POST path} with a JSON body (or none) through {@code client}; the response is recorded. */
    static HttpResponse<String> post(HttpClient client, HttpServer server, String path, String json)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(uri(server, path));
        if (json == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        }
        return call(client, builder);
    }

    /** {@code POST /api/auth/login} through {@code client}. */
    static HttpResponse<String> login(HttpClient client, HttpServer server, String username, String password)
            throws IOException, InterruptedException {
        return post(client, server, "/api/auth/login", loginJson(username, password));
    }

    /** The {@code Set-Cookie} values of a response for the session cookie. */
    static List<String> sessionCookies(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
                .toList();
    }

    private static URI uri(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static HttpResponse<String> call(HttpClient client, HttpRequest.Builder request)
            throws IOException, InterruptedException {
        var response = client.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        var headers = new ArrayList<String[]>();
        var head = new StringBuilder("HTTP/1.1 ").append(response.statusCode());
        response.headers().map().forEach((name, values) -> {
            for (var value : values) {
                headers.add(new String[] {name, value});
                head.append("\r\n").append(name).append(": ").append(value);
            }
        });
        var reply = new Support.Reply(response.statusCode(), head.toString(), List.copyOf(headers), response.body());
        Support.recordReply(reply);
        record(reply);
        return response;
    }
}
