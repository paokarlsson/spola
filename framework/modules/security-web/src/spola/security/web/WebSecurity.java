package spola.security.web;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import spola.http.Headers;
import spola.http.Response;
import spola.security.Identity;
import spola.web.AttributeKey;
import spola.web.Filter;
import spola.web.Problem;
import spola.web.Sessions;
import spola.web.WebException;
import spola.web.WebRequest;

/**
 * The security of a router: authentication, access rules and the 401 and 403 responses, the
 * counterpart of Spring Security's {@code SecurityFilterChain} bean with
 * {@code authorizeHttpRequests}, {@code httpBasic()} and a login kept in the HTTP session
 * ({@code HttpSessionSecurityContextRepository}). Immutable and thread-safe; build it with
 * {@link #builder()}:
 *
 * <pre>{@code
 * WebSecurity security = WebSecurity.builder()
 *         .authentication(HttpBasic.of(authenticator, "app"))
 *         .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
 *         .rule(RequestMatcher.method("POST", RequestMatcher.under("/admin")), AccessRule.hasRole("ADMIN"))
 *         .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
 *         .build();
 * Router router = Router.builder()
 *         .filter(security.filter())                     // first: the outermost filter
 *         .group("/admin", g -> g.filter(security.require(AccessRule.hasRole("ADMIN")))
 *                 .get("/users", listUsers))
 *         .build();
 * }</pre>
 *
 * <h2>The filter</h2>
 * {@link #filter()} must be registered on the router, <b>first</b>, so that it is the
 * outermost filter: then it runs for every request, also those the router answers with 400,
 * 404, 405 or an automatic {@code OPTIONS}, and no other filter sees a request it denies. For
 * each request it:
 * <ol>
 *   <li>with {@linkplain Builder#sessions sessions}, runs the session filter of those sessions
 *       around all the steps below, so that the request's session is found from its cookie (see
 *       "Login with a session");</li>
 *   <li>answers 400 if the request has more than one {@code Authorization} field (RFC 9110
 *       §11.6.2), before any mechanism runs;</li>
 *   <li>with sessions, takes the identity that {@link #login} put in the request's session, if
 *       the request has a live session with one. The session comes <b>before</b> the
 *       mechanisms: then no mechanism is tried, not even {@link HttpBasic}. A session cookie
 *       never rejects a request: no cookie, an unknown or expired one, or a session without an
 *       identity all go on to the mechanisms;</li>
 *   <li>otherwise tries the {@link RequestAuthentication} mechanisms in registration order; the
 *       first one that does not answer {@link AuthOutcome.None} decides.
 *       {@link AuthOutcome.Rejected} gives 401 at once, whatever the rules say (wrong
 *       credentials are never ignored, also on a {@link AccessRule#permitAll() permitAll}
 *       path). No mechanism, or only {@code None}, means anonymous;</li>
 *   <li>puts a {@link SecurityContext} with the identity on the request (see
 *       {@link #identity});</li>
 *   <li>finds the <b>first</b> rule whose {@link RequestMatcher} matches, in registration
 *       order, and asks its {@link AccessRule}. If <b>no rule matches, the request is
 *       denied</b> (fail-closed);</li>
 *   <li>passes an allowed request on with {@code next.proceed}, and answers a denied one with
 *       401 or 403. Neither the handler nor any filter inside this one runs for a denied
 *       request.</li>
 * </ol>
 * An exception from a mechanism, a matcher or a rule is not caught: the router maps it (500
 * without a mapper), so an error never lets a request through.
 *
 * <h2>401 and 403</h2>
 * A denied anonymous request, and a request whose credentials a mechanism rejected, gets
 * <b>401</b>: the problem response of {@code Problem.of(401)} (no detail, no instance) with one
 * {@code WWW-Authenticate} field per {@link HttpBasic} mechanism, in registration order
 * ({@code Basic realm="app", charset="UTF-8"}; none without such a mechanism, so a JSON API
 * that logs in with a session gets a plain 401 and the browser shows no login box). A denied
 * authenticated request gets <b>403</b>: {@code Problem.of(403)}, no detail, no
 * {@code WWW-Authenticate}. Both carry {@code Cache-Control: no-store}. The responses are
 * exactly the same whatever the reason (wrong password, unknown user, disabled account,
 * malformed Base64, a missing role), and they never hold the username, the password or the
 * field's value: a client learns that it must (re)authenticate, or that it may not, and
 * nothing that helps guessing accounts. Nothing is logged.
 *
 * <h2>Protecting a group</h2>
 * {@link #require(AccessRule)} is a filter for {@code RouteGroup.filter(...)}, the counterpart of
 * {@code @PreAuthorize("hasRole('ADMIN')")} on a controller: it reads the identity the central
 * filter put on the request and denies with the same 401 and 403.
 *
 * <h2>Login with a session</h2>
 * With {@link Builder#sessions(Sessions)} the login is kept in a {@code spola.web} session, the
 * counterpart of Spring Security's {@code HttpSessionSecurityContextRepository} with
 * {@code SessionCreationPolicy.IF_REQUIRED}. A JSON login is an ordinary handler that checks the
 * password with an {@code Authenticator} and calls {@link #login}:
 *
 * <pre>{@code
 * Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(Duration.ofMinutes(30)));
 * WebSecurity security = WebSecurity.builder()
 *         .sessions(sessions)                            // the identity is read from the session
 *         .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
 *         .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
 *         .build();
 * Router router = Router.builder()
 *         .filter(security.filter())                     // only this one: it runs sessions.filter() itself
 *         .post("/api/auth/login", request -> {
 *             var login = request.readJson(LOGIN);
 *             return switch (authenticator.authenticate(login.username(), login.password())) {
 *                 case AuthResult.Authenticated(Identity identity) -> {
 *                     WebSecurity.login(request, identity);   // a new session id with the identity
 *                     yield Responses.json(request, 200, JsonObject.of("username", JsonString.of(identity.name())));
 *                 }
 *                 case AuthResult.Failed failed -> Responses.json(request, 401, JsonObject.of());
 *             };
 *         })
 *         .post("/api/auth/logout", request -> {
 *             WebSecurity.logout(request);               // the session is removed, the cookie expires
 *             return Responses.noContent();
 *         })
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li><b>Register only {@code security.filter()}.</b> The security filter runs the session
 *       filter itself, outermost, so that the session is known before anything else and nobody
 *       can put the session filter in the wrong place. Registering {@code sessions.filter()}
 *       as well makes the session filter reach the request twice, which is a programming error:
 *       500 ({@link IllegalStateException}).</li>
 *   <li><b>The session before the mechanisms.</b> See step 3 above. A request without a session
 *       is authenticated by the mechanisms as without sessions, so {@link HttpBasic} next to
 *       sessions keeps working for clients that do not log in.</li>
 *   <li><b>Sessions only at login.</b> The security filter never creates a session: anonymous
 *       requests, 401, 403 and open paths get no {@code Set-Cookie}. {@link #login} creates or
 *       renews it.</li>
 *   <li><b>A snapshot.</b> The session holds the {@link Identity} that {@link #login} got.
 *       Changed roles, a disabled account or a removed user in the {@code UserStore} do not
 *       affect a session that is already logged in, until {@link #logout} or the session expires
 *       (as Spring by default).</li>
 *   <li><b>Expiry.</b> A session expires after its idle timeout
 *       ({@code SessionOptions.idleTimeout}); every request with its cookie counts as a use. An
 *       expired session makes the request anonymous, so a login on a {@code permitAll} path
 *       works with an expired cookie.</li>
 * </ul>
 */
public final class WebSecurity {

    /** The attribute with the request's {@link SecurityContext}; only this module has the key. */
    private static final AttributeKey<SecurityContext> CONTEXT = AttributeKey.of("spola.security.web.context",
            SecurityContext.class);
    /** The {@code WebSecurity} whose filter ran, for the 401 of {@link #requireIdentity} and for {@link #login}. */
    private static final AttributeKey<WebSecurity> OWNER = AttributeKey.of("spola.security.web.owner",
            WebSecurity.class);
    /** The session attribute with the identity that {@link #login} stored; only this module has the key. */
    private static final AttributeKey<Identity> LOGGED_IN = AttributeKey.of("spola.security.web.identity",
            Identity.class);

    private static final String MISSING_FILTER = "the request has no SecurityContext: register WebSecurity.filter()"
            + " on the router, first";

    /** One rule: a matcher and what it allows. */
    private record Rule(RequestMatcher matcher, AccessRule access) {
    }

    private final List<RequestAuthentication> mechanisms;
    private final List<Rule> rules;
    /** The sessions whose filter this security runs, or {@code null} without {@link Builder#sessions}. */
    private final Sessions sessions;
    private final Headers challenges;
    private final Response unauthorized;
    private final Response forbidden;
    private final Filter filter;

    private WebSecurity(List<RequestAuthentication> mechanisms, List<Rule> rules, Sessions sessions) {
        this.mechanisms = mechanisms;
        this.rules = rules;
        this.sessions = sessions;
        var challenges = new ArrayList<Headers.Field>();
        for (var mechanism : mechanisms) {
            if (mechanism instanceof HttpBasic basic) {
                challenges.add(new Headers.Field("WWW-Authenticate", basic.challenge()));
            }
        }
        this.challenges = Headers.of(challenges).with("Cache-Control", "no-store");
        var response = Problem.of(401).toResponse();
        for (var field : this.challenges.fields()) {
            response = response.withHeader(field.name(), field.value());
        }
        this.unauthorized = response;
        this.forbidden = Problem.of(403).toResponse().withHeader("Cache-Control", "no-store");
        this.filter = this::apply;
    }

    /**
     * A new builder.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The security filter. Register it on the router, <b>first</b>
     * ({@code Router.builder().filter(security.filter())}); see the class documentation.
     * Register it once: when a request reaches it a second time (it is also registered on a
     * group, or a second {@code WebSecurity}'s filter ran first), that is a programming error
     * and the filter throws {@link IllegalStateException} (500).
     *
     * <p>With {@link Builder#sessions(Sessions) sessions} this filter runs the session filter
     * itself, outermost; do <b>not</b> register {@code sessions.filter()} as well. If it is
     * registered too, the session filter reaches the request twice and the request gets 500
     * ({@link IllegalStateException}): registered before this filter, every request; registered
     * after it, every request that the rules let through.
     *
     * @return the filter, always the same instance
     */
    public Filter filter() {
        return filter;
    }

    /**
     * A filter for a group or a route that lets only requests through that {@code rule}
     * allows, the counterpart of {@code @PreAuthorize}:
     *
     * <pre>{@code
     * router.group("/admin", g -> g.filter(security.require(AccessRule.hasRole("ADMIN"))).get("/users", listUsers))
     * }</pre>
     *
     * It reads the identity that {@link #filter()} put on the request and answers a denied
     * request with this security's 401 (with the same {@code WWW-Authenticate}) or 403.
     * Without the central filter there is no identity, and the filter throws
     * {@link IllegalStateException} (500): a forgotten registration shows instead of letting
     * everyone through as anonymous.
     *
     * @param rule the rule
     * @return the filter
     * @throws NullPointerException if {@code rule} is {@code null}
     */
    public Filter require(AccessRule rule) {
        Objects.requireNonNull(rule, "rule");
        return (request, next) -> {
            var identity = context(request).identity();
            return rule.allows(identity, request) ? next.proceed(request) : denied(identity);
        };
    }

    /**
     * The identity of a request that passed {@link #filter()}, the counterpart of
     * {@code @AuthenticationPrincipal} and {@code SecurityContextHolder.getContext()}. It is
     * the identity the filter found; {@link #login} and {@link #logout} during the request do
     * not change it.
     *
     * @param request the request, as a handler or a filter inside the security filter gets it
     * @return the identity, or empty for an anonymous request
     * @throws IllegalStateException if the security filter has not run for the request (it is
     *                               not registered, or registered inside this code): a
     *                               programming error, 500
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static Optional<Identity> identity(WebRequest request) {
        return context(request).identity();
    }

    /**
     * The identity of a request that passed {@link #filter()}, for code that needs one.
     *
     * @param request the request, as a handler or a filter inside the security filter gets it
     * @return the identity
     * @throws WebException          401 if the request is anonymous: the same response as the
     *                               security filter's 401, {@code WWW-Authenticate} included
     * @throws IllegalStateException if the security filter has not run for the request: a
     *                               programming error, 500
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static Identity requireIdentity(WebRequest request) {
        var identity = context(request).identity();
        if (identity.isPresent()) {
            return identity.get();
        }
        var owner = request.attribute(OWNER).orElseThrow(() -> new IllegalStateException(MISSING_FILTER));
        throw new Unauthorized(owner);
    }

    /**
     * Logs {@code identity} in with the request's session, the counterpart of
     * {@code request.changeSessionId()} followed by saving the {@code SecurityContext} in the
     * session. Call it in a handler once the password has been checked (with
     * {@code Authenticator.authenticate}):
     * <ol>
     *   <li>the session gets a new id with {@link Sessions#renew}, the protection against
     *       session fixation: an id that existed before the login (one an attacker planted, say)
     *       stops working at once, and the session keeps its attributes. Without a live session a
     *       new one is created;</li>
     *   <li>the identity is stored in the session under a key only this module has;</li>
     *   <li>the response sets the session cookie with the new id (the session filter adds the
     *       {@code Set-Cookie}).</li>
     * </ol>
     * From the <b>next</b> request with the cookie on, the security filter takes the identity
     * from the session, before any mechanism. The session holds a <b>snapshot</b>: later changes
     * to the user (roles, a disabled account, a removed user) do not affect it until
     * {@link #logout} or expiry.
     *
     * <p><b>This request's own {@link SecurityContext} does not change:</b> {@link #identity}
     * and {@link #requireIdentity} keep answering what they answered when the security filter
     * ran (usually anonymous, on a {@code permitAll} login path), and the rules that already let
     * the request through are not asked again. Answer with the {@code identity} you passed in.
     *
     * <p>Logging in again replaces the identity (and renews the id again). Nothing is logged.
     *
     * @param request  the request, as a handler or a filter inside the security filter gets it
     * @param identity who logged in
     * @throws WebException          503 if the request has no live session and the session limit
     *                               is reached ({@code SessionOptions.maxSessions}); nothing is
     *                               stored and no cookie is set
     * @throws IllegalStateException if the request has not passed the filter of a
     *                               {@code WebSecurity} built with
     *                               {@link Builder#sessions(Sessions) sessions(...)} (no security
     *                               filter, or one without sessions): a programming error, 500;
     *                               also if it is called after the response was built
     * @throws NullPointerException  if an argument is {@code null}
     */
    public static void login(WebRequest request, Identity identity) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(identity, "identity");
        requireSessions(request, "login");
        Sessions.renew(request).put(LOGGED_IN, identity);
    }

    /**
     * Logs out, the counterpart of {@code session.invalidate()} (Spring Security's
     * {@code SecurityContextLogoutHandler}): the request's session is removed with all its
     * attributes ({@link Sessions#invalidate}), the old id stops working at once, and the
     * response removes the session cookie ({@code Max-Age=0}). Without a live session nothing
     * happens and the response gets no {@code Set-Cookie}. As with {@link #login}, this
     * request's own {@link SecurityContext} does not change.
     *
     * @param request the request, as a handler or a filter inside the security filter gets it
     * @throws IllegalStateException if the request has not passed the filter of a
     *                               {@code WebSecurity} built with
     *                               {@link Builder#sessions(Sessions) sessions(...)}: a
     *                               programming error, 500
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static void logout(WebRequest request) {
        Objects.requireNonNull(request, "request");
        requireSessions(request, "logout");
        Sessions.invalidate(request);
    }

    /** Fails unless the request passed the filter of a security with sessions. */
    private static void requireSessions(WebRequest request, String method) {
        var owner = request.attribute(OWNER).orElseThrow(() -> new IllegalStateException("WebSecurity." + method
                + " needs a request that passed the security filter: register WebSecurity.filter() on the router,"
                + " first, for a WebSecurity built with sessions(...)"));
        if (owner.sessions == null) {
            throw new IllegalStateException("WebSecurity." + method + " needs sessions: build the WebSecurity with"
                    + " WebSecurity.builder().sessions(sessions) and register only security.filter()");
        }
    }

    private static SecurityContext context(WebRequest request) {
        Objects.requireNonNull(request, "request");
        return request.attribute(CONTEXT).orElseThrow(() -> new IllegalStateException(MISSING_FILTER));
    }

    /** The central filter; see the class documentation. */
    private Response apply(WebRequest request, Filter.Chain next) throws Exception {
        if (request.attribute(CONTEXT).isPresent()) {
            throw new IllegalStateException("the security filter ran twice for one request: register"
                    + " WebSecurity.filter() once, on the router");
        }
        if (sessions == null) {
            return secure(request, next);
        }
        var reached = new boolean[1];
        try {
            return sessions.filter().filter(request, withSession -> {
                reached[0] = true;
                return secure(withSession, next);
            });
        } catch (IllegalStateException e) {
            if (reached[0]) {
                throw e;
            }
            // the session filter refused before it called on: it had already run for the request
            throw new IllegalStateException("the session filter already ran for the request: with"
                    + " WebSecurity.builder().sessions(...) the security filter runs it itself, so register only"
                    + " security.filter(), not sessions.filter()", e);
        }
    }

    /** The steps after the session filter; see the class documentation. */
    private Response secure(WebRequest request, Filter.Chain next) throws Exception {
        if (request.headers().all("Authorization").size() > 1) {
            throw duplicateAuthorization();
        }
        var identity = sessionIdentity(request);
        for (int i = 0; identity.isEmpty() && i < mechanisms.size(); i++) {
            var outcome = Objects.requireNonNull(mechanisms.get(i).authenticate(request),
                    "a RequestAuthentication returned null");
            switch (outcome) {
                case AuthOutcome.None none -> {
                    // no credentials for this mechanism: try the next one
                }
                case AuthOutcome.Rejected rejected -> {
                    return unauthorized;
                }
                case AuthOutcome.Success success -> identity = Optional.of(success.identity());
            }
        }
        var secured = request.withAttribute(CONTEXT, new SecurityContext(identity)).withAttribute(OWNER, this);
        for (var rule : rules) {
            if (rule.matcher().matches(secured)) {
                return rule.access().allows(identity, secured) ? next.proceed(secured) : denied(identity);
            }
        }
        return denied(identity);
    }

    /**
     * The identity that {@link #login} stored in the request's live session; empty without
     * sessions, without a session or without an identity in it. Never a rejection.
     */
    private Optional<Identity> sessionIdentity(WebRequest request) {
        if (sessions == null) {
            return Optional.empty();
        }
        return Sessions.find(request).flatMap(session -> session.get(LOGGED_IN));
    }

    /** 401 for an anonymous request, 403 for an authenticated one. */
    private Response denied(Optional<Identity> identity) {
        return identity.isPresent() ? forbidden : unauthorized;
    }

    /** The 400 for more than one {@code Authorization} field. */
    static WebException duplicateAuthorization() {
        return new WebException(400, "Bad Request", "The request has more than one Authorization field.");
    }

    /**
     * Rules, mechanisms and whether there are sessions, never credentials or session ids.
     *
     * @return {@code WebSecurity[n mechanisms, m rules]}, with {@code , sessions} at the end
     *         when it was built with {@link Builder#sessions}
     */
    @Override
    public String toString() {
        return "WebSecurity[" + mechanisms.size() + " mechanisms, " + rules.size() + " rules"
                + (sessions == null ? "" : ", sessions") + "]";
    }

    /** The 401 of {@link #requireIdentity}: the security filter's own 401 response. */
    private static final class Unauthorized extends WebException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient WebSecurity owner;

        Unauthorized(WebSecurity owner) {
            super(401, null, null, owner.challenges);
            this.owner = owner;
        }

        @Override
        public Response toResponse(WebRequest request) {
            return owner.unauthorized;
        }
    }

    /**
     * Builds a {@link WebSecurity}. Not thread-safe; the {@code WebSecurity} it builds is.
     * Nothing is checked until {@link #build()}, which reports every problem at once, except
     * {@code null} arguments, which fail at once.
     */
    public static final class Builder {

        private final List<RequestAuthentication> mechanisms = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        /** Every {@link #sessions} call; more than one is a problem for {@link #build()}. */
        private final List<Sessions> sessions = new ArrayList<>();

        private Builder() {
        }

        /**
         * Adds an authentication mechanism, such as {@link HttpBasic#of}. May be called several
         * times; the mechanisms are tried in this order and the first that does not answer
         * {@link AuthOutcome.None} decides. Without any, every request is anonymous (unless
         * {@link #sessions} gives it an identity). With sessions, the mechanisms are tried only
         * for a request whose session has no identity.
         *
         * @param mechanism the mechanism
         * @return this builder
         * @throws NullPointerException if {@code mechanism} is {@code null}
         */
        public Builder authentication(RequestAuthentication mechanism) {
            mechanisms.add(Objects.requireNonNull(mechanism, "mechanism"));
            return this;
        }

        /**
         * Keeps the login in the sessions of {@code sessions}, the counterpart of
         * {@code sessionManagement(s -> s.sessionCreationPolicy(IF_REQUIRED))} with
         * {@code HttpSessionSecurityContextRepository}. Then:
         * <ul>
         *   <li>the security filter runs {@code sessions.filter()} itself, outermost; register
         *       <b>only</b> {@code security.filter()} on the router (registering
         *       {@code sessions.filter()} too gives 500, see {@link WebSecurity#filter()});</li>
         *   <li>the identity that {@link WebSecurity#login} stored in a request's session comes
         *       first, before the mechanisms, which are not tried then; a missing, unknown or
         *       expired session cookie makes no difference to the mechanisms and never gives
         *       401 by itself;</li>
         *   <li>{@link WebSecurity#login} and {@link WebSecurity#logout} work in the handlers
         *       (without {@code sessions(...)} they throw {@link IllegalStateException}).</li>
         * </ul>
         * The security filter never creates a session; only {@link WebSecurity#login} (or the
         * application's own {@code Sessions.getOrCreate}) does. Call it once: a second call is a
         * problem that {@link #build()} reports.
         *
         * @param sessions the sessions, the same instance for the whole router
         * @return this builder
         * @throws NullPointerException if {@code sessions} is {@code null}
         */
        public Builder sessions(Sessions sessions) {
            this.sessions.add(Objects.requireNonNull(sessions, "sessions"));
            return this;
        }

        /**
         * Adds an access rule. Rules are looked at in this order and the first whose matcher
         * matches decides; a request that no rule matches is denied. End with
         * {@code rule(RequestMatcher.anyRequest(), ...)} to decide for the rest.
         *
         * @param matcher which requests the rule is about
         * @param access  what it allows
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         */
        public Builder rule(RequestMatcher matcher, AccessRule access) {
            rules.add(new Rule(Objects.requireNonNull(matcher, "matcher"), Objects.requireNonNull(access, "access")));
            return this;
        }

        /**
         * Builds the security. The builder can still be used afterwards; later changes do not
         * affect what was built.
         *
         * @return the security
         * @throws IllegalArgumentException with one line per problem: a rule after a rule with
         *                                  {@link RequestMatcher#anyRequest()} (it can never
         *                                  match), and {@link #sessions} called more than once
         */
        public WebSecurity build() {
            var problems = new ArrayList<String>();
            int any = -1;
            for (int i = 0; i < rules.size(); i++) {
                if (any >= 0) {
                    problems.add("rule " + (i + 1) + " comes after anyRequest() in rule " + (any + 1)
                            + " and can never match");
                } else if (rules.get(i).matcher() == RequestMatcher.anyRequest()) {
                    any = i;
                }
            }
            if (sessions.size() > 1) {
                problems.add("sessions(...) is called " + sessions.size() + " times; call it once, with the one"
                        + " Sessions of the router");
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", problems));
            }
            return new WebSecurity(List.copyOf(mechanisms), List.copyOf(rules),
                    sessions.isEmpty() ? null : sessions.getFirst());
        }
    }
}
