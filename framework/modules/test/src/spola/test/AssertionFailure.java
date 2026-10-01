package spola.test;

import java.io.Serial;
import java.util.Optional;

/**
 * A failed assertion. Its message names what was checked and, when the assertion compares
 * values, shows the expected and the actual value on lines of their own:
 *
 * <pre>
 * values differ
 *   expected: 3
 *   actual:   4
 * </pre>
 */
public final class AssertionFailure extends AssertionError {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The expected value as shown in the message, or {@code null} if the assertion compares none. */
    private final String expected;
    /** The actual value as shown in the message, or {@code null} if the assertion compares none. */
    private final String actual;

    /**
     * A failure without expected and actual values.
     *
     * @param message what went wrong
     */
    public AssertionFailure(String message) {
        this(message, null, null, null);
    }

    /**
     * A failure that compares two values.
     *
     * @param message  what was checked
     * @param expected the expected value, as it should be shown
     * @param actual   the actual value, as it should be shown
     * @param cause    an exception behind the failure (for example the wrong exception thrown),
     *                 or {@code null}
     */
    public AssertionFailure(String message, String expected, String actual, Throwable cause) {
        super(format(message, expected, actual), cause);
        this.expected = expected;
        this.actual = actual;
    }

    /**
     * The expected value as shown in the message.
     *
     * @return the expected value, or empty if the assertion compares no values
     */
    public Optional<String> expected() {
        return Optional.ofNullable(expected);
    }

    /**
     * The actual value as shown in the message.
     *
     * @return the actual value, or empty if the assertion compares no values
     */
    public Optional<String> actual() {
        return Optional.ofNullable(actual);
    }

    private static String format(String message, String expected, String actual) {
        if (expected == null && actual == null) {
            return message;
        }
        return message + "\n  expected: " + expected + "\n  actual:   " + actual;
    }
}
