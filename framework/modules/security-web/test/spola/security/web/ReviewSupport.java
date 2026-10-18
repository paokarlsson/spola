package spola.security.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import spola.http.HttpServer;
import spola.http.Response;
import spola.web.AttributeKey;
import spola.web.Router;
import spola.web.Sessions;

/**
 * Helpers for the tests of uppdrag 15a (the fixes for G1–G5 of the security review): the
 * example's rules with a chosen limit per user, a router with a few extra paths, requests over a
 * raw socket, and the record that {@link ReviewLeakTests} reads at the end: every reply, every
 * session id and CSRF token seen in a {@code Set-Cookie} or made up, and every exception message
 * and {@code toString()} the tests looked at.
 *
 * <p>Every reply also goes to {@link Support#REPLIES}, so {@link LeakTests} checks it, as a copy
 * whose own {@code SESSION} and {@code XSRF-TOKEN} values are replaced by
 * {@link CsrfSupport#OWN_VALUE} (the reason is in {@link CsrfSupport}: hundreds of random ids
 * would sometimes hold a four-character marker of {@code LeakTests}).
 */
final class ReviewSupport {

    private ReviewSupport() {
    }

    /** A value the open path {@code /note} stores in the session. */
    static final AttributeKey<String> NOTE = AttributeKey.of("note", String.class);

    // ---- the record for the leak test ----------------------------------------------------------

    /** Every session id seen in a {@code Set-Cookie} of these tests, and every id they made up. */
    static final Set<String> IDS = ConcurrentHashMap.newKeySet();
    /** Every CSRF token seen in a {@code Set-Cookie} of these tests. */
    static final Set<String> TOKENS = ConcurrentHashMap.newKeySet();
    /** Every reply of these tests, as received. */
    static final Queue<Support.Reply> REPLIES = new ConcurrentLinkedQueue<>();
    /** Every exception message and {@code toString()} these tests looked at. */
    static final Queue<String> TEXTS = new ConcurrentLinkedQueue<>();

    private static final Pattern OWN_LINE = Pattern.compile("(?im)^(Set-Cookie:\\s*(?:XSRF-TOKEN|SESSION)=)([^;\\r\\n]+)");
    private static final Pattern OWN_FIELD = Pattern.compile("^((?:XSRF-TOKEN|SESSION)=)([^;]+)");

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

    /** A made-up, well-formed session id, recorded. */
    static String madeUp() {
        var id = LoginSupport.madeUp();
        IDS.add(id);
        return id;
    }

    /** Records a reply: its ids and tokens, the reply itself, and a masked copy in {@link Support#REPLIES}. */
    static void keep(Support.Reply reply) {
        for (var setCookie : reply.all("Set-Cookie")) {
            var value = CsrfSupport.valueOf(setCookie);
            if (value.isEmpty()) {
                continue;
            }
            if (setCookie.startsWith("SESSION=")) {
                IDS.add(value);
            } else if (setCookie.startsWith("XSRF-TOKEN=")) {
                TOKENS.add(value);
            }
        }
        REPLIES.add(reply);
        var head = OWN_LINE.matcher(reply.head()).replaceAll("$1" + CsrfSupport.OWN_VALUE);
        var headers = new ArrayList<String[]>();
        for (var field : reply.headers()) {
            headers.add(field[0].equalsIgnoreCase("Set-Cookie")
                    ? new String[] {field[0], OWN_FIELD.matcher(field[1]).replaceFirst("$1" + CsrfSupport.OWN_VALUE)}
                    : field);
        }
        Support.recordReply(new Support.Reply(reply.status(), head, List.copyOf(headers), reply.body()));
    }

    // ---- the security ----------------------------------------------------------------------------

    /**
     * The rules of {@link LoginExample#security} with at most {@code maximum} sessions per user.
     * The concurrency tests of 11b–13b log in each of five users 40 times at once and need every
     * session to stay; since 15a the default of 10 ends the oldest.
     */
    static WebSecurity loginSecurity(Sessions sessions, int maximum) {
        return WebSecurity.builder()
                .sessions(sessions)
                .maximumSessions(maximum)
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /** The rules of {@link CsrfExample#security} (with {@code Csrf.cookie()}) with at most {@code maximum} sessions per user. */
    static WebSecurity csrfSecurity(Sessions sessions, int maximum) {
        return WebSecurity.builder()
                .sessions(sessions)
                .maximumSessions(maximum)
                .csrf(Csrf.cookie())
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes of {@link LoginExample#router} behind {@code security}, and two more:
     * {@code POST /note} stores the body in the session (a logged-in user's value) and
     * {@code GET /note} shows it ({@code none} without one); both need a login.
     */
    static Router router(WebSecurity security) {
        var routes = Support.router()
                .post("/note", request -> {
                    Sessions.find(request).orElseThrow().put(NOTE, new String(request.bytes(), StandardCharsets.UTF_8));
                    return Response.text(200, "stored");
                })
                .get("/note", request -> Response.text(200, Sessions.find(request).flatMap(s -> s.get(NOTE))
                        .orElse("none")));
        return LoginExample.router(routes, security, LoginSupport.authenticator());
    }

    // ---- raw socket ----------------------------------------------------------------------------

    /**
     * One request on a new connection with {@code Connection: close}, {@code headers} (each
     * {@code Name: value}) and an optional body ({@code Content-Length} for {@code POST}); the
     * reply is recorded.
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
        var request = new ByteArrayOutputStream();
        request.writeBytes(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        request.writeBytes(bytes);
        var reply = exchange(server.port(), request.toByteArray());
        keep(reply);
        return reply;
    }

    /** {@code GET} or {@code POST} with the session cookie {@code id} (none when it is {@code null}). */
    static Support.Reply send(HttpServer server, String method, String target, String id) throws IOException {
        return id == null ? raw(server, method, target, null) : raw(server, method, target, null, LoginSupport.cookie(id));
    }

    /** {@code POST /api/auth/login} as {@code username}, with the session cookie {@code id} (none when {@code null}). */
    static Support.Reply login(HttpServer server, String username, String id) throws IOException {
        var json = LoginSupport.loginJson(username, LoginSupport.PASSWORDS.get(username));
        return id == null ? raw(server, "POST", "/api/auth/login", json, "Content-Type: application/json")
                : raw(server, "POST", "/api/auth/login", json, "Content-Type: application/json", LoginSupport.cookie(id));
    }

    /** The new session id of a login: exactly one {@code Set-Cookie}, for {@code SESSION}. */
    static String issued(Support.Reply reply) {
        return LoginSupport.issued(reply);
    }

    /** {@code GET /api/auth/me} with {@code id}: the name it answers, or {@code 401}. */
    static String me(HttpServer server, String id) throws IOException {
        var reply = send(server, "GET", "/api/auth/me", id);
        return reply.status() == 200 ? reply.body() : String.valueOf(reply.status());
    }

    /** The body {@code /api/auth/me} gives {@code name}. */
    static String named(String name) {
        return "{\"username\":\"" + name + "\"}";
    }

    /** Sends {@code bytes} and reads the one response until the server closes (unrecorded). */
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
}
