package spola.web;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.Response;

/**
 * HTTP sessions kept in memory, one per browser, identified by a random id in a cookie: the
 * counterpart of the servlet API's {@code HttpSession} with Spring Boot's
 * {@code server.servlet.session.*}. Immutable configuration, thread-safe; create it once with
 * {@link #inMemory(SessionOptions)}, register its {@link #filter()} on the router and use the
 * static methods in handlers:
 *
 * <pre>{@code
 * static final AttributeKey<Cart> CART = AttributeKey.of("cart", Cart.class);
 *
 * Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT
 *         .withIdleTimeout(Duration.ofMinutes(30)));           // Secure when the scheme is https
 * Router router = Router.builder()
 *         .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))  // behind a TLS proxy: first
 *         .filter(sessions.filter())                             // before filters that use sessions
 *         .post("/cart/items", request -> {
 *             Session session = Sessions.getOrCreate(request);   // request.getSession()
 *             Cart cart = session.get(CART).orElseGet(Cart::empty);
 *             session.put(CART, cart.add(request.readJson(Item.CODEC)));
 *             return Responses.noContent();
 *         })
 *         .get("/cart", request -> Responses.json(request, 200, Cart.CODEC,
 *                 Sessions.find(request).flatMap(s -> s.get(CART)).orElseGet(Cart::empty)))  // getSession(false)
 *         .build();
 * }</pre>
 *
 * <h2>The filter</h2>
 * Register {@link #filter()} on the router, before the filters that use sessions (a filter
 * registered before it does not see the session state), and behind a reverse proxy after
 * {@link ForwardedHeaders} (see "{@code Secure}" below). For each request it reads the session
 * cookie, looks the session up, puts the request's session state on the request as an attribute
 * under a key only this class has, and calls {@code next.proceed}. On the response that comes
 * back, also one from a mapped exception, it adds a {@code Set-Cookie} when the session was
 * created, renewed or invalidated during the request. The filter never creates a session on its
 * own: a request that does not store anything (a health check, say) never gets one. When the
 * filter reaches a request that already has the state (it is registered twice, or two
 * {@code Sessions} are registered on one router) it throws {@link IllegalStateException}, so
 * the router answers 500.
 *
 * <p><b>Without the filter</b> {@link #find}, {@link #getOrCreate}, {@link #renew} and
 * {@link #invalidate} throw {@link IllegalStateException} (500), with a message that says to
 * register {@code filter()}: a forgotten registration shows at once instead of every request
 * looking like it has no session.
 *
 * <h2>The id</h2>
 * Only the server creates ids: 32 bytes from a {@link SecureRandom} (one
 * {@code new SecureRandom()} per {@code Sessions}, not {@code getInstanceStrong()}, which can
 * block), encoded as Base64url without padding: exactly 43 characters from
 * {@code [A-Za-z0-9_-]}, 256 bits. An id a client made up never becomes a session. The store is
 * a {@link ConcurrentHashMap} whose key is the SHA-256 of the id's ASCII bytes; the id itself
 * is not kept in the store, in a {@link Session} or in any field that outlives the response. A
 * lookup therefore takes a time that depends on the hash of the client's text, not on how much
 * of a valid id it guessed, and a memory dump holds no valid ids. The id never appears in a
 * {@code toString()}, an exception message, a log line or a problem response.
 *
 * <h2>The cookie in</h2>
 * Every value of the cookie {@link SessionOptions#cookieName} ({@link WebRequest#cookieAll}) is
 * tried in order, and the first one that names a live session is used, as Tomcat does (a
 * browser can send two when an old cookie with another {@code Path} or {@code Domain} is
 * left). A value that is not exactly 43 characters from {@code [A-Za-z0-9_-]} (quotes, padding,
 * {@code +} or {@code /} included) is never looked up. A malformed, unknown or expired value
 * gives no session, no 400 and no {@code Set-Cookie}. (A request with more than 200 cookies
 * gets the 400 of {@link WebRequest#cookies()} from the filter.)
 *
 * <h2>The cookie out</h2>
 * The session cookie is {@code ResponseCookie.of(cookieName, id)} (by default {@code Path=/},
 * {@code HttpOnly}, {@code SameSite=Lax}, no {@code Max-Age}: a session cookie) with the
 * attributes that {@link SessionOptions#cookie} gives it, by default
 * {@code SESSION=<id>; Path=/; HttpOnly; SameSite=Lax}. The removal is
 * {@code SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax} with the same {@code Path} and
 * {@code Domain}, so that it removes the cookie that was set. The function is applied once, by
 * {@link #inMemory(SessionOptions)}, so a mistake in it shows at startup; its attributes are
 * used for every session cookie. A response gets at most one {@code Set-Cookie} for the session,
 * with the final outcome: created and then invalidated during one request gives only the
 * removal, invalidated and then created gives only the new id. A response that sets or removes
 * the cookie also gets {@code Cache-Control: no-store} unless it has a {@code Cache-Control}, so
 * that a shared cache never keeps a response with an id.
 *
 * <h2>{@code Secure}</h2>
 * As Tomcat sets {@code Secure} on {@code JSESSIONID} when {@code request.isSecure()}, the
 * session cookie and its removal get {@code Secure} when the function sets it, <b>or</b> when the
 * request's {@link WebRequest#scheme()} is {@code https}:
 * {@code SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax} and
 * {@code SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax}. Nothing else in the line
 * changes.
 * <ul>
 *   <li><b>Which scheme.</b> The scheme of the request that this filter got, decided when the
 *       filter starts, for each request on its own. With
 *       {@code WebSecurity.builder().sessions(sessions)} the security filter runs this filter
 *       itself, with the request the security filter got. A handler or a filter inside this one
 *       does not change it, so {@link #renew} at login and {@link #invalidate} at logout use the
 *       same scheme.</li>
 *   <li><b>Over TLS</b> the scheme is {@code https}. <b>Behind a TLS proxy</b> (Caddy, nginx) the
 *       connection is {@code http}, and the scheme is {@code https} only when
 *       {@link ForwardedHeaders} is registered <b>before</b> this filter (first on the router)
 *       and trusts the proxy that sends {@code X-Forwarded-Proto: https}. Registered after this
 *       filter (or after the security filter that runs it), it comes too late: the cookie gets
 *       no {@code Secure} from the proxy.</li>
 *   <li><b>{@code withCookie(c -> c.secure(true))}</b> gives {@code Secure} on every response,
 *       also over {@code http}, as before.</li>
 *   <li><b>There is no way to turn the rule off</b> (Tomcat does not have one either): a cookie
 *       set over {@code https} always has {@code Secure}. The checks at startup do not depend
 *       on it: {@code SameSite=None}, {@code __Secure-} and {@code __Host-} still need
 *       {@code secure(true)} in the function, since a request over {@code http} would otherwise
 *       get a cookie that the browser rejects.</li>
 *   <li>A removal over {@code http} of a cookie that was set over {@code https} has no
 *       {@code Secure}, and a browser can then ignore it.</li>
 * </ul>
 *
 * <h2>The lifecycle</h2>
 * <ul>
 *   <li>{@link #find} never creates a session.</li>
 *   <li>{@link #getOrCreate} gives the request's live session or creates one (and the response
 *       sets the cookie).</li>
 *   <li>{@link #renew}, the counterpart of {@code request.changeSessionId()}, protects against
 *       session fixation: a live session gets a new id atomically (the old id stops working at
 *       once, also for requests with the old id that are being handled) and keeps its
 *       attributes; without a session a new, empty one is created.</li>
 *   <li>{@link #invalidate} removes the session with all its attributes, and the response
 *       removes the cookie. Without a session it does nothing (no {@code Set-Cookie}).</li>
 *   <li>A {@link Session} whose session ended (invalidated by any request, or expired), or
 *       that came with an id another request has since renewed: {@code get} gives empty,
 *       {@code remove} does nothing, {@code put} throws {@link IllegalStateException}.</li>
 *   <li>{@code getOrCreate} that has to create a session, and {@code renew}, throw
 *       {@link IllegalStateException} once the filter has built the response (from a thread
 *       that outlived the request, say): the new id could not reach the browser.</li>
 * </ul>
 *
 * <h2>Expiry and cleanup</h2>
 * A session has expired when {@link SessionOptions#idleTimeout} has passed since it was last
 * used ({@code now >= last use + idleTimeout}). The filter finding the session for a request
 * counts as a use, also when the handler never reads it; so do creating and renewing it. Time
 * comes from the {@link InstantSource} (the system clock unless given). An expired session is
 * removed when it is looked up. The filter also removes every expired session at most once a
 * minute (by the clock), on a request that passes it. There is no background thread, so
 * {@code Sessions} needs no {@code close()}.
 *
 * <h2>The limit</h2>
 * At most {@link SessionOptions#maxSessions} sessions are in memory, also when many requests
 * create sessions at once. When the limit is reached, expired sessions are removed first (a pass
 * over every session, skipped only when none can have expired since the last pass, so that a
 * flood of refused requests does not repeat it); if the limit is still reached, {@link #getOrCreate} (and {@link #renew} without a session) throws a
 * {@link WebException} 503 with a fixed detail, which gives a problem response without
 * {@code Set-Cookie}. Existing sessions are never removed to make room, so whoever can create
 * sessions cannot log others out. Renewing an existing session does not count as a new one.
 * Sessions are lost when the process stops and are not shared between servers.
 */
public final class Sessions {

    /** The request's session state; only this class has the key. */
    private static final AttributeKey<SessionState> STATE = AttributeKey.of("spola.web.sessions", SessionState.class);

    private static final String MISSING_FILTER = "the request has no session state: register sessions.filter() on"
            + " the router, before the filters and handlers that use sessions";

    /** How often the filter removes every expired session, at most. */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);
    /** Random bytes per id. */
    static final int ID_BYTES = 32;
    /** Characters per id: 32 bytes in Base64url without padding. */
    static final int ID_LENGTH = 43;
    /** A well-formed value for building the cookie with the options' function; never an issued id. */
    private static final String PLACEHOLDER = "A".repeat(ID_LENGTH);

    /** The key in the store: the SHA-256 of an id, as four longs. */
    record Key(long a, long b, long c, long d) {
        /** No hash bits in logs. */
        @Override
        public String toString() {
            return "Key";
        }
    }

    /** A session created for a request, with its new id. */
    record Issued(Session session, String id) {
        /** Never the id. */
        @Override
        public String toString() {
            return "Issued";
        }
    }

    private final SessionOptions options;
    private final InstantSource clock;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<Key, SessionEntry> store = new ConcurrentHashMap<>();
    /** Sessions in the store plus those being created; never above {@code maxSessions}. */
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicReference<Instant> nextSweep;
    /**
     * No session in the store expires before this: the earliest expiry the last cleanup saw.
     * New and used sessions expire later, so until then a cleanup cannot remove anything.
     */
    private final AtomicReference<Instant> nothingExpiresBefore = new AtomicReference<>(Instant.MIN);
    /** How many cleanups ran, for tests. */
    private final AtomicInteger sweeps = new AtomicInteger();
    /** The session cookie as the options' function made it, with {@link #PLACEHOLDER} as value. */
    private final ResponseCookie template;
    /** The removal with the template's attributes: for a request whose scheme is not {@code https}. */
    private final ResponseCookie removal;
    /** The removal with {@code Secure}: for a request whose scheme is {@code https}. */
    private final ResponseCookie secureRemoval;
    private final Filter filter;

    private Sessions(SessionOptions options, InstantSource clock) {
        this.options = options;
        this.clock = clock;
        this.template = template(options);
        this.removal = attributes(ResponseCookie.of(options.cookieName(), ""), template).maxAge(Duration.ZERO).build();
        this.secureRemoval = attributes(ResponseCookie.of(options.cookieName(), ""), template).secure(true)
                .maxAge(Duration.ZERO).build();
        this.nextSweep = new AtomicReference<>(clock.instant().plus(SWEEP_INTERVAL));
        this.filter = this::apply;
    }

    /**
     * Sessions in memory with the system clock.
     *
     * @param options the settings
     * @return the sessions
     * @throws NullPointerException     if {@code options} is {@code null}
     * @throws IllegalArgumentException if {@link SessionOptions#cookie} returns {@code null} or a
     *                                  cookie that {@link ResponseCookie.Builder#build()} rejects
     *                                  (such as {@code SameSite=None} without {@code Secure}, or a
     *                                  {@code __Host-} name with a {@code Domain}); see
     *                                  {@link #inMemory(SessionOptions, InstantSource)}
     */
    public static Sessions inMemory(SessionOptions options) {
        return inMemory(options, InstantSource.system());
    }

    /**
     * Sessions in memory with {@code clock} for expiry (tests control it; normally
     * {@link InstantSource#system()}).
     *
     * <p>The cookie function is applied here, once, to {@code ResponseCookie.of(cookieName, id)}
     * with a well-formed stand-in id, and the cookie and its removal are built from the result,
     * so a mistake in the settings shows at startup and not at the first login. The attributes
     * it sets are used for every session cookie. It must return the builder it got (with
     * attributes changed), and it must not set {@code Max-Age} to zero (the browser would drop
     * the cookie at once). An exception the function throws passes unchanged.
     *
     * @param options the settings
     * @param clock   the clock for expiry and cleanup
     * @return the sessions
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the cookie function returns {@code null}, a builder for
     *                                  another name or value, a cookie with {@code Max-Age=0}, or a
     *                                  cookie that {@link ResponseCookie.Builder#build()} rejects
     */
    public static Sessions inMemory(SessionOptions options, InstantSource clock) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(clock, "clock");
        return new Sessions(options, clock);
    }

    /**
     * The session filter. Register it on the router ({@code Router.builder().filter(sessions.filter())}),
     * once, before the filters that use sessions; see the class documentation.
     *
     * @return the filter, always the same instance
     */
    public Filter filter() {
        return filter;
    }

    /**
     * The number of sessions in memory, expired ones that have not been removed yet included.
     *
     * @return the number, at most {@link SessionOptions#maxSessions}
     */
    public int size() {
        return count.get();
    }

    /**
     * The request's session, if it has a live one; never creates one. The counterpart of
     * {@code request.getSession(false)}.
     *
     * @param request the request, as a handler or a filter inside the session filter gets it
     * @return the session, or empty
     * @throws IllegalStateException if the session filter has not run for the request (500)
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static Optional<Session> find(WebRequest request) {
        return state(request).find();
    }

    /**
     * The request's live session, or a new one: the counterpart of {@code request.getSession()}.
     * A new session gets a new id, and the response sets the cookie.
     *
     * @param request the request, as a handler or a filter inside the session filter gets it
     * @return the session
     * @throws WebException          503 if a new session is needed and the limit is reached
     *                               (after expired sessions are removed)
     * @throws IllegalStateException if the session filter has not run for the request (500), or
     *                               if a session must be created after the response was built
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static Session getOrCreate(WebRequest request) {
        return state(request).getOrCreate();
    }

    /**
     * Gives the request's session a new id, the counterpart of {@code request.changeSessionId()}
     * and the protection against session fixation: call it when the session changes privilege,
     * such as at login. A live session keeps its attributes and gets a new id atomically: the
     * old id stops working at once, and the response sets the cookie with the new one. Without
     * a session a new, empty one is created (as {@link #getOrCreate}).
     *
     * @param request the request, as a handler or a filter inside the session filter gets it
     * @return the session, with its new id (the same object the request's {@link #find} gives)
     * @throws WebException          503 if the request has no session and the limit is reached
     * @throws IllegalStateException if the session filter has not run for the request (500), or
     *                               if it is called after the response was built
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static Session renew(WebRequest request) {
        return state(request).renew();
    }

    /**
     * Ends the request's session, the counterpart of {@code session.invalidate()}: the session
     * and all its attributes are removed, every {@link Session} object for it sees it as ended,
     * and the response removes the cookie ({@code Max-Age=0}). Without a session nothing
     * happens and the response gets no {@code Set-Cookie}.
     *
     * @param request the request, as a handler or a filter inside the session filter gets it
     * @throws IllegalStateException if the session filter has not run for the request (500)
     * @throws NullPointerException  if {@code request} is {@code null}
     */
    public static void invalidate(WebRequest request) {
        state(request).invalidate();
    }

    /**
     * {@code Sessions[n sessions, max m, idle PT30M, cookie SESSION]}: never an id.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "Sessions[" + count.get() + " sessions, max " + options.maxSessions() + ", idle "
                + options.idleTimeout() + ", cookie " + options.cookieName() + "]";
    }

    private static SessionState state(WebRequest request) {
        Objects.requireNonNull(request, "request");
        return request.attribute(STATE).orElseThrow(() -> new IllegalStateException(MISSING_FILTER));
    }

    /** The session filter; see the class documentation. */
    private Response apply(WebRequest request, Filter.Chain next) throws Exception {
        if (request.attribute(STATE).isPresent()) {
            throw new IllegalStateException("the session filter ran twice for one request: register one"
                    + " Sessions.filter(), once, on the router");
        }
        var now = clock.instant();
        sweepIfDue(now);
        var state = new SessionState(this, lookup(request.cookieAll(options.cookieName()), now), isHttps(request));
        Response response;
        try {
            response = next.proceed(request.withAttribute(STATE, state));
        } catch (Throwable failure) {
            state.abandon();
            throw failure;
        }
        return state.finish(response);
    }

    /** The first value that names a live session, used at {@code now}; {@code null} if none does. */
    private Session lookup(List<String> values, Instant now) {
        for (var value : values) {
            if (!isWellFormed(value)) {
                continue;
            }
            var key = keyOf(value);
            var entry = store.get(key);
            if (entry != null) {
                var session = entry.use(key, now);
                if (session != null) {
                    return session;
                }
            }
        }
        return null;
    }

    /** Whether {@code value} has the form of an id: 43 characters from {@code [A-Za-z0-9_-]}. */
    static boolean isWellFormed(String value) {
        if (value.length() != ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < ID_LENGTH; i++) {
            char c = value.charAt(i);
            boolean base64url = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-'
                    || c == '_';
            if (!base64url) {
                return false;
            }
        }
        return true;
    }

    /** Removes every expired session if a minute has passed since the last time (or the clock went back). */
    private void sweepIfDue(Instant now) {
        var due = nextSweep.get();
        boolean wentBack = now.isBefore(due.minus(SWEEP_INTERVAL));
        if ((!now.isBefore(due) || wentBack) && nextSweep.compareAndSet(due, now.plus(SWEEP_INTERVAL))) {
            sweep(now);
        }
    }

    /**
     * Removes every session that has expired at {@code now}, and notes when the next one can
     * expire: the earliest expiry of those left, or {@code now + idleTimeout} for those created
     * from now on.
     */
    private void sweep(Instant now) {
        sweeps.incrementAndGet();
        var earliest = now.plus(options.idleTimeout());
        for (var entry : store.values()) {
            var expiry = entry.expireIfDue(now);
            if (expiry != null && expiry.isBefore(earliest)) {
                earliest = expiry;
            }
        }
        nothingExpiresBefore.set(earliest);
    }

    /**
     * A new session, stored, with its id.
     *
     * @throws WebException 503 if the limit is reached after expired sessions were removed
     */
    Issued create() {
        var now = clock.instant();
        if (!reserve()) {
            // the cleanup first; skipped only when no session can have expired since the last one,
            // so that a flood of refused requests does not walk the whole store each time
            if (!now.isBefore(nothingExpiresBefore.get())) {
                sweep(now);
            }
            if (!reserve()) {
                throw new WebException(503, "The server cannot take more sessions right now.");
            }
        }
        try {
            while (true) {
                var id = newId();
                var key = keyOf(id);
                var entry = new SessionEntry(this, key, now);
                if (store.putIfAbsent(key, entry) == null) {
                    return new Issued(entry.firstView(), id);
                }
            }
        } catch (RuntimeException | Error e) {
            count.decrementAndGet();
            throw e;
        }
    }

    /** Counts one more session if the limit allows it. */
    private boolean reserve() {
        while (true) {
            int n = count.get();
            if (n >= options.maxSessions()) {
                return false;
            }
            if (count.compareAndSet(n, n + 1)) {
                return true;
            }
        }
    }

    /** Stores {@code entry} under {@code key} if the key is free (a renew); whether it did. */
    boolean claim(Key key, SessionEntry entry) {
        return store.putIfAbsent(key, entry) == null;
    }

    /** Removes {@code key} if it maps to {@code entry}, without changing the count (the old key of a renew). */
    void unlink(Key key, SessionEntry entry) {
        store.remove(key, entry);
    }

    /** Removes an ended {@code entry} stored under {@code key}, and counts it out. */
    void release(Key key, SessionEntry entry) {
        if (store.remove(key, entry)) {
            count.decrementAndGet();
        }
    }

    /** A new id: 32 bytes from this instance's {@link SecureRandom}, Base64url without padding. */
    String newId() {
        var bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        var id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Arrays.fill(bytes, (byte) 0);
        return id;
    }

    /** The store's key for {@code id}: the SHA-256 of its ASCII bytes. */
    static Key keyOf(String id) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e); // every JDK has it
        }
        var bytes = id.getBytes(StandardCharsets.US_ASCII);
        var digest = sha256.digest(bytes);
        var buffer = ByteBuffer.wrap(digest);
        var key = new Key(buffer.getLong(), buffer.getLong(), buffer.getLong(), buffer.getLong());
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(digest, (byte) 0);
        return key;
    }

    Instant now() {
        return clock.instant();
    }

    /** How many cleanups ran, for tests. */
    int sweeps() {
        return sweeps.get();
    }

    Duration idleTimeout() {
        return options.idleTimeout();
    }

    /** Whether the scheme of the request the session filter got is {@code https}: then the cookie is {@code Secure}. */
    private static boolean isHttps(WebRequest request) {
        return request.scheme().equals("https");
    }

    /**
     * The {@code Set-Cookie} for {@code id}: the template's attributes with the id as value, and
     * {@code Secure} also when {@code https}.
     */
    ResponseCookie sessionCookie(String id, boolean https) {
        return attributes(ResponseCookie.of(options.cookieName(), id), template).secure(template.secure() || https)
                .build();
    }

    /** The {@code Set-Cookie} that removes the session cookie, with {@code Secure} also when {@code https}. */
    ResponseCookie removalCookie(boolean https) {
        return https ? secureRemoval : removal;
    }

    /** Applies the options' cookie function once, to a stand-in id, and checks the result. */
    private static ResponseCookie template(SessionOptions options) {
        var builder = options.cookie().apply(ResponseCookie.of(options.cookieName(), PLACEHOLDER));
        if (builder == null) {
            throw new IllegalArgumentException("the session cookie function of SessionOptions returned null");
        }
        ResponseCookie cookie;
        try {
            cookie = builder.build();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the session cookie function of SessionOptions gives an invalid cookie: "
                    + e.getMessage(), e);
        }
        if (!cookie.name().equals(options.cookieName()) || !cookie.value().equals(PLACEHOLDER)) {
            throw new IllegalArgumentException("the session cookie function of SessionOptions must return the builder"
                    + " it gets, with attributes changed (it returned one for the cookie " + Text.quote(cookie.name())
                    + ")");
        }
        if (cookie.maxAge().filter(Duration::isZero).isPresent()) {
            throw new IllegalArgumentException("the session cookie function of SessionOptions sets Max-Age=0, which"
                    + " removes the cookie at once; leave maxAge out for a session cookie");
        }
        return cookie;
    }

    /** {@code builder} with every attribute of {@code template} (not its name or value). */
    private static ResponseCookie.Builder attributes(ResponseCookie.Builder builder, ResponseCookie template) {
        var result = builder.path(template.path()).secure(template.secure()).httpOnly(template.httpOnly())
                .sameSite(template.sameSite());
        if (template.domain().isPresent()) {
            result = result.domain(template.domain().get());
        }
        if (template.maxAge().isPresent()) {
            result = result.maxAge(template.maxAge().get());
        }
        return result;
    }
}
