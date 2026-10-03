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
import spola.web.WebException;
import spola.web.WebRequest;

/**
 * The security of a router: authentication, access rules and the 401 and 403 responses, the
 * counterpart of Spring Security's {@code SecurityFilterChain} bean with
 * {@code authorizeHttpRequests} and {@code httpBasic()}. Immutable and thread-safe; build it
 * with {@link #builder()}:
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
 *   <li>answers 400 if the request has more than one {@code Authorization} field (RFC 9110
 *       §11.6.2), before any mechanism runs;</li>
 *   <li>tries the {@link RequestAuthentication} mechanisms in registration order; the first one
 *       that does not answer {@link AuthOutcome.None} decides. {@link AuthOutcome.Rejected}
 *       gives 401 at once, whatever the rules say (wrong credentials are never ignored, also on
 *       a {@link AccessRule#permitAll() permitAll} path). No mechanism, or only {@code None},
 *       means anonymous;</li>
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
 * ({@code Basic realm="app", charset="UTF-8"}; none without such a mechanism). A denied
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
 */
public final class WebSecurity {

    /** The attribute with the request's {@link SecurityContext}; only this module has the key. */
    private static final AttributeKey<SecurityContext> CONTEXT = AttributeKey.of("spola.security.web.context",
            SecurityContext.class);
    /** The {@code WebSecurity} whose filter ran, for the 401 of {@link #requireIdentity}. */
    private static final AttributeKey<WebSecurity> OWNER = AttributeKey.of("spola.security.web.owner",
            WebSecurity.class);

    private static final String MISSING_FILTER = "the request has no SecurityContext: register WebSecurity.filter()"
            + " on the router, first";

    /** One rule: a matcher and what it allows. */
    private record Rule(RequestMatcher matcher, AccessRule access) {
    }

    private final List<RequestAuthentication> mechanisms;
    private final List<Rule> rules;
    private final Headers challenges;
    private final Response unauthorized;
    private final Response forbidden;
    private final Filter filter;

    private WebSecurity(List<RequestAuthentication> mechanisms, List<Rule> rules) {
        this.mechanisms = mechanisms;
        this.rules = rules;
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
     * {@code @AuthenticationPrincipal} and {@code SecurityContextHolder.getContext()}.
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
        if (request.headers().all("Authorization").size() > 1) {
            throw duplicateAuthorization();
        }
        Optional<Identity> identity = Optional.empty();
        for (var mechanism : mechanisms) {
            var outcome = Objects.requireNonNull(mechanism.authenticate(request), "a RequestAuthentication returned null");
            switch (outcome) {
                case AuthOutcome.None none -> {
                    // no credentials for this mechanism: try the next one
                }
                case AuthOutcome.Rejected rejected -> {
                    return unauthorized;
                }
                case AuthOutcome.Success success -> identity = Optional.of(success.identity());
            }
            if (identity.isPresent()) {
                break;
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

    /** 401 for an anonymous request, 403 for an authenticated one. */
    private Response denied(Optional<Identity> identity) {
        return identity.isPresent() ? forbidden : unauthorized;
    }

    /** The 400 for more than one {@code Authorization} field. */
    static WebException duplicateAuthorization() {
        return new WebException(400, "Bad Request", "The request has more than one Authorization field.");
    }

    /**
     * Rules and mechanisms, never credentials.
     *
     * @return {@code WebSecurity[n mechanisms, m rules]}
     */
    @Override
    public String toString() {
        return "WebSecurity[" + mechanisms.size() + " mechanisms, " + rules.size() + " rules]";
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

        private Builder() {
        }

        /**
         * Adds an authentication mechanism, such as {@link HttpBasic#of}. May be called several
         * times; the mechanisms are tried in this order and the first that does not answer
         * {@link AuthOutcome.None} decides. Without any, every request is anonymous.
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
         *                                  match)
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
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", problems));
            }
            return new WebSecurity(List.copyOf(mechanisms), List.copyOf(rules));
        }
    }
}
