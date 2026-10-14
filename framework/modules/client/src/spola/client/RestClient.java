package spola.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.net.ssl.SSLException;
import spola.client.HeaderRules.Header;
import spola.json.JsonLimits;

/**
 * A client for a JSON API over HTTP: the counterpart of Spring's {@code RestClient}, on top of
 * {@link HttpClient}. It is immutable and thread-safe; share one per API.
 *
 * <pre>{@code
 * var anthropic = RestClient.builder()
 *         .baseUri(URI.create("https://api.anthropic.com"))
 *         .defaultHeader("x-api-key", apiKey)
 *         .defaultHeader("anthropic-version", "2023-06-01")
 *         .timeout(Duration.ofSeconds(60))
 *         .build();
 *
 * MessagesResponse response = anthropic.post("/v1/messages")
 *         .json(request, MessagesRequest.CODEC)       // Content-Type: application/json
 *         .retrieve(MessagesResponse.CODEC);           // 2xx: the decoded body
 * }</pre>
 *
 * <p>{@link #get}, {@link #post}, {@link #put}, {@link #patch} and {@link #delete} give a
 * {@link RestRequest}; its {@code retrieve...} methods send it, block the calling thread (which
 * is fine on a virtual thread) and either return the 2xx result or throw a
 * {@link RestClientException}. A client is {@link AutoCloseable}: {@link #close()} closes the
 * underlying {@link HttpClient}.
 *
 * <h2>Requests</h2>
 * <ul>
 *   <li><b>URI.</b> The path is appended to the base URI's path <em>as text</em>:
 *       {@code https://h/api} and {@code /v1/x} give {@code https://h/api/v1/x} (unlike
 *       {@link URI#resolve}). Nothing is encoded and there are no templates, so the path is sent
 *       as it stands, and a value from outside must be percent-encoded by the caller. A request
 *       always goes to the base's scheme, host and port: {@code //other.example/x} is a path
 *       on the base's host.</li>
 *   <li><b>Headers.</b> The default headers go with every request; a request's
 *       {@link RestRequest#header header} replaces a default with the same name in any case.
 *       {@code Accept: application/json} is sent when neither has an {@code Accept}.
 *       {@link RestRequest#json json(...)} sets {@code Content-Type: application/json}; without
 *       a body there is no {@code Content-Type}. {@code java.net.http} adds {@code Host},
 *       {@code Content-Length} and {@code User-Agent} (unless one is given).</li>
 *   <li><b>HTTP/1.1</b> only, on connections that {@link HttpClient} keeps alive and reuses.</li>
 *   <li><b>Redirects are never followed</b>: a 3xx is a {@link RestClientResponseException}, so
 *       the headers with the key never go to another host.</li>
 *   <li><b>No proxy and no cookies.</b> The client connects directly (the JVM's proxy settings,
 *       such as {@code https.proxyHost}, are not used) and keeps no cookies. TLS uses the JDK's
 *       default {@link javax.net.ssl.SSLContext} (its trust store, host name verification); a
 *       client cannot be given another one.</li>
 * </ul>
 *
 * <h2>Responses</h2>
 * <ul>
 *   <li><b>2xx:</b> the body is read whole, up to {@link Builder#maxResponseSize(int)}, and
 *       parsed as JSON (strict RFC 8259 in UTF-8) <em>whatever its {@code Content-Type}</em>
 *       (Spring picks a converter by {@code Content-Type}). An empty body is an error for
 *       {@code retrieve} and {@code retrieveJson}.</li>
 *   <li><b>Anything else:</b> at most the first {@value #MAX_ERROR_BODY} bytes of the body are
 *       read for {@link RestClientResponseException#bodyExcerpt()}; then the response is closed
 *       without waiting for the rest.</li>
 *   <li>A {@code Content-Length} over {@code maxResponseSize} fails without reading the body; a
 *       longer body stops being read at the limit, also when it is chunked or never ends.</li>
 * </ul>
 *
 * <h2>Timeouts</h2>
 * {@link Builder#timeout(Duration)} (default 30 s) is a deadline for the whole call: connecting,
 * TLS, sending, the status line, the headers <em>and the whole body</em>. When it runs out the
 * request is aborted, the connection closed and a {@link RestClientTransportException} with
 * {@link RestClientTransportException#timedOut()} thrown. {@link Builder#connectTimeout(Duration)}
 * (default 10 s) also limits connecting. The same timeout applies to every call of a client.
 *
 * <h2>Interrupts</h2>
 * A thread interrupted while it waits aborts its request, gets its interrupt flag set again and
 * a {@link RestClientTransportException} whose cause is the {@link InterruptedException}. A thread
 * that is already interrupted sends nothing.
 *
 * <h2>Secrets</h2>
 * No header value, query or request body appears in any message, {@code toString()} or cause that
 * the module creates; {@link #toString()} shows the names of the default headers only. The module
 * logs nothing.
 *
 * <h2>Deviations from Spring's {@code RestClient}</h2>
 * <ul>
 *   <li>The exceptions are a sealed {@link RestClientException} with exactly two cases;
 *       {@code status()} is on {@link RestClientResponseException} only, and there is no subclass
 *       per status.</li>
 *   <li>{@code Content-Type} does not decide how a response is read: every 2xx body is JSON.</li>
 *   <li>Redirects are never followed; HTTP/1.1 only.</li>
 *   <li>The path is sent as it stands: no templates ({@code {id}}) and no encoding.</li>
 *   <li>No cookies, no proxy and no {@code SSLContext} of one's own.</li>
 *   <li>The request's {@code Content-Type} cannot be set: {@code json(...)} sends
 *       {@code application/json}, and only JSON bodies can be sent.</li>
 *   <li>The timeout is a deadline for the whole call (Spring's read timeout is per read), the
 *       same for every call of a client; a body has a size limit; an error body is read only up
 *       to its first {@value #MAX_ERROR_BODY} bytes; an empty 2xx body is an error for
 *       {@code retrieve}, not {@code null}; the headers of a 2xx response are not available.</li>
 * </ul>
 */
public final class RestClient implements AutoCloseable {

    /** The default {@link Builder#timeout(Duration) timeout} for a whole call: 30 s. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** The default {@link Builder#connectTimeout(Duration) connect timeout}: 10 s. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * The default {@link Builder#maxResponseSize(int) limit} on a response body: 4 MiB, the
     * {@code maxInputSize} of {@link JsonLimits#DEFAULT}.
     */
    public static final int DEFAULT_MAX_RESPONSE_SIZE = 4 << 20;

    /** How much of a body outside 2xx is read for the excerpt: the first 8 KiB. */
    public static final int MAX_ERROR_BODY = 8 << 10;

    private final HttpClient http;
    private final URI baseUri;
    private final String base;
    private final List<Header> defaultHeaders;
    private final Duration timeout;
    private final Duration connectTimeout;
    private final int maxResponseSize;
    private final JsonLimits jsonLimits;
    // a call starts under the read lock, close() marks the client closed under the write lock: a
    // call either starts before HttpClient.close() (which then waits for it) or sees the mark
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private volatile boolean closed;

    private RestClient(URI baseUri, List<Header> defaultHeaders, Duration timeout, Duration connectTimeout,
            int maxResponseSize) {
        this.baseUri = baseUri;
        this.base = Uris.base(baseUri);
        this.defaultHeaders = List.copyOf(defaultHeaders);
        this.timeout = timeout;
        this.connectTimeout = connectTimeout;
        this.maxResponseSize = maxResponseSize;
        this.jsonLimits = JsonLimits.DEFAULT.withMaxInputSize(maxResponseSize);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofNanos(nanos(connectTimeout)))
                .build();
    }

    /**
     * A new builder; {@link Builder#baseUri(URI)} is required.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A {@code GET} request.
     *
     * @param path the path after the base URI's path, starting with {@code /}, with an optional
     *             query; sent as it stands (see {@link RestRequest}), except that
     *             {@code java.net.http} drops an empty query ({@code /x?} is sent as {@code /x})
     * @return the request
     * @throws IllegalArgumentException if the path does not start with {@code /}, has a fragment
     *                                  or has a character that is not allowed in a URI path or
     *                                  query (RFC 3986), such as a space
     * @throws NullPointerException     if {@code path} is {@code null}
     */
    public RestRequest get(String path) {
        return request("GET", path);
    }

    /**
     * A {@code POST} request.
     *
     * @param path the path; see {@link #get(String)}
     * @return the request
     * @throws IllegalArgumentException if the path is not valid; see {@link #get(String)}
     * @throws NullPointerException     if {@code path} is {@code null}
     */
    public RestRequest post(String path) {
        return request("POST", path);
    }

    /**
     * A {@code PUT} request.
     *
     * @param path the path; see {@link #get(String)}
     * @return the request
     * @throws IllegalArgumentException if the path is not valid; see {@link #get(String)}
     * @throws NullPointerException     if {@code path} is {@code null}
     */
    public RestRequest put(String path) {
        return request("PUT", path);
    }

    /**
     * A {@code PATCH} request.
     *
     * @param path the path; see {@link #get(String)}
     * @return the request
     * @throws IllegalArgumentException if the path is not valid; see {@link #get(String)}
     * @throws NullPointerException     if {@code path} is {@code null}
     */
    public RestRequest patch(String path) {
        return request("PATCH", path);
    }

    /**
     * A {@code DELETE} request.
     *
     * @param path the path; see {@link #get(String)}
     * @return the request
     * @throws IllegalArgumentException if the path is not valid; see {@link #get(String)}
     * @throws NullPointerException     if {@code path} is {@code null}
     */
    public RestRequest delete(String path) {
        return request("DELETE", path);
    }

    private RestRequest request(String method, String path) {
        Objects.requireNonNull(path, "path");
        var problem = Uris.pathProblem(path);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(problem.get());
        }
        URI uri;
        try {
            uri = new URI(base + path);
        } catch (URISyntaxException e) { // not expected after the check; the message would quote the query
            throw new IllegalArgumentException("the path is not a valid URI path");
        }
        return new RestRequest(this, method, uri, List.of(), null);
    }

    /**
     * Closes the underlying {@link HttpClient}. It waits for the calls in progress, each of which
     * ends within its timeout. A {@code retrieve...} call that starts once {@code close()} has
     * begun throws {@link IllegalStateException} without sending anything; a second
     * {@code close()} does nothing.
     */
    @Override
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
        } finally {
            lifecycle.writeLock().unlock();
        }
        http.close();
    }

    /**
     * The base URI, the names of the default headers (never their values), the timeouts and the
     * limit.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "RestClient[baseUri " + Uris.withoutQuery(baseUri) + ", defaultHeaders "
                + defaultHeaders.stream().map(Header::name).toList() + ", timeout " + millis(timeout)
                + " ms, connectTimeout " + millis(connectTimeout) + " ms, maxResponseSize " + maxResponseSize
                + (closed ? ", closed" : "") + "]";
    }

    List<Header> defaultHeaders() {
        return defaultHeaders;
    }

    JsonLimits jsonLimits() {
        return jsonLimits;
    }

    // ---- sending ------------------------------------------------------------------------

    /**
     * A 2xx response with its whole body.
     *
     * @param status  the status
     * @param headers the headers
     * @param body    the body, exactly
     */
    record Success(int status, HttpHeaders headers, byte[] body) {
    }

    /**
     * Sends {@code request} and returns its 2xx response with the whole body; throws for
     * anything else.
     */
    Success exchange(RestRequest request) {
        var response = send(request);
        int status = response.statusCode();
        var body = response.body();
        if (status < 200 || status > 299) {
            throw new RestClientResponseException(request.method(), request.uri(), status, response.headers(),
                    Text.excerpt(body.bytes(), body.length()), statusText(status) + notRead(body), null);
        }
        if (body.ending() == BodyReader.Ending.DECLARED_TOO_LARGE) {
            throw new RestClientResponseException(request.method(), request.uri(), status, response.headers(), "",
                    "the response body is larger than maxResponseSize" + notRead(body), null);
        }
        if (body.ending() == BodyReader.Ending.TOO_LARGE) {
            throw new RestClientResponseException(request.method(), request.uri(), status, response.headers(),
                    Text.excerpt(body.bytes(), body.length()), "the response body is larger than maxResponseSize ("
                            + maxResponseSize + " bytes); reading stopped there", null);
        }
        var bytes = body.length() == body.bytes().length ? body.bytes() : Arrays.copyOf(body.bytes(), body.length());
        return new Success(status, response.headers(), bytes);
    }

    private String notRead(BodyReader.Body body) {
        return body.ending() == BodyReader.Ending.DECLARED_TOO_LARGE
                ? " (Content-Length " + body.declared() + " is over maxResponseSize " + maxResponseSize
                        + " bytes; the body was not read)"
                : "";
    }

    private static String statusText(int status) {
        if (status >= 300 && status <= 399) {
            return "a redirect, which is not followed";
        }
        if (status >= 400 && status <= 499) {
            return "a client error";
        }
        if (status >= 500 && status <= 599) {
            return "a server error";
        }
        return "not a 2xx status";
    }

    /** Sends the request and waits for the response and its body, within the timeout. */
    private HttpResponse<BodyReader.Body> send(RestRequest request) {
        if (closed) {
            throw new IllegalStateException("the RestClient is closed");
        }
        var method = request.method();
        var uri = request.uri();
        if (Thread.currentThread().isInterrupted()) {
            throw new RestClientTransportException(method, uri, "the calling thread is interrupted; nothing was sent",
                    false, new InterruptedException("interrupted before the request was sent"));
        }
        var headers = request.effectiveHeaders();
        var builder = HttpRequest.newBuilder(uri).method(method, request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        for (var header : headers) {
            builder.header(header.name(), header.value());
        }
        var reader = new BodyReader(maxResponseSize);
        var httpRequest = builder.build();
        CompletableFuture<HttpResponse<BodyReader.Body>> pending;
        lifecycle.readLock().lock();
        try {
            if (closed) {
                throw new IllegalStateException("the RestClient is closed");
            }
            pending = http.sendAsync(httpRequest, reader); // does not block: the exchange runs on the client's threads
        } finally {
            lifecycle.readLock().unlock();
        }
        try {
            return pending.get(nanos(timeout), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            abort(pending, reader);
            throw new RestClientTransportException(method, uri, "timed out after " + millis(timeout) + " ms", true,
                    null);
        } catch (InterruptedException e) {
            abort(pending, reader);
            Thread.currentThread().interrupt();
            throw new RestClientTransportException(method, uri, "interrupted while waiting for the response", false,
                    e);
        } catch (ExecutionException | CancellationException e) {
            abort(pending, reader);
            var failure = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            throw transportFailure(method, uri, failure, secrets(request, headers));
        }
    }

    private static void abort(CompletableFuture<?> pending, BodyReader reader) {
        reader.abort();
        pending.cancel(true); // aborts the exchange and closes the connection
    }

    /** Every value the request carries that must not show: the header values, the query and the body. */
    private static List<String> secrets(RestRequest request, List<Header> headers) {
        var secrets = new ArrayList<String>();
        for (var header : headers) {
            secrets.add(header.value());
        }
        if (request.uri().getRawQuery() != null) {
            secrets.add(request.uri().getRawQuery());
        }
        if (request.body() != null) {
            secrets.add(new String(request.body(), StandardCharsets.UTF_8)); // compact JSON: one line
        }
        return secrets;
    }

    private RestClientTransportException transportFailure(String method, URI uri, Throwable failure,
            List<String> secrets) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof Error error) {
            throw error;
        }
        boolean clean = Causes.isClean(failure, secrets);
        var cause = clean ? failure : Causes.safe(failure, secrets);
        var what = switch (failure) {
            case HttpConnectTimeoutException _ -> "connect timed out after " + millis(connectTimeout) + " ms";
            case HttpTimeoutException _ -> "timed out";
            case ConnectException _ -> "could not connect";
            case SSLException _ -> "TLS failed";
            case ProtocolException _ -> "the response is not valid HTTP/1.1";
            case IOException _ -> "the connection failed";
            case CancellationException _ -> "the request was cancelled";
            default -> "the request failed";
        };
        return new RestClientTransportException(method, uri, what + " (" + Causes.describe(failure, clean) + ")",
                failure instanceof HttpTimeoutException, cause);
    }

    /** The duration in nanoseconds, or {@code Long.MAX_VALUE} (about 292 years) when it is longer. */
    static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long millis(Duration duration) {
        return nanos(duration) / 1_000_000;
    }

    // ---- builder ------------------------------------------------------------------------

    /**
     * Sets up a {@link RestClient}; the counterpart of Spring's {@code RestClient.Builder}. Every
     * setter takes its value as it is and throws only {@link NullPointerException} for
     * {@code null}; {@link #build()} checks everything at once. A builder is not thread-safe and
     * can build several clients.
     */
    public static final class Builder {

        private URI baseUri;
        private final List<Header> defaultHeaders = new ArrayList<>();
        private Duration timeout = DEFAULT_TIMEOUT;
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private int maxResponseSize = DEFAULT_MAX_RESPONSE_SIZE;

        private Builder() {
        }

        /**
         * The base URI, required: absolute, with the scheme {@code http} or {@code https} and a
         * host, without userinfo, query or fragment. Its path may be empty or a prefix such as
         * {@code /api}; one trailing {@code /} is dropped. Every request goes to this scheme, host
         * and port.
         *
         * @param baseUri the base URI, such as {@code URI.create("https://api.anthropic.com")}
         * @return this builder
         * @throws NullPointerException if {@code baseUri} is {@code null}
         */
        public Builder baseUri(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
            return this;
        }

        /**
         * A header sent with every request (a request's own {@link RestRequest#header header}
         * with the same name, in any case, replaces it). The name is a token (RFC 9110 5.6.2);
         * the value is visible ASCII, spaces and tabs, may be empty, and does not start or end
         * with a space or tab. Refused in any case: {@code Connection}, {@code Content-Length},
         * {@code Content-Type}, {@code Expect}, {@code Host}, {@code Keep-Alive},
         * {@code Proxy-Connection}, {@code TE}, {@code Trailer}, {@code Transfer-Encoding} and
         * {@code Upgrade}. The same name twice, in any case, is an error. The value never appears
         * in a message or {@code toString()}.
         *
         * @param name  the name, such as {@code x-api-key}
         * @param value the value
         * @return this builder
         * @throws NullPointerException if {@code name} or {@code value} is {@code null}
         */
        public Builder defaultHeader(String name, String value) {
            defaultHeaders.add(new Header(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value")));
            return this;
        }

        /**
         * The deadline for each whole call: connecting, TLS, sending, the status line, the
         * headers and the whole body. Default {@link RestClient#DEFAULT_TIMEOUT} (30 s). A
         * duration longer than {@code Long.MAX_VALUE} nanoseconds counts as that.
         *
         * @param timeout the timeout, positive
         * @return this builder
         * @throws NullPointerException if {@code timeout} is {@code null}
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * The limit on connecting (within {@link #timeout(Duration)}). Default
         * {@link RestClient#DEFAULT_CONNECT_TIMEOUT} (10 s).
         *
         * @param connectTimeout the connect timeout, positive
         * @return this builder
         * @throws NullPointerException if {@code connectTimeout} is {@code null}
         */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        /**
         * The limit on a response body, in bytes, for every {@code retrieve...} call. Default
         * {@link RestClient#DEFAULT_MAX_RESPONSE_SIZE} (4 MiB). A {@code Content-Length} over it
         * fails without the body being read; a longer body stops being read at the limit. JSON
         * is parsed with {@code JsonLimits.DEFAULT.withMaxInputSize(maxResponseSize)}.
         *
         * @param bytes the limit, at least 1
         * @return this builder
         */
        public Builder maxResponseSize(int bytes) {
            this.maxResponseSize = bytes;
            return this;
        }

        /**
         * Checks everything and builds the client with its own {@link HttpClient}.
         *
         * @return the client
         * @throws IllegalArgumentException with one line per problem: a missing or invalid base
         *                                  URI, an invalid or refused header, the same header
         *                                  twice, a timeout that is not positive, a
         *                                  {@code maxResponseSize} under 1. No line quotes a
         *                                  header value or the base URI.
         */
        public RestClient build() {
            var problems = new ArrayList<String>();
            if (baseUri == null) {
                problems.add("baseUri is missing");
            } else {
                problems.addAll(Uris.baseProblems(baseUri));
            }
            var valid = new ArrayList<Header>();
            for (var header : defaultHeaders) {
                var problem = HeaderRules.problem(header.name(), header.value());
                if (problem.isPresent()) {
                    problems.add("defaultHeader: " + problem.get());
                    continue;
                }
                var earlier = valid.stream().filter(h -> HeaderRules.sameName(h.name(), header.name())).findFirst();
                if (earlier.isPresent()) {
                    problems.add("defaultHeader: header " + header.name() + " is given twice (first as "
                            + earlier.get().name() + ")");
                    continue;
                }
                valid.add(header);
            }
            if (timeout.isZero() || timeout.isNegative()) {
                problems.add("timeout must be positive, was " + timeout);
            }
            if (connectTimeout.isZero() || connectTimeout.isNegative()) {
                problems.add("connectTimeout must be positive, was " + connectTimeout);
            }
            if (maxResponseSize < 1) {
                problems.add("maxResponseSize must be at least 1 byte, was " + maxResponseSize);
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", problems));
            }
            return new RestClient(baseUri, valid, timeout, connectTimeout, maxResponseSize);
        }
    }
}
