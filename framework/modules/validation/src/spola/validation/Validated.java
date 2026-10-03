package spola.validation;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@link Validator#check}: {@link Valid} with the value, or {@link Invalid} with
 * the violations. Sealed, so a {@code switch} over it needs no default:
 *
 * <pre>{@code
 * switch (ORDER.check(order)) {
 *     case Validated.Valid<CreateOrder>(var valid) -> save(valid);
 *     case Validated.Invalid<CreateOrder>(var violations, var count) -> reject(violations);
 * }
 * }</pre>
 *
 * @param <T> the type of the validated value
 */
public sealed interface Validated<T> permits Validated.Valid, Validated.Invalid {

    /**
     * The most violations a result keeps, the same limit as {@code spola.json.codec} and
     * {@code spola.web.Problem.MAX_ERRORS}. Validation goes on after that, but later
     * violations are only counted.
     */
    int MAX_VIOLATIONS = 100;

    /**
     * Nothing was wrong.
     *
     * @param value the validated value, the same instance that was checked (may be
     *              {@code null} when the validator accepts {@code null})
     * @param <T>   the type of the value
     */
    record Valid<T>(T value) implements Validated<T> {
    }

    /**
     * Something was wrong.
     *
     * @param violations the first violations in the order they were reported (declaration
     *                   order, depth first), at least 1 and at most {@value #MAX_VIOLATIONS};
     *                   not modifiable
     * @param count      how many violations were reported in total, kept or not; equal to
     *                   {@code violations.size()} unless {@value #MAX_VIOLATIONS} were kept
     * @param <T>        the type of the value
     */
    record Invalid<T>(List<Violation> violations, int count) implements Validated<T> {

        /**
         * Checks and copies the components.
         *
         * @throws NullPointerException     if {@code violations} or one of them is {@code null}
         * @throws IllegalArgumentException if {@code violations} is empty or longer than
         *                                  {@value #MAX_VIOLATIONS}, or {@code count} does not
         *                                  match it as described above
         */
        public Invalid {
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
            if (violations.isEmpty() || violations.size() > MAX_VIOLATIONS) {
                throw new IllegalArgumentException("an Invalid result has 1 to " + MAX_VIOLATIONS
                        + " violations, not " + violations.size());
            }
            if (count < violations.size() || (count > violations.size() && violations.size() < MAX_VIOLATIONS)) {
                throw new IllegalArgumentException("count " + count + " does not match " + violations.size()
                        + " violations (more than kept only when " + MAX_VIOLATIONS + " are kept)");
            }
        }
    }
}
