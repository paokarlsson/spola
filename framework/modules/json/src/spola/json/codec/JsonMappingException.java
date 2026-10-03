package spola.json.codec;

import java.io.Serial;
import java.util.List;

/**
 * A value could not be decoded from JSON (or encoded to it). {@link #problems()} lists every
 * problem found in the document, in document order, each with its JSON path:
 *
 * <pre>
 * $.customer.email: invalid value: not an email address
 * $.items[3].qty: expected int but found string
 * $.items[5].sku: missing member
 * </pre>
 *
 * <p>At most 100 problems are listed; when there are more, a last problem on {@code $} says how
 * many were left out. {@link #getMessage()} is one line of at most {@value #MAX_MESSAGE}
 * characters: the number of problems and as many of them as fit. Problems and message are
 * printable ASCII, so they can be logged and returned to a client as they are.
 *
 * <p>Encoding stops at the first problem, so an exception from {@code encode} has one problem.
 * A document that is not JSON at all fails earlier, with {@link spola.json.JsonParseException}.
 */
public final class JsonMappingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The longest {@link #getMessage()}, in characters. */
    public static final int MAX_MESSAGE = 2_000;

    /** The problems; an immutable list (serializable, as {@code List.copyOf} lists are). */
    @SuppressWarnings("serial")
    private final List<JsonProblem> problems;

    private final int problemCount;

    JsonMappingException(List<JsonProblem> problems) {
        this(problems, problems.size());
    }

    JsonMappingException(List<JsonProblem> problems, int problemCount) {
        super(summary(problems), null, false, true);
        this.problems = List.copyOf(problems);
        this.problemCount = problemCount;
    }

    /**
     * Every problem, in document order, at most 100 plus one that counts the rest.
     *
     * @return the problems; never empty, not modifiable
     */
    public List<JsonProblem> problems() {
        return problems;
    }

    /**
     * How many problems were found, listed or not: {@code problems().size()} when at most 100
     * were found, otherwise more than 100 (the last entry of {@link #problems()} is then the
     * summary of the ones left out, not a problem itself).
     *
     * @return the number of problems, at least 1
     */
    public int problemCount() {
        return problemCount;
    }

    /** Room kept at the end of the message for {@code "; ... (100 more)"}. */
    private static final int TAIL_ROOM = 20;

    private static String summary(List<JsonProblem> problems) {
        int n = problems.size();
        var out = new StringBuilder(n > Decoding.MAX_PROBLEMS
                ? "more than " + Decoding.MAX_PROBLEMS + " problems: "
                : n + (n == 1 ? " problem: " : " problems: "));
        for (int i = 0; i < n; i++) {
            var entry = Messages.clean(problems.get(i).toString(), MAX_MESSAGE);
            var separator = i == 0 ? "" : "; ";
            int after = n - i - 1;
            if (out.length() + separator.length() + entry.length() + (after > 0 ? TAIL_ROOM : 0) <= MAX_MESSAGE) {
                out.append(separator).append(entry);
                continue;
            }
            // this entry does not fit whole: cut it if there is reasonable room, then count the rest
            var tailAfterCut = after > 0 ? "; ... (" + after + " more)" : "";
            int room = MAX_MESSAGE - out.length() - separator.length() - 3 - tailAfterCut.length();
            if (room >= TAIL_ROOM) {
                out.append(separator).append(entry, 0, room).append("...").append(tailAfterCut);
            } else {
                out.append("; ... (").append(n - i).append(" more)");
            }
            return out.toString();
        }
        return out.toString();
    }
}
