package spola.validation;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * A check of a single value with a code and a message, such as {@code notBlank} /
 * {@code must not be blank}. The built-in ones are in {@link Constraints}; your own are made
 * with {@link #of}:
 *
 * <pre>{@code
 * static final Constraint<String> SKU =
 *         Constraint.of("sku", s -> s.matches("[A-Z]{3}-[0-9]{4}"), "must be a SKU like ABC-1234");
 * }</pre>
 *
 * <p>Constraints are given to {@link Validator.Builder#field} and its siblings, or to
 * {@link Validator#of}; the type parameter makes a constraint for the wrong type a compile
 * error ({@code Constraints.notBlank()} on an {@code int} field does not compile).
 *
 * <p><b>{@code null}:</b> as in Jakarta Bean Validation, every constraint accepts
 * {@code null}, except {@link Constraints#notNull()}, {@link Constraints#notBlank()} and the
 * {@code notEmpty} constraints. The predicate of a constraint made with {@link #of} is never
 * called with {@code null}.
 *
 * <p>This is a final, immutable class rather than a functional interface, so that a lambda is
 * never both a {@code Constraint} and a {@link Validator} and overloads that take either are
 * never ambiguous. Constraints can be shared by any number of threads. An exception thrown by
 * the predicate is not caught: it propagates out of {@code check}/{@code require} unchanged.
 *
 * @param <T> the type of the values the constraint checks
 */
public final class Constraint<T> {

    private final String code;
    private final String message;
    private final Predicate<? super T> test;
    private final boolean checksNull;

    private Constraint(String code, String message, Predicate<? super T> test, boolean checksNull) {
        this.code = Text.code(Objects.requireNonNull(code, "code"));
        this.message = Objects.requireNonNull(message, "message");
        this.test = Objects.requireNonNull(test, "test");
        this.checksNull = checksNull;
    }

    /**
     * A constraint of your own. {@code null} is valid and never given to {@code test}.
     *
     * @param code    a stable, machine-readable key: non-empty, printable ASCII without spaces
     * @param test    true for a valid value; never called with {@code null}
     * @param message the text for a person, such as {@code must be a SKU like ABC-1234}
     * @param <T>     the type of the values
     * @return the constraint
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code code} is empty or not printable ASCII
     */
    public static <T> Constraint<T> of(String code, Predicate<? super T> test, String message) {
        return new Constraint<>(code, message, test, false);
    }

    /** A built-in constraint whose test also decides about {@code null}. */
    static <T> Constraint<T> checkingNull(String code, Predicate<? super T> test, String message) {
        return new Constraint<>(code, message, test, true);
    }

    /**
     * The same check and code with another message.
     *
     * @param message the new message
     * @return a new constraint
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public Constraint<T> withMessage(String message) {
        return new Constraint<>(code, message, test, checksNull);
    }

    /**
     * The same check and message with another code.
     *
     * @param code the new code: non-empty, printable ASCII without spaces
     * @return a new constraint
     * @throws NullPointerException     if {@code code} is {@code null}
     * @throws IllegalArgumentException if {@code code} is empty or not printable ASCII
     */
    public Constraint<T> withCode(String code) {
        return new Constraint<>(code, message, test, checksNull);
    }

    /**
     * The code reported when a value is not valid.
     *
     * @return the code, such as {@code notBlank}
     */
    public String code() {
        return code;
    }

    /**
     * The message reported when a value is not valid.
     *
     * @return the message, such as {@code must not be blank}
     */
    public String message() {
        return message;
    }

    /**
     * Whether a value is valid. {@code null} is valid unless this is {@code notNull},
     * {@code notBlank} or a {@code notEmpty} constraint.
     *
     * @param value the value, may be {@code null}
     * @return true if the value is valid
     */
    public boolean accepts(T value) {
        if (value == null && !checksNull) {
            return true;
        }
        return test.test(value);
    }

    /** Rejects the value at {@code errors}' path unless it is valid. */
    void check(T value, Errors errors) {
        if (!accepts(value)) {
            errors.reject(code, message);
        }
    }

    @Override
    public String toString() {
        return "Constraint[" + code + "]";
    }
}
