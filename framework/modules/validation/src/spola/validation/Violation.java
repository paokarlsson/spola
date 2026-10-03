package spola.validation;

import java.util.Objects;

/**
 * One thing wrong with a validated value: where ({@code path}), what ({@code code}) and a text
 * for a person ({@code message}).
 *
 * <p>A violation <b>never carries the rejected value</b>. Jakarta Bean Validation does
 * ({@code getInvalidValue()}), but values can be passwords or personal data, and they should
 * not end up in a log line or a response by mistake. The path says where the value is.
 *
 * <p>Violations made by {@link Errors#reject} have a path in the notation of
 * {@code spola.json.codec} (printable ASCII only), a code in printable ASCII and a message
 * without control characters, so {@code path} and {@code message} can be put into a log line
 * or an error response as they are.
 *
 * @param path    where the value is, such as {@code $.lines[3].qty}; {@code $} is the
 *                validated value itself
 * @param code    a stable, machine-readable key such as {@code notBlank} or {@code length}
 * @param message an English text such as {@code must not be blank}
 */
public record Violation(String path, String code, String message) {

    /**
     * Checks the components.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public Violation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
