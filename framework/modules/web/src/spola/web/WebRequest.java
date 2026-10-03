package spola.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import spola.http.Headers;
import spola.http.Request;
import spola.json.JsonLimits;
import spola.json.codec.JsonCodec;

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
 * <p>The body can be read once: as JSON with {@link #readJson} (the counterpart of Spring's
 * {@code @RequestBody}), or as a stream with {@link #body()} or {@link #bytes()}. Every request
 * derived with {@link #withAttribute} shares the body and that rule.
 */
public final class WebRequest {

    private final Request http;
    private final String path;
    private final List<String> segments;
    private final Route route;
    private final QueryState query;
    private final BodyState body;
    private final Map<AttributeKey<?>, Object> attributes;

    WebRequest(Request http, String path, List<String> segments, Route route, QueryState query, BodyState body,
            Map<AttributeKey<?>, Object> attributes) {
        this.http = http;
        this.path = path;
        this.segments = segments;
        this.route = route;
        this.query = query;
        this.body = body;
        this.attributes = attributes;
    }

    /**
     * The underlying request: raw path and query, version, TLS, host.
     *
     * @return the request
     */
    public Request http() {
        return http;
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
     * {@code readJson} after {@link #body()} or {@link #bytes()}, is a programming error
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
     * The body as a stream (the same stream on every call); see {@link Request#body()}.
     *
     * @return the body
     * @throws IllegalStateException if {@link #readJson} read the body
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
     * @throws IllegalStateException if {@link #readJson} read the body
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
     * change.
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
        return new WebRequest(http, path, segments, route, query, body, Map.copyOf(copy));
    }

    /** How many times the query was parsed, for tests. */
    int queryParses() {
        return query.parses();
    }

    /** The method and the path, escaped and short (safe for a log line). */
    @Override
    public String toString() {
        return Text.escape(http.method(), Text.MAX_QUOTED) + " " + Text.escape(path, Text.MAX_LOGGED);
    }
}
