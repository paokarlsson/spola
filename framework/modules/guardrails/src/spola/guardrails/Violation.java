package spola.guardrails;

import java.util.Comparator;
import java.util.Objects;

/**
 * One broken ground rule.
 *
 * @param file     path of the offending file, relative to the checked root, with {@code /}
 *                 as separator
 * @param line     1-based line number for source files, {@code 0} when not applicable
 *                 (bytecode and plain files)
 * @param rule     the rule that was broken
 * @param subject  what broke the rule, for example {@code @javax.annotation.processing.Generated}
 *                 or a module name
 * @param location where inside the file, for bytecode (for example
 *                 {@code method run()V parameter 0}); empty for source and plain files
 */
public record Violation(String file, int line, Rule rule, String subject, String location) {

    /** Orders violations by file, line, rule, subject and location. */
    public static final Comparator<Violation> ORDER = Comparator.comparing(Violation::file)
            .thenComparingInt(Violation::line)
            .thenComparing(Violation::rule)
            .thenComparing(Violation::subject)
            .thenComparing(Violation::location);

    /**
     * Validates the components.
     *
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if {@code line} is negative
     */
    public Violation {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(location, "location");
        if (line < 0) {
            throw new IllegalArgumentException("line must be >= 0: " + line);
        }
    }

    /**
     * Formats the violation as
     * {@code file[:line]: RULE (REGLER.md section): subject [at location] - description}.
     *
     * @return a single-line, human-readable report
     */
    public String format() {
        var where = line > 0 ? file + ":" + line : file;
        var at = location.isEmpty() ? "" : " at " + location;
        return where + ": " + rule.name() + " (REGLER.md " + rule.section() + "): " + subject + at
                + " - " + rule.description();
    }
}
