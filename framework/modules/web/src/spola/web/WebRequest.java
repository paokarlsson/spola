package spola.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import spola.http.Headers;
import spola.http.Request;
import spola.json.JsonLimits;
import spola.json.codec.JsonCodec;
import spola.validation.Validated;
import spola.validation.Validator;

/**
 * The request a {@link WebHandler} and a {@link Filter} get: the underlying
 * {@link spola.http.Request}, the decoded path, the matched route's path variables, the query
 * parameters and typed attributes. Immutable: {@link #withAttribute} returns a new request.
 *
 * <p>The path has passed the router's checks and is decoded (see {@link Router}); the raw path
 * is still in {@link #http()}. Only when the router answers 400 because the path failed those
 * checks (filters still run for that request) is {@link #path()} the raw path and
 * {@link #pathSegments()} empty.
 *
 * <p>The query is parsed on first use, at most once per request, after the
 * {@code application/x-www-form-urlencoded} rules: {@code &} separates pairs, the first
 * {@code =} separates name and value (a pair without {@code =} has an empty value), {@code +}
 * is a space, percent-decoding is strict UTF-8, empty pairs ({@code &&}) are skipped, and
 * several values for one name keep their order. Decoded control characters are allowed in
 * values (they are data). Invalid encoding or more than 1 000 pairs is a {@link WebException}
 * 400, thrown by the first method that reads the query.
 *
 * <p>The cookies ({@link #cookie(String)}, the counterpart of Spring's {@code @CookieValue}) are
 * parsed on first use too, at most once per request, after RFC 6265 §5.4: see
 * {@link #cookies()}.
 *
 * <p>The body can be read once: as JSON with {@link #readJson} (the counterpart of Spring's
 * {@code @RequestBody}), as a form with {@link #readForm()} or bound with
 * {@link #readForm(FormBinder)}, or as a stream with
 * {@link #body()} or {@link #bytes()}. Every request derived with {@link #withAttribute} shares
 * the body and that rule, and the parsed query and cookies.
 *
 * <p><b>Validation</b> (the counterpart of Spring's {@code @Valid}): {@code readJson},
 * {@code readForm} and {@code bindQuery} take an optional {@link Validator}. Mapping (or
 * binding) always comes first; the validator runs only on a value that was read, parsed and
 * mapped (or bound) without error, and its violations give the 400 of
 * {@link WebException#invalid(Validated.Invalid)}. A handler therefore never gets an invalid
 * value, and there is no {@code BindingResult}.
 *
 * <p><b>The client behind a reverse proxy.</b> {@link #scheme()} and {@link #clientAddress()}
 * are the client's scheme and address, the counterparts of Spring's {@code getScheme()} and
 * {@code getRemoteAddr()}. Without {@link ForwardedHeaders} they are the connection's,
 * {@code http().scheme()} and {@code http().remoteAddress().getAddress()}. After
 * {@code ForwardedHeaders}, for a request whose direct sender is a trusted proxy, they are what
 * the proxy's {@code X-Forwarded-Proto} and {@code X-Forwarded-For} say. Only
 * {@code ForwardedHeaders} can change them (there is no public way to), {@link #withAttribute}
 * keeps them, and {@link #http()} always describes the connection, that is the proxy's. Register
 * {@code ForwardedHeaders} first, so that every filter after it and the handler see the client's
 * values; a filter registered before it sees the connection's.
 */
public final class WebRequest {

    private final Request http;
    private final String path;
    private final List<String> segments;
    private final Route route;
    private final QueryState query;
    private final CookieState cookies;
    private final BodyState body;
    private final Map<AttributeKey<?>, Object> attributes;
    private final String scheme;
    private final InetAddress clientAddress;

    /** A request as the router makes it, with the connection's scheme and address. */
    WebRequest(Request http, String path, List<String> segments, Route route, QueryState query, CookieState cookies,
            BodyState body, Map<AttributeKey<?>, Object> attributes) {
        this(http, path, segments, route, query, cookies, body, attributes, http.scheme(),
                http.remoteAddress().getAddress());
    }

    private WebRequest(Request http, String path, List<String> segments, Route route, QueryState query,
            CookieState cookies, BodyState body, Map<AttributeKey<?>, Object> attributes, String scheme,
            InetAddress clientAddress) {
        this.http = http;
        this.path = path;
        this.segments = segments;
        this.route = route;
        this.query = query;
        this.cookies = cookies;
        this.body = body;
        this.attributes = attributes;
        this.scheme = scheme;
        this.clientAddress = clientAddress;
    }

    /**
     * The underlying request: raw path and query, version, TLS, host, and the connection it came
     * on. Behind a reverse proxy, {@code http().scheme()} and {@code http().remoteAddress()} are
     * the proxy's connection and never change; the client's are {@link #scheme()} and
     * {@link #clientAddress()}.
     *
     * @return the request
     */
    public Request http() {
        return http;
    }

    /**
     * The scheme the client used, the counterpart of Spring's {@code request.getScheme()}
     * ({@code isSecure()} is {@code scheme().equals("https")}). Without {@link ForwardedHeaders},
     * or when the direct sender is not a trusted proxy, it is {@code http().scheme()}:
     * {@code "https"} over TLS, otherwise {@code "http"}. After {@code ForwardedHeaders} and a
     * trusted sender, it is the proxy's {@code X-Forwarded-Proto} in lower case, or
     * {@code http().scheme()} without that header. See {@link ForwardedHeaders} for the rules.
     *
     * @return {@code "http"} or {@code "https"}
     */
    public String scheme() {
        return scheme;
    }

    /**
     * The client's IP address, the counterpart of Spring's {@code request.getRemoteAddr()}.
     * Without {@link ForwardedHeaders}, or when the direct sender is not a trusted proxy, it is
     * {@code http().remoteAddress().getAddress()}. After {@code ForwardedHeaders} and a trusted
     * sender, it is the client's address from the proxy's {@code X-Forwarded-For}, walked from
     * the right past the trusted proxies, or the sender's without that header. See
     * {@link ForwardedHeaders} for the rules.
     *
     * <p>No name is looked up to make it. {@code getHostAddress()} gives it as text;
     * {@code getHostName()} would ask DNS for a name.
     *
     * <p>It is where the request came from, not who sent it, and never an authorization on its
     * own: with {@link TrustedProxies#privateNetworks()} every container on those networks is
     * trusted and can write any client address into {@code X-Forwarded-For}, and Docker's
     * userland proxy can make a client from outside look like the bridge's gateway (such as
     * {@code 172.17.0.1}), a private address. Never let a request in only because its client
     * address is private or internal.
     *
     * @return the address, never {@code null}
     */
    public InetAddress clientAddress() {
        return clientAddress;
    }

    /**
     * The method, case-sensitive as sent.
     *
     * @return the method
     */
    public String method() {
        return http.method();
    }

    /**
     * The decoded path: {@code /} followed by the decoded segments joined with {@code /}
     * ({@code /a%20b} is {@code /a b}). Decoded segments never hold {@code /}, so the path is
     * unambiguous.
     *
     * @return the decoded path
     */
    public String path() {
        return path;
    }

    /**
     * The decoded segments of the path; empty for {@code /}; the last one is empty when the path
     * ends with {@code /}.
     *
     * @return the segments, unmodifiable
     */
    public List<String> pathSegments() {
        return segments;
    }

    /**
     * The pattern of the matched route, group prefixes included ({@code /orders/{id}}).
     *
     * @return the pattern, or empty if no route matched (the router answers 400, 404, 405 or an
     *         automatic {@code OPTIONS})
     */
    public Optional<String> pattern() {
        return route == null ? Optional.empty() : Optional.of(route.pattern().text());
    }

    /**
     * The decoded value of a path variable. For a catch-all {@code {name...}} it is the decoded
     * segments it matched, joined with {@code /} (see {@link #pathParamSegments}).
     *
     * @param name the variable's name in the matched route's pattern
     * @return the value, never empty
     * @throws IllegalArgumentException if the matched route's pattern has no such variable (a
     *                                  programming error: the router answers 500)
     */
    public String pathParam(String name) {
        return String.join("/", pathParamSegments(name));
    }

    /**
     * The decoded segments of a path variable: one for {@code {name}}, one or more for a
     * catch-all {@code {name...}}.
     *
     * @param name the variable's name in the matched route's pattern
     * @return the segments, unmodifiable, not empty
     * @throws IllegalArgumentException if the matched route's pattern has no such variable
     */
    public List<String> pathParamSegments(String name) {
        Objects.requireNonNull(name, "name");
        Integer position = route == null ? null : route.positions().get(name);
        if (position == null) {
            throw new IllegalArgumentException("no path variable " + Text.quote(name) + " in "
                    + (route == null ? "this request (no route matched)"
                            : "the pattern " + Text.quote(route.pattern().text())));
        }
        return name.equals(route.catchAll()) ? segments.subList(position, segments.size())
                : List.of(segments.get(position));
    }

    /**
     * A path variable converted with {@code param}.
     *
     * @param name  the variable's name
     * @param param the conversion
     * @param <T>   the converted type
     * @return the value
     * @throws WebException             400 if the value does not convert
     * @throws IllegalArgumentException if the matched route's pattern has no such variable
     */
    public <T> T pathParam(String name, Param<T> param) {
        return param.convert("path parameter", name, pathParam(name));
    }

    /**
     * The first value of a query parameter.
     *
     * @param name the decoded name
     * @return the value (empty text for {@code ?name} or {@code ?name=}), or empty if absent
     * @throws WebException 400 if the query is invalid
     */
    public Optional<String> query(String name) {
        var values = query.params().get(name);
        return values == null ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Every value of a query parameter, in order.
     *
     * @param name the decoded name
     * @return the values, unmodifiable, empty if absent
     * @throws WebException 400 if the query is invalid
     */
    public List<String> queryAll(String name) {
        return query.params().getOrDefault(name, List.of());
    }

    /**
     * The first value of a query parameter converted with {@code param}.
     *
     * @param name  the decoded name
     * @param param the conversion
     * @param <T>   the converted type
     * @return the value, or empty if the parameter is absent
     * @throws WebException 400 if the query is invalid or the value does not convert
     */
    public <T> Optional<T> query(String name, Param<T> param) {
        return query(name).map(value -> param.convert("query parameter", name, value));
    }

    /**
     * The first value of a query parameter that must be present, converted with {@code param}.
     *
     * @param name  the decoded name
     * @param param the conversion
     * @param <T>   the converted type
     * @return the value
     * @throws WebException 400 if the parameter is absent, the query is invalid or the value does
     *                      not convert
     */
    public <T> T requiredQuery(String name, Param<T> param) {
        return query(name, param).orElseThrow(() -> new WebException(400, "Bad Request",
                "Missing query parameter " + Text.quote(name) + "."));
    }

    /**
     * Every query parameter: names in the order they first appear, values in order.
     *
     * @return the parameters, unmodifiable
     * @throws WebException 400 if the query is invalid
     */
    public Map<String, List<String>> queryParams() {
        return query.params();
    }

    /**
     * The first value of a cookie, the counterpart of Spring's {@code @CookieValue}. See
     * {@link #cookies()} for the rules.
     *
     * @param name the name, case-sensitive
     * @return the value as sent (quotes kept, nothing decoded), or empty if absent
     * @throws WebException 400 if the request has more than 200 cookies
     */
    public Optional<String> cookie(String name) {
        var values = cookies.cookies().get(Objects.requireNonNull(name, "name"));
        return values == null ? Optional.empty() : Optional.of(values.getFirst());
    }

    /**
     * Every value of a cookie, in the order of the {@code Cookie} header. Browsers send one
     * name more than once when cookies with different {@code Path} or {@code Domain} match;
     * the one with the longest path comes first (RFC 6265 §5.4).
     *
     * @param name the name, case-sensitive
     * @return the values, unmodifiable, empty if absent
     * @throws WebException 400 if the request has more than 200 cookies
     */
    public List<String> cookieAll(String name) {
        return cookies.cookies().getOrDefault(Objects.requireNonNull(name, "name"), List.of());
    }

    /**
     * The first value of a cookie converted with {@code param}, as strictly as a query
     * parameter.
     *
     * @param name  the name, case-sensitive
     * @param param the conversion
     * @param <T>   the converted type
     * @return the value, or empty if the cookie is absent
     * @throws WebException 400 if the value does not convert (the detail names the cookie and
     *                      quotes the value, escaped and at most 40 characters), or if the
     *                      request has more than 200 cookies
     */
    public <T> Optional<T> cookie(String name, Param<T> param) {
        Objects.requireNonNull(param, "param");
        return cookie(name).map(value -> param.convert("cookie", name, value));
    }

    /**
     * Every cookie of the request, parsed on first use, at most once per request (RFC 6265
     * §4.2.1, §5.4). Every {@code Cookie} field counts, joined as if separated by {@code "; "}.
     * The header is split on {@code ;}; each pair is trimmed of spaces and tabs; the name is the
     * text before the first {@code =} and must be a {@code token}; the value is
     * {@code *cookie-octet} (visible US-ASCII without {@code "}, {@code ,}, {@code ;} and
     * {@code \}) or the same inside {@code "}. The quotes stay part of the value, and nothing is
     * percent-decoded: the value is opaque to the framework.
     *
     * <p>A pair that breaks these rules (no {@code =}, an empty or invalid name, a space, comma,
     * backslash, control character or non-ASCII character in the value) is skipped without a
     * trace, never a 400: other applications on the same site may set cookies the browser
     * sends along, and a 400 would lock the user out. More than 200 valid pairs is a
     * {@link WebException} 400 whose detail holds no names or values; the header's size is
     * already limited by {@code HttpLimits.maxHeaderSectionSize}.
     *
     * @return names in the order they first appear, values in order; unmodifiable
     * @throws WebException 400 if the request has more than 200 cookies
     */
    public Map<String, List<String>> cookies() {
        return cookies.cookies();
    }

    /**
     * The header fields.
     *
     * @return the headers
     */
    public Headers headers() {
        return http.headers();
    }

    /**
     * The {@code Content-Type} of the request.
     *
     * @return the media type, or empty if the request has none
     * @throws WebException 415 if it is not a valid media type (see {@link MediaType#parse}) or
     *                      is given more than once
     */
    public Optional<MediaType> contentType() {
        return JsonBody.contentType(headers());
    }

    /**
     * The {@code Accept} header, every {@code Accept} field combined (see {@link AcceptHeader}).
     *
     * @return the header; {@link AcceptHeader#ANY} if the request has none
     * @throws WebException 400 if it is not valid (Spring answers 406 here; a malformed header is
     *                      the client's error)
     */
    public AcceptHeader accept() {
        try {
            return AcceptHeader.parse(headers().all("Accept"));
        } catch (IllegalArgumentException e) {
            throw new WebException(400, "Invalid Accept header: " + e.getMessage() + ".");
        }
    }

    /**
     * Content negotiation: the media type to respond with, among those the handler can produce,
     * after the request's {@code Accept} (see {@link AcceptHeader#negotiate}). The highest
     * quality above 0 wins; on equal quality the first one given; without {@code Accept} the
     * first one.
     *
     * <pre>{@code
     * var type = request.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
     * return type.equals(MediaType.TEXT_PLAIN) ? Response.text(200, order.toString())
     *         : Responses.json(request, 200, ORDER, order);
     * }</pre>
     *
     * @param offered the media types, without wildcards, in the server's order of preference
     * @return the chosen media type, one of {@code offered}
     * @throws WebException             406 if none is acceptable, 400 if {@code Accept} is invalid
     * @throws IllegalArgumentException if {@code offered} is empty or has a wildcard
     */
    public MediaType negotiate(MediaType... offered) {
        var types = List.of(offered);
        return accept().negotiate(types).orElseThrow(() -> {
            var names = new StringBuilder();
            for (var type : types) {
                names.append(names.isEmpty() ? "" : ", ").append(type);
            }
            return new WebException(406, Text.truncate("Acceptable representations: " + names + ".", 500));
        });
    }

    /**
     * Reads the body as JSON with the router's {@link JsonLimits} (see
     * {@link RouterBuilder#jsonLimits}; {@link JsonLimits#DEFAULT} unless set) and decodes it
     * with {@code codec}: the counterpart of Spring's {@code @RequestBody}. See
     * {@link #readJson(JsonCodec, JsonLimits)} for the rules.
     *
     * @param codec the codec
     * @param <T>   the decoded type
     * @return the value
     * @throws WebException                     415, 413 or 400, see
     *                                          {@link #readJson(JsonCodec, JsonLimits)}
     * @throws IllegalStateException            if the body was already read
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (the server answers with its status)
     * @throws IOException                      if the connection fails
     */
    public <T> T readJson(JsonCodec<T> codec) throws IOException {
        return readJson(codec, body.jsonLimits());
    }

    /**
     * Reads the body as JSON with {@code limits} (instead of the router's) and decodes it with
     * {@code codec}. Checked in this order:
     *
     * <ol>
     *   <li>A {@code Content-Encoding} other than {@code identity}: 415 (RFC 9110 §8.4), with
     *       {@code Accept: application/json} and {@code Accept-Encoding: identity}.</li>
     *   <li>No {@code Content-Type}, an invalid one, one that is not JSON
     *       ({@code application/json} or {@code application/<name>+json}, see
     *       {@link MediaType#isJson()}), or a {@code charset} other than UTF-8 (without regard to
     *       case): 415 with {@code Accept: application/json}.</li>
     *   <li>An empty body: 400 {@code Request body is required}.</li>
     *   <li>The body is parsed as it streams in, never read whole first. More than
     *       {@code limits.maxInputSize()} bytes: 413 (reading stops one byte past the limit).
     *       Not JSON, or over another limit (depth, entries, string or number length): 400 with
     *       the parser's description, line and column, but no text quoted from the body.</li>
     *   <li>{@code codec.decode}: a mismatch is 400 with the detail
     *       {@code Request body has N problems.} and the extension member {@code errors}, every
     *       problem as {@code {"path": ..., "message": ...}} in document order, at most 100
     *       (see {@link Problem#withErrors}).</li>
     * </ol>
     *
     * <p>Every one of those is a {@link WebException} and goes through the router's exception
     * mappers like any other. The body can be read once: a second {@code readJson}, or
     * {@code readJson} after {@link #readForm}, {@link #body()} or {@link #bytes()}, is a programming error
     * ({@link IllegalStateException}, so 500).
     *
     * @param codec  the codec
     * @param limits the parser's limits for this body
     * @param <T>    the decoded type
     * @return the value
     * @throws WebException                     415, 413 or 400 as listed
     * @throws IllegalStateException            if the body was already read
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (passed on unchanged, so the server
     *                                          answers with its status)
     * @throws IOException                      if the connection fails
     */
    public <T> T readJson(JsonCodec<T> codec, JsonLimits limits) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(limits, "limits");
        body.claimForJson();
        return JsonBody.read(headers(), http.body(), codec, limits);
    }

    /**
     * Reads the body as JSON with the router's {@link JsonLimits}, decodes it with {@code codec}
     * and validates the value: the counterpart of Spring's {@code @Valid @RequestBody}. See
     * {@link #readJson(JsonCodec, JsonLimits, Validator)} for the rules.
     *
     * <pre>{@code
     * CreateOrder order = request.readJson(CREATE_ORDER, CREATE_ORDER_RULES); // valid, or 400
     * }</pre>
     *
     * @param codec     the codec
     * @param validator the validator
     * @param <T>       the decoded type
     * @return the value, valid
     * @throws WebException                     415, 413 or 400 as for
     *                                          {@link #readJson(JsonCodec, JsonLimits)}, or the 400
     *                                          of {@link WebException#invalid(Validated.Invalid)}
     * @throws IllegalStateException            if the body was already read
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (the server answers with its status)
     * @throws IOException                      if the connection fails
     */
    public <T> T readJson(JsonCodec<T> codec, Validator<? super T> validator) throws IOException {
        return readJson(codec, body.jsonLimits(), validator);
    }

    /**
     * Reads the body as JSON with {@code limits}, decodes it with {@code codec} and validates the
     * value with {@code validator}. Every check of {@link #readJson(JsonCodec, JsonLimits)} comes
     * first, in its order and with its responses (415, 400 for an empty body, 413, 400 for
     * invalid JSON, 400 with {@code errors} for a mapping problem). Only then is
     * {@code validator.check(value)} called: a mapping problem is answered before any
     * validation, and the validator is never called for a body that could not be read, parsed
     * or mapped (as in Spring, where {@code HttpMessageNotReadableException} comes before
     * validation).
     *
     * <p>A {@link Validated.Valid} result gives the value. A {@link Validated.Invalid} result
     * is the {@link WebException} of {@link WebException#invalid(Validated.Invalid)}: 400,
     * {@code The request has N validation errors.} and every violation (at most 100) in
     * {@code errors} with {@code path}, {@code message} and {@code code}, without the rejected
     * values. An exception from the validator passes unchanged (the same instance), so the
     * router's mappers or its 500 handle it.
     *
     * @param codec     the codec
     * @param limits    the parser's limits for this body
     * @param validator the validator
     * @param <T>       the decoded type
     * @return the value, valid
     * @throws WebException                     415, 413 or 400 as listed
     * @throws IllegalStateException            if the body was already read
     * @throws NullPointerException             if an argument is {@code null} (checked before
     *                                          the body is read)
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (passed on unchanged)
     * @throws IOException                      if the connection fails
     */
    public <T> T readJson(JsonCodec<T> codec, JsonLimits limits, Validator<? super T> validator)
            throws IOException {
        Objects.requireNonNull(validator, "validator");
        return validated(readJson(codec, limits), validator);
    }

    /**
     * Reads the body as an {@code application/x-www-form-urlencoded} form, at most the router's
     * {@link RouterBuilder#maxFormSize} bytes (2 MiB unless set). See {@link #readForm(int)}
     * for the rules.
     *
     * @return the form
     * @throws WebException                     415, 413 or 400, see {@link #readForm(int)}
     * @throws IllegalStateException            if the body was already read
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (the server answers with its status)
     * @throws IOException                      if the connection fails
     */
    public FormData readForm() throws IOException {
        return readForm(body.maxFormSize());
    }

    /**
     * Reads the body as an {@code application/x-www-form-urlencoded} form of at most
     * {@code maxBytes} bytes (instead of the router's limit): the counterpart of the form fields
     * Spring binds to {@code @RequestParam} and {@code @ModelAttribute}. Checked in this order:
     *
     * <ol>
     *   <li>A {@code Content-Encoding} other than {@code identity}: 415 with
     *       {@code Accept: application/x-www-form-urlencoded} and
     *       {@code Accept-Encoding: identity}.</li>
     *   <li>No {@code Content-Type}, an invalid one, one given twice, one other than
     *       {@code application/x-www-form-urlencoded}, or a {@code charset} other than UTF-8
     *       (without regard to case): 415 with
     *       {@code Accept: application/x-www-form-urlencoded}.</li>
     *   <li>The body is read as it streams in, never past the limit: more than
     *       {@code maxBytes} bytes is 413 (reading stops one byte past the limit).</li>
     *   <li>A byte above 0x7F is 400 (a form body is percent-encoded US-ASCII). The text is then
     *       parsed exactly as the query is (WHATWG URL Standard §5.1: {@code &} between pairs,
     *       the first {@code =} between name and value, {@code +} is a space, strict
     *       percent-decoding to UTF-8, empty pairs skipped). Invalid encoding or more than
     *       1 000 pairs is 400; the detail quotes nothing from the body.</li>
     * </ol>
     *
     * <p>An empty body is an empty form (an HTML form without fields is valid), unlike
     * {@link #readJson}. The form holds only the body: query parameters are never merged in
     * (read them with {@link #query(String)}). Every error above is a {@link WebException} and
     * goes through the router's exception mappers. The body can be read once: {@code readForm}
     * after {@link #readJson}, {@link #body()}, {@link #bytes()} or {@code readForm} is a
     * programming error ({@link IllegalStateException}, so 500).
     *
     * @param maxBytes the largest body in bytes, at least 1
     * @return the form
     * @throws WebException                     415, 413 or 400 as listed
     * @throws IllegalStateException            if the body was already read
     * @throws IllegalArgumentException         if {@code maxBytes} is less than 1
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (passed on unchanged, so the server
     *                                          answers with its status)
     * @throws IOException                      if the connection fails
     */
    public FormData readForm(int maxBytes) throws IOException {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be at least 1, was " + maxBytes);
        }
        body.claimForForm();
        return FormBody.read(headers(), http.body(), maxBytes);
    }

    /**
     * Reads the body as a form exactly as {@link #readForm()} does (the same 415, 413 and 400,
     * the router's {@link RouterBuilder#maxFormSize} and the read-once rule) and binds it with
     * {@code binder}: the counterpart of Spring's {@code @ModelAttribute}. See
     * {@link FormFields} for the binding rules (codes {@code required}, {@code type} and
     * {@code single}, empty values, paths, order, the limit of 100, no echoed values).
     *
     * <p>Binding errors are a {@link WebException} 400 with {@link WebException#errors()} and
     * the detail {@code Request body has N problems.}, followed by
     * {@code  The first 100 are listed.} when more than 100 were counted. An exception the
     * binder throws after errors were collected is discarded for that 400; without collected
     * errors it passes unchanged.
     *
     * @param binder the binder
     * @param <T>    the bound type
     * @return the value
     * @throws WebException                     415, 413 or 400 as for {@link #readForm(int)}, or
     *                                          400 with the binding errors
     * @throws IllegalStateException            if the body was already read
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (the server answers with its status)
     * @throws IOException                      if the connection fails
     */
    public <T> T readForm(FormBinder<T> binder) throws IOException {
        Objects.requireNonNull(binder, "binder");
        return FormFields.bind(binder, readForm().asMap(), FormFields.BODY);
    }

    /**
     * Reads and binds the body as {@link #readForm(FormBinder)} does, then validates the value:
     * the counterpart of Spring's {@code @Valid @ModelAttribute}, without a
     * {@code BindingResult}. The validator runs only when binding succeeded (binding errors
     * are answered first, and the validator is not called); its violations give the 400 of
     * {@link WebException#invalid(Validated.Invalid)}. An exception from the validator passes
     * unchanged.
     *
     * @param binder    the binder
     * @param validator the validator
     * @param <T>       the bound type
     * @return the value, valid
     * @throws WebException                     415, 413 or 400 as for
     *                                          {@link #readForm(FormBinder)}, or the 400 of
     *                                          {@link WebException#invalid(Validated.Invalid)}
     * @throws IllegalStateException            if the body was already read
     * @throws spola.http.RequestBodyException if the body is larger than
     *                                          {@code HttpLimits.maxBodySize}, malformed or stops
     *                                          arriving (the server answers with its status)
     * @throws IOException                      if the connection fails
     */
    public <T> T readForm(FormBinder<T> binder, Validator<? super T> validator) throws IOException {
        Objects.requireNonNull(binder, "binder");
        Objects.requireNonNull(validator, "validator");
        return validated(readForm(binder), validator);
    }

    /**
     * Binds the query parameters ({@link #queryParams()}) with {@code binder}, as
     * {@link #readForm(FormBinder)} binds a form: the counterpart of Spring's
     * {@code @ModelAttribute} on a {@code GET}. The body is never touched. Binding errors are a
     * {@link WebException} 400 with {@link WebException#errors()} and the detail
     * {@code Query has N problems.}, followed by {@code  The first 100 are listed.} when more
     * than 100 were counted.
     *
     * @param binder the binder
     * @param <T>    the bound type
     * @return the value
     * @throws WebException 400 if the query is invalid (see the class documentation) or with the
     *                      binding errors
     */
    public <T> T bindQuery(FormBinder<T> binder) {
        Objects.requireNonNull(binder, "binder");
        return FormFields.bind(binder, queryParams(), FormFields.QUERY);
    }

    /**
     * Binds the query parameters as {@link #bindQuery(FormBinder)} does, then validates the
     * value. The validator runs only when binding succeeded; its violations give the 400 of
     * {@link WebException#invalid(Validated.Invalid)}. An exception from the validator passes
     * unchanged. The body is never touched.
     *
     * @param binder    the binder
     * @param validator the validator
     * @param <T>       the bound type
     * @return the value, valid
     * @throws WebException 400 if the query is invalid, with the binding errors, or the 400 of
     *                      {@link WebException#invalid(Validated.Invalid)}
     */
    public <T> T bindQuery(FormBinder<T> binder, Validator<? super T> validator) {
        Objects.requireNonNull(binder, "binder");
        Objects.requireNonNull(validator, "validator");
        return validated(bindQuery(binder), validator);
    }

    /** {@code value} if {@code validator} accepts it; otherwise the 400 of {@link WebException#invalid}. */
    private static <T> T validated(T value, Validator<? super T> validator) {
        if (validator.check(value) instanceof Validated.Invalid<?> invalid) {
            throw WebException.invalid(invalid);
        }
        return value;
    }

    /**
     * The body as a stream (the same stream on every call); see {@link Request#body()}.
     *
     * @return the body
     * @throws IllegalStateException if {@link #readJson} or {@link #readForm} read the body
     */
    public InputStream body() {
        body.claimForStream("body");
        return http.body();
    }

    /**
     * Reads the rest of the body; see {@link Request#bytes()}.
     *
     * @return the bytes
     * @throws IOException           if the body cannot be read
     *                               ({@link spola.http.RequestBodyException} for a body that is
     *                               too large or malformed)
     * @throws IllegalStateException if {@link #readJson} or {@link #readForm} read the body
     */
    public byte[] bytes() throws IOException {
        body.claimForStream("bytes");
        return http.bytes();
    }

    /**
     * An attribute.
     *
     * @param key the key
     * @param <T> the value type
     * @return the value, or empty if no filter set it
     */
    public <T> Optional<T> attribute(AttributeKey<T> key) {
        var value = attributes.get(Objects.requireNonNull(key, "key"));
        return value == null ? Optional.empty() : Optional.of(key.type().cast(value));
    }

    /**
     * A copy with an attribute set (replacing the key's earlier value). This request does not
     * change; the copy keeps everything else, also {@link #scheme()} and {@link #clientAddress()}.
     *
     * @param key   the key
     * @param value the value
     * @param <T>   the value type
     * @return the new request
     * @throws ClassCastException   if {@code value} is not an instance of the key's type
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public <T> WebRequest withAttribute(AttributeKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        key.type().cast(value);
        var copy = new HashMap<AttributeKey<?>, Object>(attributes);
        copy.put(key, value);
        return new WebRequest(http, path, segments, route, query, cookies, body, Map.copyOf(copy), scheme,
                clientAddress);
    }

    /**
     * A copy with the client's scheme and address, for {@link ForwardedHeaders} only (the one
     * place that may change them). This request does not change.
     */
    WebRequest withClient(String scheme, InetAddress clientAddress) {
        return new WebRequest(http, path, segments, route, query, cookies, body, attributes,
                Objects.requireNonNull(scheme, "scheme"), Objects.requireNonNull(clientAddress, "clientAddress"));
    }

    /** How many times the query was parsed, for tests. */
    int queryParses() {
        return query.parses();
    }

    /** How many times the cookies were parsed, for tests. */
    int cookieParses() {
        return cookies.parses();
    }

    /** The method and the path, escaped and short (safe for a log line). */
    @Override
    public String toString() {
        return Text.escape(http.method(), Text.MAX_QUOTED) + " " + Text.escape(path, Text.MAX_LOGGED);
    }
}
