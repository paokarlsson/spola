package spola.json.codec;

import java.util.Objects;

/**
 * One problem found while decoding (or encoding) a value, such as
 * {@code $.items[3].qty: expected int but found string}.
 *
 * <p>Problems made by the codecs are safe to log and to return to a client: the path and the
 * message are printable ASCII (member names and values escaped as {@code \}{@code uXXXX}) and
 * quote at most 40 characters of a value from the document (200 of an exception message).
 *
 * @param path    where the problem is, from {@code $} (the value given to {@code decode}), in
 *                the notation of {@link spola.json.JsonAccessException}: {@code $.items[3].qty},
 *                {@code $["first name"]}
 * @param message what is wrong, such as {@code missing member} or
 *                {@code expected int but found string}
 */
public record JsonProblem(String path, String message) {

    /**
     * Checks the components.
     *
     * @throws NullPointerException if {@code path} or {@code message} is {@code null}
     */
    public JsonProblem {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    /**
     * The problem as one line.
     *
     * @return {@code path + ": " + message}
     */
    @Override
    public String toString() {
        return path + ": " + message;
    }
}
