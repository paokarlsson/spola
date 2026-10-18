package spola.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.HttpServer;
import spola.http.Response;

/**
 * Shared cases and helpers for the tests of {@link ForwardedHeaders} and {@link TrustedProxies}:
 * the invalid values of criteria 5 and 6, a router that answers with what it sees, and requests
 * with exact header lines over a raw socket or through {@link HttpClient}.
 */
final class ForwardedSupport {

    private ForwardedSupport() {
    }

    /** The proxies of criterion 4: the sender 127.0.0.1 is trusted, and so are 10/8 and fd00::/8. */
    static final TrustedProxies TRUSTING = TrustedProxies.of("127.0.0.0/8", "10.0.0.0/8", "fd00::/8");

    /** Proxies that do not include the sender 127.0.0.1 (criterion 3). */
    static final TrustedProxies NOT_TRUSTING = TrustedProxies.of("10.0.0.0/8");

    /**
     * A request's header lines, for a table.
     *
     * @param name  what the case is
     * @param lines the header lines, such as {@code X-Forwarded-For: unknown}
     */
    record Case(String name, List<String> lines) {
        Case(String name, String... lines) {
            this(name, List.of(lines));
        }

        /** The values of the lines, after the colon and the space. */
        List<String> values() {
            return lines.stream().map(line -> line.substring(line.indexOf(':') + 1).strip()).toList();
        }

        /** Whether every byte can go through {@link HttpClient} (it sends header values as ASCII). */
        boolean ascii() {
            return lines.stream().allMatch(line -> line.chars().allMatch(c -> c < 0x80));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Case xff(String value) {
        return new Case("X-Forwarded-For: " + visible(value), "X-Forwarded-For: " + value);
    }

    static Case xfp(String value) {
        return new Case("X-Forwarded-Proto: " + visible(value), "X-Forwarded-Proto: " + value);
    }

    /** {@code "text"} with every char outside printable ASCII as {@code \\uXXXX}, for test names. */
    static String visible(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            out.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return out.append('"').toString();
    }

    /** Criterion 5: elements of {@code X-Forwarded-For} that the walk reaches and that are not IP literals. */
    static final List<Case> INVALID_FOR = List.of(
            xff("unknown"),
            xff("example.com"),
            xff("localhost"),
            xff("_hidden"),
            xff("203.0.113.7:8080"),
            xff("[2001:db8::1]"),
            xff("[2001:db8::1]:8080"),
            xff("fe80::1%1"),
            xff("fe80::1%eth0"),
            xff("127.1"),
            xff("2130706433"),
            xff("1.2.3"),
            xff("010.0.0.1"),
            xff("1.2.3.04"),
            xff("0x7f.0.0.1"),
            xff("256.1.1.1"),
            xff("1.2.3.4.5"),
            xff("1.2.3."),
            xff("203.0.113.7 10.0.0.1"),
            xff("203.0.113.7\t10.0.0.1"),
            xff("2001:db8::1::2"),
            xff("1:2:3:4:5:6:7:8:9"),
            xff("1:2:3:4:5:6:7::8"),
            xff("12345::1"),
            xff("2001:db8::1:"),
            xff(":2001:db8::1"),
            xff("::ffff:010.0.0.1"),
            xff("1:2:3:4:5:6:7:1.2.3.4"),
            xff("skräp, 10.0.0.2"),
            xff("203.0.113.7, unknown"),
            xff("10.0.0.2, 10.0.0.3, nope"),
            xff("203.0.113.7\u00a0"),
            xff("\u00a0203.0.113.7, 10.0.0.2"),
            new Case("two fields, the right one invalid", "X-Forwarded-For: 203.0.113.7", "X-Forwarded-For: unknown"));

    /** Criterion 6: {@code X-Forwarded-Proto} values from a trusted sender that are a 400. */
    static final List<Case> INVALID_PROTO = List.of(
            new Case("two fields: https and http", "X-Forwarded-Proto: https", "X-Forwarded-Proto: http"),
            new Case("two fields: https twice", "X-Forwarded-Proto: https", "X-Forwarded-Proto: https"),
            xfp("https, http"),
            xfp(""),
            xfp("ftp"),
            xfp("wss"),
            xfp("https:"),
            xfp("httpss"),
            xfp("htt"),
            xfp("\"https\""),
            xfp("https;"),
            xfp("http s"),
            xfp("https\u00a0"));

    /**
     * A router whose filter chain is {@code ForwardedHeaders.trusting(proxies)} and whose handler
     * answers {@code GET /who} with what it sees: the scheme, the client's address as text and its
     * class, and the connection's scheme. It counts the handler's calls and logs to {@code log}.
     */
    static Router whoRouter(TrustedProxies proxies, AtomicInteger calls, Support.CapturedLog log) {
        return Router.builder()
                .filter(ForwardedHeaders.trusting(proxies))
                .get("/who", request -> {
                    calls.incrementAndGet();
                    return Response.text(200, describe(request));
                })
                .log(log)
                .build();
    }

    /** {@code scheme address class} of what the request says about the client. */
    static String describe(WebRequest request) {
        var address = request.clientAddress();
        return request.scheme() + " " + address.getHostAddress() + " " + address.getClass().getSimpleName();
    }

    /** One {@code GET} with the case's header lines over a raw socket (exact bytes, ISO-8859-1). */
    static Support.Reply send(HttpServer server, String target, Case header) throws Exception {
        return Support.send(server.port(), "GET", target, header.lines().toArray(String[]::new));
    }

    /** One {@code GET} with the case's header lines through {@link HttpClient}. */
    static HttpResponse<String> call(HttpClient client, HttpServer server, String target, Case header)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + target))
                .timeout(Duration.ofSeconds(10));
        for (var line : header.lines()) {
            int colon = line.indexOf(':');
            builder.header(line.substring(0, colon), line.substring(colon + 1).strip());
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Every case, joined. */
    @SafeVarargs
    static List<Case> all(List<Case>... lists) {
        var all = new ArrayList<Case>();
        for (var list : lists) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }
}
