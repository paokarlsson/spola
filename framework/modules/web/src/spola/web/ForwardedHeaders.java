package spola.web;

import java.lang.System.Logger.Level;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import spola.http.Request;
import spola.http.Response;

/**
 * The filter that gives a request the client's scheme and address behind a reverse proxy, the
 * counterpart of Spring Boot's {@code server.forward-headers-strategy=native} (Tomcat's
 * {@code RemoteIpValve}): after it, {@link WebRequest#scheme()} and
 * {@link WebRequest#clientAddress()} are what {@code request.getScheme()}, {@code isSecure()} and
 * {@code getRemoteAddr()} give in Spring. Thread-safe; its only state is whether it has logged
 * the warning about a sender it does not trust (below).
 *
 * <pre>{@code
 * var router = Router.builder()
 *         .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))  // first
 *         .get("/api/whoami", request -> Response.text(200,
 *                 request.scheme() + " " + request.clientAddress().getHostAddress()))
 *         .build();
 * // behind Caddy: "https 203.0.113.7"; directly: "http 127.0.0.1"
 * }</pre>
 *
 * <h2>Only from a trusted sender</h2>
 * The filter asks {@code proxies.trusts(request.http().remoteAddress().getAddress())}, about the
 * direct sender of the TCP connection. If the answer is {@code false}, the request goes on
 * unchanged and the values of the headers are not read at all (only whether they are there, for
 * the warning below): a sender that is not trusted can neither change the values nor cause a 400
 * with the headers. The filter always works from
 * {@code request.http()} (the sender and the headers), never from the request's current
 * {@code scheme()} or {@code clientAddress()}, so two of these filters in one chain give the
 * same result as one.
 *
 * <h2>A warning, once, for a sender that is not trusted</h2>
 * The first time a sender that {@code proxies} does not trust sends {@code X-Forwarded-Proto} or
 * {@code X-Forwarded-For}, the filter logs one line on {@link System.Logger.Level#WARNING} to the
 * {@link System.Logger} named {@code spola.web}:
 *
 * <pre>{@code
 * X-Forwarded-Proto or X-Forwarded-For from 172.18.0.6 ignored: ForwardedHeaders does not trust
 * this sender, ...
 * }</pre>
 *
 * The line names the sender's address, as {@link TrustedProxies#of} takes it (IPv6 after RFC
 * 5952, without a zone), and never a header's value. Each filter logs it once, not per request:
 * after the first line such requests pass on without one, and nothing else changes. It usually
 * means that {@code proxies} does not match the proxy, such as
 * {@code TrustedProxies.of("172.18.0.5")} after the proxy's container got a new address, or a
 * proxy that reaches the application over IPv6. The request then keeps the connection's scheme,
 * {@code http} behind a TLS proxy, and the session and CSRF cookies get {@code Secure} only when
 * their cookie function sets it: an application reached only over https sets {@code secure(true)}
 * on both, so that {@code Secure} does not depend on the proxy's address. The first sender that is not
 * trusted takes the line, also a client that reaches the application's port directly with
 * headers of its own; publish only the proxy's port.
 *
 * <h2>{@code X-Forwarded-Proto}</h2>
 * Without the field the connection's scheme stays. Otherwise there must be exactly one field,
 * and its value, without spaces and tabs around it, must be {@code http} or {@code https} in any
 * ASCII case; the scheme is the value in lower case.
 *
 * <h2>{@code X-Forwarded-For}, from the right</h2>
 * Every field is read, in order, as one list (RFC 9110 §5.3) split at {@code ,}; spaces and
 * tabs around each element are removed and empty elements are skipped (RFC 9110 §5.6.1).
 * Without the field, or without any element, the client is the sender. Otherwise the list is
 * walked from the right, as {@code RemoteIpValve} does:
 * <ul>
 *   <li>an element that is not a strict IP literal (below) is a 400;</li>
 *   <li>an address that {@code proxies} trusts is skipped (a proxy in the chain);</li>
 *   <li>the first address that {@code proxies} does not trust is the client, and the walk stops
 *       there: elements to its left are never read, whatever they hold.</li>
 * </ul>
 * If {@code proxies} trusts every address, the client is the leftmost one. The same
 * {@link TrustedProxies} decides about the sender and the elements, like {@code internalProxies}
 * in {@code RemoteIpValve}. A proxy that appends the address it sees to the right (such as
 * nginx's {@code $proxy_add_x_forwarded_for}) puts what the client sent itself to the left of
 * the client's address, where it is never read. A client whose own address {@code proxies}
 * trusts is skipped like a proxy, so what it wrote decides: trust networks that only proxies
 * are on (see {@link TrustedProxies#privateNetworks()}).
 *
 * <h2>IP literals</h2>
 * An element is an IPv4 literal of four decimal parts (1 to 3 ASCII digits, 0 to 255, no leading
 * zero except {@code 0} itself) or an IPv6 literal in the text form of RFC 4291 §2.2 (groups of
 * 1 to 4 hex digits in either case, at most one {@code ::}, optionally an IPv4 part instead of
 * the last two groups). Host names ({@code unknown}, {@code example.com}), ports
 * ({@code 203.0.113.7:8080}), brackets ({@code [2001:db8::1]}), zones ({@code fe80::1%1}),
 * short or numeric IPv4 forms ({@code 127.1}, {@code 2130706433}, {@code 010.0.0.1}) and
 * whitespace inside an element are not literals. Only a checked literal becomes an address,
 * with {@link InetAddress#ofLiteral}: no name is ever looked up. An IPv4-mapped literal
 * ({@code ::ffff:203.0.113.7}) becomes an {@code Inet4Address}, as the JDK makes it, and is
 * tested as IPv4. See {@link TrustedProxies#of} for the same rules on networks.
 *
 * <h2>400</h2>
 * From a trusted sender, these are a {@link WebException} 400 (a problem response, through the
 * router's exception mappers): more than one {@code X-Forwarded-Proto} field, or a value other
 * than {@code http} or {@code https} (a list such as {@code https, http}, an empty value,
 * {@code ftp}, {@code wss}, {@code https:}); and an element of {@code X-Forwarded-For} that the
 * walk reaches and that is not an IP literal. Neither the handler nor any filter after this one
 * runs. The detail names the header but never holds its value, and the filter logs nothing.
 * Why 400 and not ignore the element: the walk reaches an element only when everything to its
 * right came from trusted proxies, so a trusted proxy sent something broken. Falling back to the
 * sender would make the client look like the proxy, an internal address, which a rule that lets
 * internal addresses in would let in. The 400 fails closed and shows at once. A client on a
 * trusted network that sends garbage gets 400 too.
 *
 * <h2>The order in the chain</h2>
 * Register this filter <b>first</b>, on the router, so that it is the outermost filter: then
 * every filter after it (such as {@code sessions.filter()} and a security filter), every group's
 * filters and the handler see the client's scheme and address, also on requests that a filter
 * passes on with {@link WebRequest#withAttribute} and on the router's own 400, 404 and 405.
 * A filter registered before it sees the connection's values.
 *
 * <h2>{@code http()} describes the connection</h2>
 * Only this filter can change {@code scheme()} and {@code clientAddress()} (there is no public
 * way to), so a value that differs from the connection's always comes from a trusted proxy.
 * {@link WebRequest#http()} does not change: {@code http().scheme()} and
 * {@code http().remoteAddress()} stay the connection's, that is the proxy's.
 *
 * <p>Only {@code X-Forwarded-Proto} and {@code X-Forwarded-For} are read; {@code Forwarded}
 * (RFC 7239), {@code X-Forwarded-Host}, {@code X-Forwarded-Port} and {@code X-Forwarded-Prefix}
 * are not, so {@code http().host()} is the {@code Host} the proxy sends. A proxy that passes the
 * client's own {@code X-Forwarded-*} on without replacing or appending to them lets the client
 * decide the values.
 */
public final class ForwardedHeaders implements Filter {

    /** The header with the client's scheme. */
    static final String PROTO = "X-Forwarded-Proto";

    /** The header with the client's address and the proxies'. */
    static final String FOR = "X-Forwarded-For";

    private static final String INVALID_PROTO = "The X-Forwarded-Proto header from a trusted proxy is not one field"
            + " with a valid scheme.";

    private static final String INVALID_FOR = "The X-Forwarded-For header from a trusted proxy has an element that is"
            + " not an IP address.";

    private final TrustedProxies proxies;

    private final System.Logger log;

    /** Whether this filter has logged the warning about a sender that it does not trust. */
    private final AtomicBoolean warned = new AtomicBoolean();

    private ForwardedHeaders(TrustedProxies proxies, System.Logger log) {
        this.proxies = proxies;
        this.log = log;
    }

    /**
     * The filter, believing the headers when the direct sender is one of {@code proxies}.
     *
     * @param proxies the trusted proxies, such as {@link TrustedProxies#privateNetworks()} or
     *                {@code TrustedProxies.of("172.18.0.5")}
     * @return the filter; register it first on the router
     * @throws NullPointerException if {@code proxies} is {@code null}
     */
    public static ForwardedHeaders trusting(TrustedProxies proxies) {
        return trusting(proxies, System.getLogger("spola.web"));
    }

    /** {@link #trusting(TrustedProxies)} that logs the warning to {@code log}; for the tests. */
    static ForwardedHeaders trusting(TrustedProxies proxies, System.Logger log) {
        return new ForwardedHeaders(Objects.requireNonNull(proxies, "proxies"), Objects.requireNonNull(log, "log"));
    }

    /** The logger of the warning: {@code spola.web} unless the tests gave another. */
    System.Logger log() {
        return log;
    }

    /**
     * Passes the request on with the client's scheme and address when its sender is trusted,
     * otherwise unchanged (and logs the warning the first time such a sender sends the headers);
     * see the class documentation.
     *
     * @param request the request
     * @param next    the rest of the chain
     * @return the response
     * @throws WebException 400 for an invalid header from a trusted sender
     * @throws Exception    what {@link TrustedProxies#trusts} or the rest of the chain throws
     */
    @Override
    public Response filter(WebRequest request, Chain next) throws Exception {
        var http = request.http();
        var sender = http.remoteAddress().getAddress();
        if (!proxies.trusts(sender)) {
            warnOnce(http, sender);
            return next.proceed(request);
        }
        var scheme = scheme(http);
        var client = client(http.headers().all(FOR), sender);
        return next.proceed(request.withClient(scheme, client));
    }

    /**
     * Logs the warning the first time a sender that is not trusted sends one of the headers. The
     * line names the sender's address and never a value; the values are not even read.
     */
    private void warnOnce(Request http, InetAddress sender) {
        if (warned.get()) {
            return; // said already: not even the names are looked at
        }
        boolean sent = http.headers().contains(PROTO) || http.headers().contains(FOR);
        if (sent && warned.compareAndSet(false, true)) {
            log.log(Level.WARNING, warning(sender));
        }
    }

    /**
     * The warning's text: only the sender's address, never a value from the request. The fixed
     * text must not contain {@code https}: the tests send {@code X-Forwarded-Proto: https} and
     * check that the line does not contain the value.
     */
    static String warning(InetAddress sender) {
        return PROTO + " or " + FOR + " from " + IpLiteral.format(sender.getAddress())
                + " ignored: ForwardedHeaders does not trust this sender, so the request keeps the connection's"
                + " scheme and address (behind a TLS proxy the scheme is then http, and a cookie that follows the"
                + " scheme gets no Secure). If the sender is the proxy, trust its address in TrustedProxies."
                + " Logged once per filter.";
    }

    /** The scheme from {@code X-Forwarded-Proto}, or the connection's without it. */
    private static String scheme(Request http) {
        var fields = http.headers().all(PROTO);
        if (fields.isEmpty()) {
            return http.scheme();
        }
        if (fields.size() == 1) {
            var value = fields.getFirst();
            int from = start(value, 0, value.length());
            int to = end(value, from, value.length());
            if (asciiEqualsIgnoreCase(value, from, to, "https")) {
                return "https";
            }
            if (asciiEqualsIgnoreCase(value, from, to, "http")) {
                return "http";
            }
        }
        throw new WebException(400, INVALID_PROTO);
    }

    /** The client from {@code X-Forwarded-For}, walked from the right; {@code sender} without elements. */
    private InetAddress client(List<String> fields, InetAddress sender) {
        var client = sender;
        for (int f = fields.size() - 1; f >= 0; f--) {
            var field = fields.get(f);
            int limit = field.length(); // the end of the element; -1 when the field is done
            while (limit >= 0) {
                int comma = field.lastIndexOf(',', limit - 1);
                int from = start(field, comma + 1, limit);
                int to = end(field, from, limit);
                limit = comma;
                if (from == to) {
                    continue; // an empty element
                }
                var address = IpLiteral.address(field.substring(from, to));
                if (address == null) {
                    throw new WebException(400, INVALID_FOR);
                }
                client = address;
                if (!proxies.trusts(address)) {
                    return address; // the client; nothing to its left is read
                }
            }
        }
        return client; // the sender, or the leftmost element when every one is trusted
    }

    /** The first index in {@code [from, to)} that is not SP or HTAB. */
    private static int start(String text, int from, int to) {
        while (from < to && isSpace(text.charAt(from))) {
            from++;
        }
        return from;
    }

    /** One past the last index in {@code [from, to)} that is not SP or HTAB. */
    private static int end(String text, int from, int to) {
        while (to > from && isSpace(text.charAt(to - 1))) {
            to--;
        }
        return to;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t';
    }

    /** Whether {@code text[from, to)} is {@code lower} (ASCII lower case) without regard to ASCII case. */
    private static boolean asciiEqualsIgnoreCase(String text, int from, int to, String lower) {
        if (to - from != lower.length()) {
            return false;
        }
        for (int i = 0; i < lower.length(); i++) {
            char c = text.charAt(from + i);
            char folded = c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
            if (folded != lower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** {@code ForwardedHeaders[<the proxies>]}. */
    @Override
    public String toString() {
        return "ForwardedHeaders[" + proxies + "]";
    }
}
