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
 */
public final class WebRequest {

    private final Request http;
    private final String path;
    private final List<String> segments;
    private final Route route;
    private final QueryState query;
    private final Map<AttributeKey<?>, Object> attributes;

    WebRequest(Request http, String path, List<String> segments, Route route, QueryState query,
            Map<AttributeKey<?>, Object> attributes) {
        this.http = http;
        this.path = path;
        this.segments = segments;
        this.route = route;
        this.query = query;
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
     * The body as a stream; see {@link Request#body()}.
     *
     * @return the body
     */
    public InputStream body() {
        return http.body();
    }

    /**
     * Reads the rest of the body; see {@link Request#bytes()}.
     *
     * @return the bytes
     * @throws IOException if the body cannot be read ({@link spola.http.RequestBodyException}
     *                     for a body that is too large or malformed)
     */
    public byte[] bytes() throws IOException {
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
        return new WebRequest(http, path, segments, route, query, Map.copyOf(copy));
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
