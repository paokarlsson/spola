package spola.config;

import java.io.Serial;
import java.util.List;

/**
 * Building or binding a {@link Config} failed. Carries every problem that was found, not only
 * the first: all sources are read, all placeholders resolved and every value a binding asks
 * for is checked before this is thrown. The message lists the problems one per line; it never
 * contains the value of a sensitive key.
 */
public final class ConfigException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ConfigProblem[] problems;

    ConfigException(String what, List<ConfigProblem> problems) {
        super(message(what, problems));
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("a ConfigException needs at least one problem");
        }
        this.problems = problems.toArray(ConfigProblem[]::new);
    }

    private static String message(String what, List<ConfigProblem> problems) {
        var text = new StringBuilder(what + " has " + problems.size() + " problem"
                + (problems.size() == 1 ? "" : "s") + ":");
        for (var problem : problems) {
            text.append("\n  - ").append(problem.message());
        }
        return text.toString();
    }

    /**
     * The problems, in the order they were found: for a build, source by source and then
     * placeholders by key; for a binding, in the order the binding function asked for values.
     *
     * @return the problems, never empty
     */
    public List<ConfigProblem> problems() {
        return List.of(problems);
    }
}
