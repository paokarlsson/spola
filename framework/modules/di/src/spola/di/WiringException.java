package spola.di;

import java.io.Serial;
import java.util.List;

/**
 * The bindings given to {@link Container#create} do not form a valid graph. Thrown before any
 * component is created; lists every problem found, each with where the binding was made and,
 * for cycles and scope mistakes, the dependency chain ({@code A -> B -> C -> A}).
 */
public final class WiringException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String[] problems;

    WiringException(List<String> problems) {
        super(message(problems));
        this.problems = problems.toArray(String[]::new);
    }

    private static String message(List<String> problems) {
        var text = new StringBuilder("the container has " + problems.size() + " wiring problem"
                + (problems.size() == 1 ? "" : "s") + ":");
        for (var problem : problems) {
            text.append("\n  - ").append(problem);
        }
        return text.toString();
    }

    /**
     * The problems, one line each, in a stable order: duplicate bindings, bindings without a
     * target, missing dependencies, cycles, singletons depending on call-scoped components.
     *
     * @return the problems
     */
    public List<String> problems() {
        return List.of(problems);
    }
}
