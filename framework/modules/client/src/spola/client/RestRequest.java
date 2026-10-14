package spola.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import spola.client.HeaderRules.Header;
import spola.json.Json;
import spola.json.JsonParseException;
import spola.json.JsonValue;
import spola.json.codec.JsonCodec;
import spola.json.codec.JsonMappingException;

/**
 * One request of a {@link RestClient}: method, URI, headers and an optional JSON body. It is
 * immutable (every method returns a new request), so it can be sent several times and shared
 * between threads.
 *
 * <pre>{@code
 * Item created = client.post("/items")
 *         .header("Idempotency-Key", key)
 *         .json(new Item(0, "tea", 2), ITEM)
 *         .retrieve(ITEM);
 * int status = client.delete("/items/7").retrieveBodiless(); // 204
 * }</pre>
 *
 * <p>Each {@code retrieve...} method sends a new request and blocks the calling thread until the
 * response and its body are read or the client's timeout runs out. They throw
 * {@link RestClientResponseException} for a response that cannot be used (status outside 2xx,
 * body over the limit, empty, not JSON, not matching the codec) and
 * {@link RestClientTransportException} when no usable response came; see {@link RestClient} for
 * the rules.
 *
 * <p>The path was appended to the base URI as it stands: nothing is encoded and there are no
 * templates ({@code {id}} is not a variable), so a value from outside must be percent-encoded by
 * the caller before it becomes part of the path.
 */
public final class RestRequest {

    private static final String JSON_TYPE = "application/json";

    private final RestClient client;
    private final String method;
    private final URI uri;
    private final List<Header> headers;
    private final byte[] body; // null: no body; never changed

    RestRequest(RestClient client, String method, URI uri, List<Header> headers, byte[] body) {
        this.client = client;
        this.method = method;
        this.uri = uri;
        this.headers = headers;
        this.body = body;
    }

    /**
     * A copy with one more header. It replaces a default header and an earlier {@code header}
     * with the same name, in any case. The rules are those of
     * {@link RestClient.Builder#defaultHeader(String, String)}: a token as name, visible ASCII,
     * spaces and tabs as value without a space or tab first or last, and the same names refused.
     *
     * @param name  the name
     * @param value the value; it never appears in a message or {@code toString()}
     * @return the new request
     * @throws IllegalArgumentException if the name or value is not allowed; the message does not
     *                                  quote the value
     * @throws NullPointerException     if {@code name} or {@code value} is {@code null}
     */
    public RestRequest header(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        var problem = HeaderRules.problem(name, value);
        if (problem.isPresent()) {
            throw new IllegalArgumentException(problem.get());
        }
        var copy = new ArrayList<Header>(headers.size() + 1);
        for (var header : headers) {
            if (!HeaderRules.sameName(header.name(), name)) {
                copy.add(header);
            }
        }
        copy.add(new Header(name, value));
        return new RestRequest(client, method, uri, List.copyOf(copy), body);
    }

    /**
     * A copy with {@code value} as the JSON body, encoded now by {@code codec}, and
     * {@code Content-Type: application/json}. It replaces an earlier body. Works with every
     * method; a body on {@code GET} has no defined meaning (RFC 9110 9.3.1) and many servers
     * refuse it.
     *
     * @param value the value; to send JSON {@code null}, use {@code json(JsonNull.INSTANCE)}
     * @param codec the codec
     * @param <T>   the value's type
     * @return the new request
     * @throws JsonMappingException if the codec cannot encode the value; passed through as it is,
     *                              and nothing is sent
     * @throws NullPointerException if {@code value} or {@code codec} is {@code null}
     */
    public <T> RestRequest json(T value, JsonCodec<T> codec) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(codec, "codec");
        return withBody(codec.encode(value));
    }

    /**
     * A copy with a JSON tree as the body and {@code Content-Type: application/json}. It
     * replaces an earlier body.
     *
     * @param value the JSON value
     * @return the new request
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public RestRequest json(JsonValue value) {
        return withBody(Objects.requireNonNull(value, "value"));
    }

    private RestRequest withBody(JsonValue json) {
        return new RestRequest(client, method, uri, headers, Json.write(json).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends the request and decodes the 2xx body with {@code codec}: the counterpart of Spring's
     * {@code retrieve().body(Type.class)}.
     *
     * @param codec the codec for the response body
     * @param <T>   the value's type
     * @return the decoded value
     * @throws RestClientResponseException  for a status outside 2xx, a body over the limit, an
     *                                      empty body or one that is not JSON (the cause is the
     *                                      {@link JsonParseException}), or JSON that does not fit
     *                                      the codec (the cause is the
     *                                      {@link JsonMappingException})
     * @throws RestClientTransportException when no usable response came
     * @throws IllegalStateException        if the client is closed
     * @throws NullPointerException         if {@code codec} is {@code null}
     */
    public <T> T retrieve(JsonCodec<T> codec) {
        Objects.requireNonNull(codec, "codec");
        var success = client.exchange(this);
        var json = parse(success);
        try {
            return codec.decode(json);
        } catch (JsonMappingException e) {
            throw unusable(success, "the response body does not fit the codec", e);
        }
    }

    /**
     * Sends the request and parses the 2xx body as a JSON tree.
     *
     * @return the JSON value
     * @throws RestClientResponseException  for a status outside 2xx, a body over the limit, or an
     *                                      empty body or one that is not JSON (the cause is the
     *                                      {@link JsonParseException})
     * @throws RestClientTransportException when no usable response came
     * @throws IllegalStateException        if the client is closed
     */
    public JsonValue retrieveJson() {
        return parse(client.exchange(this));
    }

    /**
     * Sends the request, reads the 2xx body within the limit and drops it: the counterpart of
     * Spring's {@code retrieve().toBodilessEntity()}, for 204 and the like.
     *
     * @return the status, 200–299
     * @throws RestClientResponseException  for a status outside 2xx or a body over the limit
     * @throws RestClientTransportException when no usable response came
     * @throws IllegalStateException        if the client is closed
     */
    public int retrieveBodiless() {
        return client.exchange(this).status();
    }

    private JsonValue parse(RestClient.Success success) {
        try {
            return Json.parse(success.body(), client.jsonLimits());
        } catch (JsonParseException e) {
            var what = success.body().length == 0 ? "the response body is empty, not JSON"
                    : "the response body is not JSON";
            throw unusable(success, what, e);
        }
    }

    private RestClientResponseException unusable(RestClient.Success success, String what, RuntimeException cause) {
        return new RestClientResponseException(method, uri, success.status(), success.headers(),
                Text.excerpt(success.body(), success.body().length), what, cause);
    }

    /**
     * The headers that are sent: the defaults that the request does not replace, the request's
     * own, {@code Content-Type} with a body and {@code Accept: application/json} when none of
     * them is an {@code Accept}.
     */
    List<Header> effectiveHeaders() {
        var all = new ArrayList<Header>();
        for (var header : client.defaultHeaders()) {
            if (headers.stream().noneMatch(h -> HeaderRules.sameName(h.name(), header.name()))) {
                all.add(header);
            }
        }
        all.addAll(headers);
        if (body != null) {
            all.add(new Header("Content-Type", JSON_TYPE));
        }
        if (all.stream().noneMatch(h -> HeaderRules.sameName(h.name(), "Accept"))) {
            all.add(new Header("Accept", JSON_TYPE));
        }
        return all;
    }

    String method() {
        return method;
    }

    URI uri() {
        return uri;
    }

    byte[] body() {
        return body;
    }

    /**
     * The method, the URI without its query, the names of the headers that are sent (never
     * their values) and the size of the body (never its content).
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "RestRequest[" + method + " " + Uris.withoutQuery(uri) + ", headers "
                + effectiveHeaders().stream().map(Header::name).toList()
                + (body == null ? ", no body" : ", body " + body.length + " bytes") + "]";
    }
}
