package spola.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The collector of one validation, at one path. A {@link Validator} gets one in
 * {@link Validator#validate} and reports what is wrong with {@link #reject}; {@link #at},
 * {@link #atIndex} and {@link #atKey} give an {@code Errors} for a path below this one that
 * writes to the same report:
 *
 * <pre>{@code
 * Validator<Map<String, Integer>> stock = (map, errors) -> map.forEach((sku, qty) -> {
 *     if (qty < 0) {
 *         errors.atKey(sku).reject("negative", "must not be negative");
 *     }
 * });
 * }</pre>
 *
 * <p><b>Paths</b> use the notation of {@code spola.json.codec}, so that a path can be shown
 * next to a JSON mapping problem as it is: {@code $} is the validated value, {@code .name} a
 * member whose name is an identifier ({@code [A-Za-z_$][A-Za-z0-9_$]*}), {@code ["any name"]}
 * any other name (escaped like a JSON string, every character outside printable ASCII as
 * {@code \}{@code uXXXX}) and {@code [3]} an element. A name the code declares ({@link #at})
 * is always written in full; a name from data ({@link #atKey}) is cut after 40 characters and
 * followed by its length ({@code $["aaaa..."... (10000 characters)]}).
 *
 * <p><b>Order and limit:</b> violations are kept in the order {@code reject} is called. At
 * most {@link Validated#MAX_VIOLATIONS} (100) are kept; later ones are only counted, and their
 * path is never rendered.
 *
 * <p>An {@code Errors} belongs to one {@code check}/{@code require} call: it is not
 * thread-safe, and using it after that call has returned throws
 * {@link IllegalStateException}.
 */
public final class Errors {

    /** The report shared by every {@code Errors} of one validation. */
    private static final class Report {
        private final List<Violation> kept = new ArrayList<>();
        private int count;
        private boolean closed;
    }

    private final Report report;
    private final Path path;

    private Errors(Report report, Path path) {
        this.report = report;
        this.path = path;
    }

    /** A fresh collector at the root ({@code $}) with an empty report. */
    static Errors root() {
        return new Errors(new Report(), Path.ROOT);
    }

    /** Ends the validation: later use of any collector of it throws; the result. */
    <T> Validated<T> finish(T value) {
        report.closed = true;
        if (report.count == 0) {
            return new Validated.Valid<>(value);
        }
        return new Validated.Invalid<>(List.copyOf(report.kept), report.count);
    }

    /**
     * The collector for a member whose name the code declares (a field, a property). The name
     * is written in full in the path: {@code .name} if it is an identifier, otherwise
     * {@code ["name"]}, escaped.
     *
     * @param name the member's name; may be empty (rendered {@code [""]})
     * @return a collector at {@code <this path>.name} that writes to the same report
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Errors at(String name) {
        Objects.requireNonNull(name, "name");
        return new Errors(report, path.member(name));
    }

    /**
     * The collector for an element of a list or array.
     *
     * @param index the element's index
     * @return a collector at {@code <this path>[index]} that writes to the same report
     * @throws IllegalArgumentException if {@code index} is negative
     */
    public Errors atIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("an index must not be negative: " + index);
        }
        return new Errors(report, path.element(index));
    }

    /**
     * The collector for a name that comes from data, such as a map key. Rendered like
     * {@link #at}, except that a key longer than 40 characters is cut after 40 and followed by
     * its length outside the quotes: {@code ["kkkk..."... (41 characters)]}. Up to 100
     * violations can share a path, so a key of a megabyte is never copied into each of them.
     *
     * @param key the key
     * @return a collector at the key below this path that writes to the same report
     * @throws NullPointerException if {@code key} is {@code null}
     */
    public Errors atKey(String key) {
        Objects.requireNonNull(key, "key");
        return new Errors(report, path.key(key));
    }

    /**
     * Reports a violation at this path. The violation is kept if fewer than 100 have been kept
     * in this validation, and counted either way. The message is kept as given, except that
     * control characters, line and paragraph separators, bidirectional formatting characters
     * and unpaired surrogates are written as {@code \}{@code uXXXX}, and that a message longer
     * than 500 characters is cut and followed by its length.
     *
     * @param code    a stable, machine-readable key such as {@code notBlank}: non-empty,
     *                printable ASCII without spaces
     * @param message the text for a person, such as {@code must not be blank}
     * @throws NullPointerException     if {@code code} or {@code message} is {@code null}
     * @throws IllegalArgumentException if {@code code} is empty or not printable ASCII
     * @throws IllegalStateException    if the validation this collector belongs to is over
     */
    public void reject(String code, String message) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Text.code(code);
        if (report.closed) {
            throw new IllegalStateException("this Errors belongs to a validation that is over");
        }
        if (report.kept.size() < Validated.MAX_VIOLATIONS) {
            report.kept.add(new Violation(path.toString(), code, Text.message(message)));
        }
        if (report.count < Integer.MAX_VALUE) {
            report.count++;
        }
    }

    /**
     * The path of this collector, rendered as in a {@link Violation}.
     *
     * @return the path, such as {@code $.lines[3].qty}
     */
    public String path() {
        return path.toString();
    }

    @Override
    public String toString() {
        return "Errors[" + path() + "]";
    }
}
