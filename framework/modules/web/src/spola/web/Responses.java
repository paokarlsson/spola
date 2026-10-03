package spola.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import spola.http.Response;
import spola.json.Json;
import spola.json.JsonValue;
import spola.json.codec.JsonCodec;
import spola.json.codec.JsonMappingException;

/**
 * Responses with a JSON body, the counterpart of Spring's {@code @ResponseBody} and
 * {@code ResponseEntity}:
 *
 * <pre>{@code
 * Response show(WebRequest request) {
 *     return Responses.json(request, 200, ORDER, orders.find(request.pathParam("id", Param.LONG)));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Content negotiation comes first: {@code request.negotiate(MediaType.APPLICATION_JSON)},
 *       so a request whose {@code Accept} does not take JSON gets 406 (and an invalid
 *       {@code Accept} 400) before anything is encoded.</li>
 *   <li>{@code Content-Type: application/json} without {@code charset} (JSON is always UTF-8,
 *       RFC 8259 §11 defines no charset parameter), and a UTF-8 body with
 *       {@code Content-Length}: the body is encoded and written to a byte array before the
 *       response is returned, so a failure while encoding is a clean 500, never a broken
 *       stream. {@code HEAD} gets the same headers without the body.</li>
 *   <li>A value the codec cannot encode (a {@code null} where the codec does not allow one) is
 *       the server's fault: an {@link IllegalStateException} with the
 *       {@link JsonMappingException} as its cause, so the router answers 500 without details and
 *       logs it. It never becomes a 400.</li>
 * </ul>
 *
 * <p>Problem responses ({@link Problem#toResponse()}, a {@link WebException} without a mapper)
 * are not negotiated: they are always {@code application/problem+json}, even when
 * {@code Accept} does not take it (as Spring Boot does and RFC 9457 allows).
 */
public final class Responses {

    private static final String JSON = MediaType.APPLICATION_JSON.toString();

    private Responses() {
    }

    /**
     * A JSON response with a value encoded by {@code codec}.
     *
     * @param request the request (for its {@code Accept})
     * @param status  the status, 200–599 except 204, 205 and 304
     * @param codec   the codec
     * @param value   the value
     * @param <T>     the value's type
     * @return the response
     * @throws WebException             406 if {@code Accept} does not take {@code application/json},
     *                                  400 if it is invalid
     * @throws IllegalStateException    if the codec cannot encode {@code value} (500)
     * @throws IllegalArgumentException if the status cannot have a body
     */
    public static <T> Response json(WebRequest request, int status, JsonCodec<T> codec, T value) {
        Objects.requireNonNull(codec, "codec");
        request.negotiate(MediaType.APPLICATION_JSON);
        JsonValue json;
        try {
            json = codec.encode(value);
        } catch (JsonMappingException e) {
            throw new IllegalStateException("the response value could not be encoded as JSON", e);
        }
        return body(status, json);
    }

    /**
     * A JSON response with a JSON value.
     *
     * @param request the request (for its {@code Accept})
     * @param status  the status, 200–599 except 204, 205 and 304
     * @param value   the value
     * @return the response
     * @throws WebException             406 if {@code Accept} does not take {@code application/json},
     *                                  400 if it is invalid
     * @throws IllegalArgumentException if the status cannot have a body
     */
    public static Response json(WebRequest request, int status, JsonValue value) {
        Objects.requireNonNull(value, "value");
        request.negotiate(MediaType.APPLICATION_JSON);
        return body(status, value);
    }

    /**
     * 201 Created with {@code Location} and the created value as JSON, the counterpart of
     * {@code ResponseEntity.created(location).body(value)}.
     *
     * @param request  the request (for its {@code Accept})
     * @param location the {@code Location} value, such as {@code /orders/7}
     * @param codec    the codec
     * @param value    the created value
     * @param <T>      the value's type
     * @return the response
     * @throws WebException             406 if {@code Accept} does not take {@code application/json}
     * @throws IllegalStateException    if the codec cannot encode {@code value} (500)
     * @throws IllegalArgumentException if {@code location} is not a valid header value
     */
    public static <T> Response created(WebRequest request, String location, JsonCodec<T> codec, T value) {
        Objects.requireNonNull(location, "location");
        return json(request, 201, codec, value).withHeader("Location", location);
    }

    /**
     * 204 No Content, the counterpart of {@code ResponseEntity.noContent().build()}.
     *
     * @return the response
     */
    public static Response noContent() {
        return Response.status(204);
    }

    private static Response body(int status, JsonValue json) {
        var out = new ByteArrayOutputStream();
        try {
            Json.writeUtf8(json, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // a ByteArrayOutputStream does not fail
        }
        return Response.bytes(status, JSON, out.toByteArray());
    }
}
