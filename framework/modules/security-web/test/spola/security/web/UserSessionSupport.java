package spola.security.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import spola.http.HttpServer;
import spola.http.Response;
import spola.web.AttributeKey;
import spola.web.Router;
import spola.web.Sessions;

/**
 * Helpers for the tests of the sessions per user, the new session for another user and the
 * {@code Cache-Control} rules (15a): the example's rules with a chosen limit per user, a router
 * with a few extra paths, requests over a raw socket, and the record that
 * {@link UserSessionLeakTests} reads at the end: every reply, every session id and CSRF token
 * seen in a {@code Set-Cookie} or made up, and every exception message and {@code toString()}
 * the tests looked at.
 *
 * <p>The requests go through {@link CsrfSupport#raw}, which also puts every reply in
 * {@link Support#REPLIES} for {@link LeakTests}, with the values of the reply's own
 * {@code SESSION} and {@code XSRF-TOKEN} cookies masked (the reason is in {@link CsrfSupport}).
 */
final class UserSessionSupport {

    private UserSessionSupport() {
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

    /** Records a reply for {@link UserSessionLeakTests}: the reply, and the ids and tokens it sets. */
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
    }

    // ---- the security ----------------------------------------------------------------------------

    /**
     * The rules of {@link LoginExample#security} with at most {@code maximum} sessions per user.
     * The concurrency tests of 11b–13b log in each of five users 40 times at once and need every
     * session to stay; with the default of 10 per user the oldest end.
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
     * {@code Name: value}) and an optional body, as {@link CsrfSupport#raw} sends it; the reply
     * is recorded.
     */
    static Support.Reply raw(HttpServer server, String method, String target, String body, String... headers)
            throws IOException {
        var reply = CsrfSupport.raw(server, method, target, body, headers);
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
}
