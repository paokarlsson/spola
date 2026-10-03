package spola.web;

import java.util.concurrent.atomic.AtomicReference;
import spola.json.JsonLimits;

/**
 * The body of one request: who has read it, and the router's default {@link JsonLimits}.
 * Shared by every {@link WebRequest} derived from the same request ({@code withAttribute} keeps
 * it), so that the body is read as JSON at most once whichever copy is used.
 */
final class BodyState {

    private static final String JSON = "readJson";

    private final JsonLimits jsonLimits;
    private final AtomicReference<String> reader = new AtomicReference<>();

    BodyState(JsonLimits jsonLimits) {
        this.jsonLimits = jsonLimits;
    }

    /** The router's default limits for {@code readJson}. */
    JsonLimits jsonLimits() {
        return jsonLimits;
    }

    /** Claims the body for {@code readJson}; fails if anything read it before. */
    void claimForJson() {
        var earlier = reader.compareAndExchange(null, JSON);
        if (earlier != null) {
            throw new IllegalStateException("the request body can be read only once and was already read by "
                    + earlier + "()");
        }
    }

    /** Notes that {@code body()} or {@code bytes()} reads the body; fails after {@code readJson}. */
    void claimForStream(String method) {
        var earlier = reader.compareAndExchange(null, method);
        if (JSON.equals(earlier)) {
            throw new IllegalStateException("the request body was already read by readJson()");
        }
    }
}
