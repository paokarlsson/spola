package spola.web;

import java.io.Serial;
import java.util.List;

/**
 * {@link RouterBuilder#build()} found problems: invalid patterns, invalid methods, two routes
 * with the same method and shape, two mappers for the same exception class. The message lists
 * every problem, one per line, with the method and the pattern, so that one start shows them
 * all.
 */
public final class RouterDefinitionException extends IllegalArgumentException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The problems, as listed in the message. */
    private final String[] problems;

    RouterDefinitionException(List<String> problems) {
        super(message(problems));
        this.problems = problems.toArray(String[]::new);
    }

    private static String message(List<String> problems) {
        var out = new StringBuilder("the router has ").append(problems.size())
                .append(problems.size() == 1 ? " problem:" : " problems:");
        for (var problem : problems) {
            out.append("\n  - ").append(problem);
        }
        return out.toString();
    }

    /**
     * Every problem, in the order the routes, groups and mappers were registered.
     *
     * @return the problems, unmodifiable
     */
    public List<String> problems() {
        return List.of(problems);
    }
}
