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
 * The security of a router: authentication, access rules, CSRF protection and the 401 and 403
 * responses, the counterpart of Spring Security's {@code SecurityFilterChain} bean with
 * {@code authorizeHttpRequests}, {@code httpBasic()}, a login kept in the HTTP session
 * ({@code HttpSessionSecurityContextRepository}) and {@code csrf()} with a cookie for a
 * single-page application ({@code CookieCsrfTokenRepository.withHttpOnlyFalse()}). Immutable and
 * thread-safe; build it with {@link #builder()}:
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
 * 404, 405 or an automatic {@code OPTIONS}, and no other filter sees a request it denies.
 * Behind a reverse proxy the one exception is {@link spola.web.ForwardedHeaders}, which comes
 * before it: then this filter, the session filter it runs and the rules see the client's scheme
 * and address, and the cookies get {@code Secure} over HTTPS (see "{@code Secure} from the
 * scheme"). For each request it:
 * <ol>
 *   <li>with {@linkplain Builder#sessions sessions}, runs the session filter of those sessions
 *       around all the steps below, so that the request's session is found from its cookie (see
 *       "Login with a session");</li>
 *   <li>answers 400 if the request has more than one {@code Authorization} field (RFC 9110
 *       §11.6.2), before any mechanism runs;</li>
 *   <li>with {@linkplain Builder#csrf CSRF protection}, checks the request: a method other than
 *       {@code GET}, {@code HEAD}, {@code OPTIONS} and {@code TRACE} needs an
 *       {@code X-XSRF-TOKEN} header equal to its {@code XSRF-TOKEN} cookie, or the answer is
 *       403 at once, before the identity, so no mechanism runs for a forged request (see "CSRF
 *       protection");</li>
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
 *       request. The response to an allowed request with an identity (from the session or a
 *       mechanism) gets {@code Cache-Control: no-store} when it has no {@code Cache-Control},
 *       and when its {@code Cache-Control} has {@code public} or {@code s-maxage}; another
 *       value that the handler set stays (see "Caches" below).</li>
 * </ol>
 * With CSRF protection, the response that comes back (the handler's, 401, 403, 404, a mapped
 * error) gets a {@code Set-Cookie} with a new {@code XSRF-TOKEN} when the request had no
 * well-formed one, or when {@link #login} or {@link #logout} ran. That cookie and the session
 * cookie get {@code Secure} when their cookie function sets it or when the scheme of the request
 * this filter got is {@code https} (see "{@code Secure} from the scheme" below).
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
 * {@code WWW-Authenticate}; a request that fails the CSRF check gets the same 403, also when it
 * is anonymous. Both carry {@code Cache-Control: no-store}. The responses are
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
 *   <li><b>The session before the mechanisms.</b> See step 4 above. A request without a session
 *       is authenticated by the mechanisms as without sessions, so {@link HttpBasic} next to
 *       sessions keeps working for clients that do not log in.</li>
 *   <li><b>Sessions only at login.</b> The security filter never creates a session: anonymous
 *       requests, 401, 403 and open paths get no session cookie (with CSRF protection they can
 *       get an {@code XSRF-TOKEN} cookie, which is not a session). {@link #login} creates or
 *       renews it.</li>
 *   <li><b>At most {@link Builder#maximumSessions} sessions per user</b> (10 unless set), the
 *       counterpart of {@code sessionManagement(s -> s.maximumSessions(10))}: when a login would
 *       give a user more, the user's least recently used sessions end (a later request with
 *       their cookie is anonymous), never the new login. Users are compared on
 *       {@link Identity#name()}. One account that logs in again and again (every login without a
 *       cookie is a new session) therefore holds at most that many of the
 *       {@code SessionOptions.maxSessions} and cannot lock other users out. A session counts
 *       for the user from the login until it ends, whatever other requests with its cookie do;
 *       logins of one user at the same time never end each other's sessions while they are
 *       handled, and once their responses are built the user has at most the limit.</li>
 *   <li><b>Another user gets a new session.</b> When the request's session already holds the
 *       identity of another user (a shared computer without a logout), {@link #login} ends that
 *       session and starts a new, empty one, so the new user never sees the previous user's
 *       attributes. The same user, or a session without an identity, keeps its attributes.</li>
 *   <li><b>A snapshot.</b> The session holds the {@link Identity} that {@link #login} got.
 *       Changed roles, a disabled account or a removed user in the {@code UserStore} do not
 *       affect a session that is already logged in, until {@link #logout} or the session expires
 *       (as Spring by default).</li>
 *   <li><b>Expiry.</b> A session expires after its idle timeout
 *       ({@code SessionOptions.idleTimeout}); every request with its cookie counts as a use. An
 *       expired session makes the request anonymous, so a login on a {@code permitAll} path
 *       works with an expired cookie.</li>
 *   <li><b>{@code Secure}.</b> The session cookie that {@link #login} sets and the removal that
 *       {@link #logout} sends get {@code Secure} also when the request this filter got is
 *       {@code https}; see "{@code Secure} from the scheme" below.</li>
 * </ul>
 *
 * <h2>CSRF protection</h2>
 * With {@link Builder#csrf(Csrf) csrf(Csrf.cookie())} the filter protects requests that change
 * something against cross-site request forgery with Angular's pattern, a double-submit cookie
 * (see {@link Csrf}): the response sets the cookie {@code XSRF-TOKEN}, which the application's
 * JavaScript can read, and a request with a method other than {@code GET}, {@code HEAD},
 * {@code OPTIONS} and {@code TRACE} must repeat it in the header {@code X-XSRF-TOKEN}:
 *
 * <pre>{@code
 * WebSecurity security = WebSecurity.builder()
 *         .sessions(sessions)
 *         .csrf(Csrf.cookie())                           // XSRF-TOKEN / X-XSRF-TOKEN, 403 on a mismatch
 *         .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
 *         .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li><b>The check comes before the identity</b> (step 3 above), on every path, the login and
 *       logout included: a request without exactly one matching header gets 403, the same
 *       response as a denied rule, and no mechanism, no handler and no filter inside this one
 *       runs.</li>
 *   <li><b>The cookie on the first response.</b> A request without a well-formed
 *       {@code XSRF-TOKEN} cookie gets one with a new token on whatever response leaves the
 *       filter; a request with one gets none.</li>
 *   <li><b>A new token at login and logout.</b> {@link #login} and {@link #logout} give the
 *       response a new token, so the token from before stops working.</li>
 *   <li><b>No stored tokens.</b> The server compares the header with the cookie of the same
 *       request and keeps nothing, so the protection works with and without sessions.</li>
 * </ul>
 *
 * <h2>Caches</h2>
 * A response to a logged-in user, or one that carries the session id or a CSRF token, must not
 * be stored by a shared cache (a proxy or a CDN) and handed to someone else; Spring Security
 * writes {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} on every response
 * that has none for the same reason.
 * <ul>
 *   <li><b>A response to a request with an identity</b> (from the session or a mechanism) gets
 *       {@code Cache-Control: no-store} when it has no {@code Cache-Control}. A value that the
 *       handler set stays, such as {@code private, no-cache} or {@code max-age=60}, except one
 *       with {@code public} or {@code s-maxage} (or one that does not parse as a list of
 *       directives): then every {@code Cache-Control} field is replaced by one
 *       {@code Cache-Control: no-store}.</li>
 *   <li><b>A response that sets {@code XSRF-TOKEN}</b> (and, with sessions, one that sets or
 *       removes the session cookie) keeps the handler's {@code Cache-Control} only when it already
 *       keeps shared caches out ({@code no-store}, or {@code private} without a field list, and no
 *       {@code public} or {@code s-maxage}); any other value, or none, becomes
 *       {@code Cache-Control: no-store}.</li>
 *   <li>The 401 and 403 have {@code Cache-Control: no-store}. A response to an anonymous request
 *       that sets no cookie is left as the handler made it.</li>
 *   <li>Directive names are compared without regard to case, and every {@code Cache-Control}
 *       field counts. Other fields ({@code Expires}, {@code Pragma}, {@code CDN-Cache-Control},
 *       {@code Surrogate-Control}) are not changed: a CDN that obeys a targeted field of its own
 *       instead of {@code Cache-Control} can still store the response, so do not set one on
 *       responses that can carry a cookie or a logged-in user's data.</li>
 * </ul>
 *
 * <h2>{@code Secure} from the scheme</h2>
 * The session cookie (with {@link Builder#sessions sessions}) and {@code XSRF-TOKEN} (with
 * {@link Builder#csrf csrf}) get {@code Secure} when their cookie function sets it
 * ({@code SessionOptions.withCookie(c -> c.secure(true))},
 * {@code Csrf.cookie().withCookie(c -> c.secure(true))}; then also over {@code http}), <b>or</b>
 * when the {@link WebRequest#scheme()} of the request this filter got is {@code https}, as
 * Tomcat and Spring Security do with {@code request.isSecure()}. This holds for the session
 * cookie and its removal, and for {@code XSRF-TOKEN} on the first response and at
 * {@link #login} and {@link #logout}; a handler or a filter inside this one does not change
 * which scheme applies. Over TLS the scheme is {@code https}. Behind a TLS proxy it is
 * {@code https} only when {@link spola.web.ForwardedHeaders} is registered <b>before</b> this
 * filter and trusts the proxy:
 *
 * <pre>{@code
 * Router router = Router.builder()
 *         .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))  // first
 *         .filter(security.filter())                     // sees https: both cookies get Secure
 *         ...
 * }</pre>
 *
 * Registered after this filter, {@code ForwardedHeaders} comes too late and the cookies get no
 * {@code Secure} from the proxy. There is no way to turn the rule off. See
 * {@link spola.web.Sessions} and {@link Csrf}.
 *
 * <p>An application reached only over https sets {@code secure(true)} on both cookies, and names
 * the session cookie {@code __Host-SESSION}, so that {@code Secure} does not depend on
 * {@code ForwardedHeaders} trusting the proxy's address (when it does not, the scheme is
 * {@code http}; {@code ForwardedHeaders} logs a warning the first time):
 *
 * <pre>{@code
 * Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT
 *         .withCookieName("__Host-SESSION")
 *         .withCookie(c -> c.secure(true)));
 * WebSecurity security = WebSecurity.builder()
 *         .sessions(sessions)
 *         .csrf(Csrf.cookie().withCookie(c -> c.secure(true)))
 *         ...
 * }</pre>
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
    /** The request's CSRF state, with {@link Builder#csrf}; only this module has the key. */
    private static final AttributeKey<Csrf.Exchange> CSRF = AttributeKey.of("spola.security.web.csrf",
            Csrf.Exchange.class);

    private static final String MISSING_FILTER = "the request has no SecurityContext: register WebSecurity.filter()"
            + " on the router, first";

    /** One rule: a matcher and what it allows. */
    private record Rule(RequestMatcher matcher, AccessRule access) {
    }

    /** The most sessions per user unless {@link Builder#maximumSessions} says otherwise. */
    static final int DEFAULT_MAXIMUM_SESSIONS = 10;

    private final List<RequestAuthentication> mechanisms;
    private final List<Rule> rules;
    /** The sessions whose filter this security runs, or {@code null} without {@link Builder#sessions}. */
    private final Sessions sessions;
    /** The most sessions per user ({@link Builder#maximumSessions}). */
    private final int maximumSessions;
    /** The CSRF protection, or {@code null} without {@link Builder#csrf}. */
    private final Csrf.Protection csrf;
    private final Headers challenges;
    private final Response unauthorized;
    private final Response forbidden;
    private final Filter filter;

    private WebSecurity(List<RequestAuthentication> mechanisms, List<Rule> rules, Sessions sessions,
            int maximumSessions, Csrf.Protection csrf) {
        this.mechanisms = mechanisms;
        this.rules = rules;
        this.sessions = sessions;
        this.maximumSessions = maximumSessions;
        this.csrf = csrf;
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
     * ({@code Router.builder().filter(security.filter())}), behind a reverse proxy right after
     * {@link spola.web.ForwardedHeaders}; see the class documentation.
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
     *   <li>the session gets a new id with {@link Sessions#renewFor} (and the identity's name),
     *       the protection against session fixation: an id that existed before the login (one an
     *       attacker planted, say) stops working at once, and the session keeps its attributes.
     *       Without a live session a new one is created. A session that holds <b>another
     *       user's</b> identity is ended instead, and the login gets a new, empty session;</li>
     *   <li>if the user would have more than {@link Builder#maximumSessions} sessions (10 unless
     *       set), the user's least recently used ones end, never this one;</li>
     *   <li>the identity is stored in the session under a key only this module has;</li>
     *   <li>the response sets the session cookie with the new id (the session filter adds the
     *       {@code Set-Cookie});</li>
     *   <li>with {@linkplain Builder#csrf CSRF protection}, the response also sets a new
     *       {@code XSRF-TOKEN} (Spring's {@code CsrfAuthenticationStrategy}): the token from
     *       before the login stops working once the browser has the new cookie.</li>
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
     * <p>Logging in again as the same user replaces the identity (and renews the id again); as
     * another user it starts another new session. Nothing is logged.
     *
     * @param request  the request, as a handler or a filter inside the security filter gets it
     * @param identity who logged in
     * @throws WebException          503 if a new session is needed and the session limit is
     *                               reached ({@code SessionOptions.maxSessions}, after the user's
     *                               own oldest sessions beyond {@link Builder#maximumSessions}
     *                               ended); nothing is stored and no session id is set (a session
     *                               of another user has then ended, and the response removes its
     *                               cookie)
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
        var owner = requireSessions(request, "login");
        // a new id; another user's session ends; the user's oldest sessions beyond the limit end
        Sessions.renewFor(request, identity.name(), owner.maximumSessions).put(LOGGED_IN, identity);
        owner.renewToken(request, "login");
    }

    /**
     * Logs out, the counterpart of {@code session.invalidate()} (Spring Security's
     * {@code SecurityContextLogoutHandler}): the request's session is removed with all its
     * attributes ({@link Sessions#invalidate}), the old id stops working at once, and the
     * response removes the session cookie ({@code Max-Age=0}). Without a live session nothing
     * happens to the session and the response gets no {@code Set-Cookie} for it. With
     * {@linkplain Builder#csrf CSRF
     * protection} the response sets a new {@code XSRF-TOKEN} in either case (Spring's
     * {@code CsrfLogoutHandler}), so the token from before stops working. As with
     * {@link #login}, this request's own {@link SecurityContext} does not change.
     *
     * @param request the request, as a handler or a filter inside the security filter gets it
     * @throws IllegalStateException if the request has not passed the filter of a
     *                               {@code WebSecurity} built with
     *                               {@link Builder#sessions(Sessions) sessions(...)}: a
     *                               programming error, 500; with CSRF protection also if it is
     *                               called after the response was built
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static void logout(WebRequest request) {
        Objects.requireNonNull(request, "request");
        var owner = requireSessions(request, "logout");
        Sessions.invalidate(request);
        owner.renewToken(request, "logout");
    }

    /** The security whose filter the request passed; fails unless it has sessions. */
    private static WebSecurity requireSessions(WebRequest request, String method) {
        var owner = request.attribute(OWNER).orElseThrow(() -> new IllegalStateException("WebSecurity." + method
                + " needs a request that passed the security filter: register WebSecurity.filter() on the router,"
                + " first, for a WebSecurity built with sessions(...)"));
        if (owner.sessions == null) {
            throw new IllegalStateException("WebSecurity." + method + " needs sessions: build the WebSecurity with"
                    + " WebSecurity.builder().sessions(sessions) and register only security.filter()");
        }
        return owner;
    }

    /** With CSRF protection, the response to {@code request} gets a new token. */
    private void renewToken(WebRequest request, String method) {
        if (csrf != null) {
            request.attribute(CSRF).orElseThrow(() -> new IllegalStateException("the request has no CSRF state: pass"
                    + " WebSecurity." + method + " the request the handler got")).renew(method);
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
        if (csrf == null) {
            return authorize(request, next);
        }
        var exchange = csrf.begin(request);
        Response response;
        try {
            response = exchange.allowed() ? authorize(request.withAttribute(CSRF, exchange), next) : forbidden;
        } catch (Throwable failure) {
            exchange.abandon();
            throw failure;
        }
        return exchange.finish(response);
    }

    /** The identity and the rules; see the class documentation. */
    private Response authorize(WebRequest request, Filter.Chain next) throws Exception {
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
                if (!rule.access().allows(identity, secured)) {
                    return denied(identity);
                }
                var response = next.proceed(secured);
                return identity.isPresent() ? CacheControl.forIdentity(response) : response;
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
     * Rules, mechanisms and whether there are sessions and CSRF protection, never credentials,
     * session ids or tokens.
     *
     * @return {@code WebSecurity[n mechanisms, m rules]}, with {@code , sessions} when it was
     *         built with {@link Builder#sessions} and {@code , csrf} when it was built with
     *         {@link Builder#csrf}
     */
    @Override
    public String toString() {
        return "WebSecurity[" + mechanisms.size() + " mechanisms, " + rules.size() + " rules"
                + (sessions == null ? "" : ", sessions") + (csrf == null ? "" : ", csrf") + "]";
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
        /** Every {@link #csrf} call; more than one is a problem for {@link #build()}. */
        private final List<Csrf> csrf = new ArrayList<>();
        /** The last {@link #maximumSessions} value, or {@code null} if it was not called. */
        private Integer maximumSessions;

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
         * application's own {@code Sessions.getOrCreate}) does. A user has at most
         * {@link #maximumSessions} sessions (10 unless set). Call it once: a second call is a
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
         * The most sessions a user may have at once, the counterpart of Spring Security's
         * {@code sessionManagement(s -> s.maximumSessions(n))}; 10 unless set. When a
         * {@link WebSecurity#login} would give a user more, the user's least recently used
         * sessions end (as Spring's default {@code maxSessionsPreventsLogin(false)}), never the
         * new login: a later request with an ended session's cookie is anonymous. Users are
         * compared on {@link Identity#name()}, exactly. A use is a request that finds the
         * session, its creation and its renewal.
         *
         * <p>The limit keeps one account from filling {@code SessionOptions.maxSessions}: without
         * it, a client that logs in again and again without a cookie gets a new session each time,
         * until every other login gets 503. Ten is enough for a user's browsers and devices; a
         * client that logs in for each call (a script without cookies) loses its oldest sessions
         * and should keep its cookie instead. Logging in again with a session of the same user
         * renews it and does not count as another one. Logins of the same user at the same time
         * never end each other's sessions while they are handled, so for that moment the user
         * can have more; once their responses are built the user has at most {@code maximum}.
         *
         * <p>Only logins are counted: a session that the application creates with
         * {@code Sessions.getOrCreate} has no user until {@code login}. The last call wins.
         * {@link #build()} reports a value below 1, and a call without {@link #sessions}.
         *
         * @param maximum the most sessions per user, at least 1
         * @return this builder
         */
        public Builder maximumSessions(int maximum) {
            this.maximumSessions = maximum;
            return this;
        }

        /**
         * Turns on CSRF protection with {@code csrf}, the counterpart of Spring Security's
         * {@code csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
         * .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))} with a
         * {@code CsrfCookieFilter}; usually {@code csrf(Csrf.cookie())}. It is a double-submit
         * cookie, Angular's standard pattern (see {@link Csrf}):
         * <ul>
         *   <li><b>The pattern and what it rests on.</b> The response sets the cookie
         *       {@code XSRF-TOKEN}, which is not {@code HttpOnly}, so the application's JavaScript
         *       can read it and send it back in the header {@code X-XSRF-TOKEN}. Another origin
         *       can make the browser send the cookie but cannot read it, and cannot set the
         *       header without CORS. Script on the application's own pages (XSS) and an origin
         *       that CORS lets send the header are not stopped.</li>
         *   <li><b>No stored tokens.</b> The server compares the header with the cookie of the
         *       same request and keeps nothing, not in memory and not in the session; it works
         *       with and without {@link #sessions}.</li>
         *   <li><b>The methods that are not checked</b> are {@code GET}, {@code HEAD},
         *       {@code OPTIONS} and {@code TRACE}, compared exactly. Every other method needs
         *       exactly one {@code X-XSRF-TOKEN} field with a well-formed token equal to one of
         *       the request's well-formed {@code XSRF-TOKEN} cookies, compared in constant time;
         *       otherwise the answer is 403 ({@code Problem.of(403)}, no detail,
         *       {@code Cache-Control: no-store}), on every path, the login and logout
         *       included.</li>
         *   <li><b>The order in the filter:</b> the session filter, the 400 for several
         *       {@code Authorization} fields, this check, the identity (session, then mechanisms),
         *       the rules. A forged request never reaches an {@code Authenticator}.</li>
         *   <li><b>When the cookie is set and replaced:</b> on the first response that leaves the
         *       security filter for a request without a well-formed cookie (handler, 401, 403,
         *       404, mapped errors), with {@code Cache-Control: no-store} unless the response has
         *       a {@code Cache-Control}; and with a new token by {@link WebSecurity#login} and
         *       {@link WebSecurity#logout}. A request with a well-formed cookie gets none, and a
         *       response gets at most one.</li>
         *   <li><b>{@code Secure}</b> when the cookie function sets it, or when the scheme of the
         *       request the security filter got is {@code https} (over TLS, or behind a proxy with
         *       {@link spola.web.ForwardedHeaders} registered before the security filter); see
         *       "{@code Secure} from the scheme" in {@link WebSecurity}.</li>
         * </ul>
         * {@link #build()} applies the cookie function of {@code csrf} once and reports a bad
         * one (see {@link Csrf#withCookie}). Call it once: a second call is a problem that
         * {@link #build()} reports.
         *
         * @param csrf the protection, such as {@code Csrf.cookie()} or
         *             {@code Csrf.cookie().withCookie(c -> c.secure(true))}
         * @return this builder
         * @throws NullPointerException if {@code csrf} is {@code null}
         */
        public Builder csrf(Csrf csrf) {
            this.csrf.add(Objects.requireNonNull(csrf, "csrf"));
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
         *                                  match), {@link #sessions} called more than once,
         *                                  {@link #maximumSessions} below 1 or without
         *                                  {@link #sessions}, {@link #csrf} called more than
         *                                  once, and a cookie
         *                                  function of {@link #csrf} that returns {@code null} or
         *                                  a builder for another cookie, turns {@code HttpOnly}
         *                                  on, sets {@code Max-Age=0} or gives a cookie that
         *                                  {@code ResponseCookie.Builder.build()} rejects (such as
         *                                  {@code SameSite=None} without {@code Secure})
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
            if (maximumSessions != null && maximumSessions < 1) {
                problems.add("maximumSessions(" + maximumSessions + "): a user needs at least 1 session");
            }
            if (maximumSessions != null && sessions.isEmpty()) {
                problems.add("maximumSessions(...) without sessions(...): it limits the sessions of WebSecurity.login;"
                        + " add sessions(sessions)");
            }
            if (csrf.size() > 1) {
                problems.add("csrf(...) is called " + csrf.size() + " times; call it once");
            }
            var protection = csrf.size() == 1 ? csrf.getFirst().protection(problems) : null;
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", problems));
            }
            return new WebSecurity(List.copyOf(mechanisms), List.copyOf(rules),
                    sessions.isEmpty() ? null : sessions.getFirst(),
                    maximumSessions == null ? DEFAULT_MAXIMUM_SESSIONS : maximumSessions, protection);
        }
    }
}
