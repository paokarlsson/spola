package spola.validation;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

/**
 * A value was not valid; thrown by {@link Validator#require}. The Spola counterpart of
 * Jakarta's {@code ConstraintViolationException}.
 *
 * <p>{@link #violations()} and {@link #count()} are those of the {@link Validated.Invalid}
 * result. {@link #getMessage()} is one short line of at most {@value #MAX_MESSAGE} characters,
 * printable ASCII only: the number of violations and the first three as
 * {@code path: message}, separated by {@code ; }, each cut after 600 characters (once escaped), and how many
 * more there are:
 *
 * <pre>
 * 5 violations: $.customer: must not be blank; $.lines[0].qty: must be between 1 and 99; $.lines[2].sku: must not be blank; ... (2 more)
 * </pre>
 *
 * <p>Characters outside printable ASCII (line breaks, escape characters, non-Latin letters)
 * are written as {@code \}{@code uXXXX}, so the message can never forge a log line.
 */
public final class ValidationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The longest {@link #getMessage()}, in characters. */
    public static final int MAX_MESSAGE = 2_000;

    /** Violations listed in the message. */
    private static final int LISTED = 3;

    /** Longest entry ({@code path: message}) in the message, in characters once escaped. */
    private static final int MAX_ENTRY = 600;

    /** The violations; an immutable list (serializable, as {@code List.copyOf} lists are). */
    @SuppressWarnings("serial")
    private final List<Violation> violations;

    private final int count;

    /**
     * The exception for an invalid result.
     *
     * @param invalid the result
     * @throws NullPointerException if {@code invalid} is {@code null}
     */
    public ValidationException(Validated.Invalid<?> invalid) {
        super(summary(Objects.requireNonNull(invalid, "invalid")), null, false, true);
        this.violations = invalid.violations();
        this.count = invalid.count();
    }

    /**
     * The violations, in the order they were reported, at most
     * {@value Validated#MAX_VIOLATIONS}.
     *
     * @return the violations; at least 1, not modifiable
     */
    public List<Violation> violations() {
        return violations;
    }

    /**
     * How many violations were reported, kept or not.
     *
     * @return the number, at least {@code violations().size()}
     */
    public int count() {
        return count;
    }

    private static String summary(Validated.Invalid<?> invalid) {
        int count = invalid.count();
        var out = new StringBuilder(count + (count == 1 ? " violation: " : " violations: "));
        var listed = invalid.violations().subList(0, Math.min(LISTED, invalid.violations().size()));
        for (int i = 0; i < listed.size(); i++) {
            var violation = listed.get(i);
            if (i > 0) {
                out.append("; ");
            }
            out.append(Text.ascii(violation.path() + ": " + violation.message(), MAX_ENTRY));
        }
        int more = count - listed.size();
        if (more > 0) {
            out.append("; ... (").append(more).append(" more)");
        }
        // At most 3 entries of 603 characters plus the count and the tail: always fits. Kept as
        // a guard so the documented limit holds even if the constants change.
        if (out.length() > MAX_MESSAGE) {
            var tail = more > 0 ? "... (" + more + " more)" : "...";
            out.setLength(MAX_MESSAGE - tail.length());
            out.append(tail);
        }
        return out.toString();
    }
}
