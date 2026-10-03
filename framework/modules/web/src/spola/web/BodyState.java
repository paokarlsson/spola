package spola.web;

import java.util.concurrent.atomic.AtomicReference;
import spola.json.JsonLimits;

/**
 * The body of one request: who has read it, and the router's defaults for {@code readJson}
 * ({@link JsonLimits}) and {@code readForm} (the largest form). Shared by every
 * {@link WebRequest} derived from the same request ({@code withAttribute} keeps it), so that
 * the body is read as JSON or as a form at most once whichever copy is used.
 */
final class BodyState {

    private static final String JSON = "readJson";
    private static final String FORM = "readForm";

    private final JsonLimits jsonLimits;
    private final int maxFormSize;
    private final AtomicReference<String> reader = new AtomicReference<>();

    BodyState(JsonLimits jsonLimits, int maxFormSize) {
        this.jsonLimits = jsonLimits;
        this.maxFormSize = maxFormSize;
    }

    /** The router's default limits for {@code readJson}. */
    JsonLimits jsonLimits() {
        return jsonLimits;
    }

    /** The router's default size limit for {@code readForm}. */
    int maxFormSize() {
        return maxFormSize;
    }

    /** Claims the body for {@code readJson}; fails if anything read it before. */
    void claimForJson() {
        claimWhole(JSON);
    }

    /** Claims the body for {@code readForm}; fails if anything read it before. */
    void claimForForm() {
        claimWhole(FORM);
    }

    private void claimWhole(String method) {
        var earlier = reader.compareAndExchange(null, method);
        if (earlier != null) {
            throw new IllegalStateException("the request body can be read only once and was already read by "
                    + earlier + "()");
        }
    }

    /**
     * Notes that {@code body()} or {@code bytes()} reads the body; fails after {@code readJson}
     * or {@code readForm}.
     */
    void claimForStream(String method) {
        var earlier = reader.compareAndExchange(null, method);
        if (JSON.equals(earlier) || FORM.equals(earlier)) {
            throw new IllegalStateException("the request body was already read by " + earlier + "()");
        }
    }
}
