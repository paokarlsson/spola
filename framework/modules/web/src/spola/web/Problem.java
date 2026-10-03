package spola.web;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import spola.http.Response;
import spola.json.Json;
import spola.json.JsonArray;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;

/**
 * Problem details for an HTTP API (RFC 9457), the counterpart of Spring's {@code ProblemDetail}.
 * {@link #toResponse()} writes it as {@code application/problem+json} with {@code spola.json}:
 *
 * <pre>{@code
 * {"type":"about:blank","title":"Not Found","status":404,"detail":"No order 7.","instance":"/orders/7"}
 * }</pre>
 *
 * <p>Members in this order: {@code type}, {@code title}, {@code status}, {@code detail} (only
 * when present), {@code instance} (only when present), then the extension members. Text is cut
 * to at most {@link #MAX_TEXT} characters: {@code title}, {@code detail}, {@code instance} and
 * every string inside the extensions (at any depth); unpaired surrogates become U+FFFD, so that
 * every problem can be written as JSON.
 *
 * @param type       a URI reference that identifies the problem type; {@code about:blank} when
 *                   the status says it all
 * @param title      a short summary of the problem type; the status's reason phrase by default
 * @param status     the status, 400–599
 * @param detail     an explanation of this occurrence, or empty
 * @param instance   a reference to this occurrence (the router uses the decoded request path),
 *                   or empty
 * @param extensions extra members; none of them may be named like a standard member
 */
public record Problem(String type, String title, int status, Optional<String> detail, Optional<String> instance,
        JsonObject extensions) {

    /** The media type of a problem response. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /** The most characters a text member (or a string in the extensions) is cut to. */
    public static final int MAX_TEXT = 2_000;

    /** The most entries {@link #withErrors} keeps in {@code errors}. */
    public static final int MAX_ERRORS = 100;

    /**
     * One entry of the {@code errors} extension member: where in the request something is wrong
     * and what. This is Spola's common format for errors in request content: {@code readJson}
     * uses it for mapping problems ({@code $.items[3].qty}), and validation is to use it too.
     *
     * @param path    where the problem is, such as {@code $.items[3].qty}
     * @param message what is wrong, such as {@code expected int but found string}
     */
    public record FieldError(String path, String message) {

        /**
         * Checks the components.
         *
         * @throws NullPointerException if {@code path} or {@code message} is {@code null}
         */
        public FieldError {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(message, "message");
        }

        /**
         * The entry as JSON.
         *
         * @return {@code {"path": ..., "message": ...}}
         */
        public JsonObject toJson() {
            return JsonObject.builder().put("path", path).put("message", message).build();
        }
    }

    private static final Set<String> STANDARD_MEMBERS = Set.of("type", "title", "status", "detail", "instance");

    /**
     * Checks the members and cuts the text.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if the status is outside 400–599, the type is empty, or an
     *                                  extension member has a standard member's name
     */
    public Problem {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(extensions, "extensions");
        checkStatus(status);
        if (type.isEmpty()) {
            throw new IllegalArgumentException("the problem type must not be empty (use about:blank)");
        }
        for (var name : extensions.members().keySet()) {
            if (STANDARD_MEMBERS.contains(name)) {
                throw new IllegalArgumentException("extension member " + Text.quote(name) + " is a standard member");
            }
        }
        type = Text.truncate(type, MAX_TEXT);
        title = Text.truncate(title, MAX_TEXT);
        detail = detail.map(d -> Text.truncate(d, MAX_TEXT));
        instance = instance.map(i -> Text.truncate(i, MAX_TEXT));
        extensions = (JsonObject) cut(extensions);
    }

    /**
     * A problem with {@code type} {@code about:blank} and the status's reason phrase as title.
     *
     * @param status the status, 400–599
     * @return the problem
     */
    public static Problem of(int status) {
        return new Problem("about:blank", standardTitle(status), status, Optional.empty(), Optional.empty(),
                JsonObject.of());
    }

    /**
     * A problem as {@link #of(int)} with a detail.
     *
     * @param status the status, 400–599
     * @param detail the detail
     * @return the problem
     */
    public static Problem of(int status, String detail) {
        return of(status).withDetail(detail);
    }

    /**
     * A copy with another type.
     *
     * @param type the type URI reference
     * @return the copy
     */
    public Problem withType(String type) {
        return new Problem(type, title, status, detail, instance, extensions);
    }

    /**
     * A copy with another title.
     *
     * @param title the title
     * @return the copy
     */
    public Problem withTitle(String title) {
        return new Problem(type, title, status, detail, instance, extensions);
    }

    /**
     * A copy with a detail.
     *
     * @param detail the detail
     * @return the copy
     */
    public Problem withDetail(String detail) {
        return new Problem(type, title, status, Optional.of(detail), instance, extensions);
    }

    /**
     * A copy with an instance.
     *
     * @param instance the instance, typically {@link WebRequest#path()}
     * @return the copy
     */
    public Problem withInstance(String instance) {
        return new Problem(type, title, status, detail, Optional.of(instance), extensions);
    }

    /**
     * A copy with one more extension member.
     *
     * @param name  the member name, not a standard member's
     * @param value the value
     * @return the copy
     * @throws IllegalArgumentException if the name is a standard member's or already present
     */
    public Problem withExtension(String name, JsonValue value) {
        var builder = JsonObject.builder();
        extensions.members().forEach(builder::put);
        builder.put(name, value);
        return new Problem(type, title, status, detail, instance, builder.build());
    }

    /**
     * A copy with the extension member {@code errors}: an array of
     * {@code {"path": ..., "message": ...}} objects in the given order, at most
     * {@value #MAX_ERRORS} (the first ones; the detail should say how many there were). Strings
     * are cut like every extension string.
     *
     * <pre>{@code
     * {"type":"about:blank","title":"Bad Request","status":400,"detail":"Request body has 2 problems.",
     *  "errors":[{"path":"$.qty","message":"expected int but found string"},
     *            {"path":"$.sku","message":"missing member"}]}
     * }</pre>
     *
     * @param errors the errors
     * @return the copy
     * @throws IllegalArgumentException if the problem already has an {@code errors} member
     */
    public Problem withErrors(java.util.List<FieldError> errors) {
        var array = new java.util.ArrayList<JsonValue>(Math.min(errors.size(), MAX_ERRORS));
        for (var error : errors) {
            if (array.size() == MAX_ERRORS) {
                break;
            }
            array.add(error.toJson());
        }
        return withExtension("errors", JsonArray.copyOf(array));
    }

    /**
     * The problem as a JSON object, members in the documented order.
     *
     * @return the object
     */
    public JsonObject toJson() {
        var builder = JsonObject.builder().put("type", type).put("title", title).put("status", status);
        detail.ifPresent(d -> builder.put("detail", d));
        instance.ifPresent(i -> builder.put("instance", i));
        extensions.members().forEach(builder::put);
        return builder.build();
    }

    /**
     * The problem as a response with {@code Content-Type: application/problem+json}.
     *
     * @return the response
     */
    public Response toResponse() {
        return Response.bytes(status, MEDIA_TYPE, Json.write(toJson()).getBytes(StandardCharsets.UTF_8));
    }

    /** Every string inside {@code value} cut to {@link #MAX_TEXT}. */
    private static JsonValue cut(JsonValue value) {
        return switch (value) {
            case JsonString s -> s.value().length() > MAX_TEXT ? JsonString.of(Text.truncate(s.value(), MAX_TEXT)) : s;
            case JsonArray a -> {
                var values = new java.util.ArrayList<JsonValue>(a.values().size());
                boolean changed = false;
                for (var element : a.values()) {
                    var cut = cut(element);
                    changed |= cut != element;
                    values.add(cut);
                }
                yield changed ? JsonArray.copyOf(values) : a;
            }
            case JsonObject o -> {
                var builder = JsonObject.builder();
                boolean changed = false;
                for (var member : o.members().entrySet()) {
                    var cut = cut(member.getValue());
                    changed |= cut != member.getValue();
                    builder.put(member.getKey(), cut);
                }
                yield changed ? builder.build() : o;
            }
            default -> value;
        };
    }

    static void checkStatus(int status) {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("a problem status must be between 400 and 599, was " + status);
        }
    }

    /** The reason phrase of RFC 9110 §15 for a 4xx or 5xx status, or a generic one. */
    static String standardTitle(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 421 -> "Misdirected Request";
            case 422 -> "Unprocessable Content";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            default -> status >= 500 ? "Server Error" : "Client Error";
        };
    }
}
