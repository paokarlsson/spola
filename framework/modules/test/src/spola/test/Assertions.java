package spola.test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Assertions for tests, meant to be imported statically. A failed assertion throws an
 * {@link AssertionFailure} whose message shows the expected and the actual value.
 *
 * <p>Equality is {@link Objects#deepEquals}, so arrays are compared element by element.
 */
public final class Assertions {

    private Assertions() {
    }

    /**
     * Fails the test.
     *
     * @param message why
     * @throws AssertionFailure always
     */
    public static void fail(String message) {
        throw new AssertionFailure(message);
    }

    // ---- equality ----------------------------------------------------------------------

    /**
     * Asserts that two values are equal.
     *
     * @param expected the expected value
     * @param actual   the actual value
     * @throws AssertionFailure if they differ
     */
    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, "values differ");
    }

    /**
     * Asserts that two values are equal.
     *
     * @param expected the expected value
     * @param actual   the actual value
     * @param message  what is checked, shown above the values on failure
     * @throws AssertionFailure if they differ
     */
    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.deepEquals(expected, actual)) {
            var shown = Values.describeBoth(expected, actual);
            throw new AssertionFailure(message, shown[0], shown[1], null);
        }
    }

    /**
     * Asserts that two numbers differ by at most {@code tolerance}.
     *
     * @param expected  the expected value
     * @param actual    the actual value
     * @param tolerance the largest allowed difference, not negative
     * @throws AssertionFailure if they differ by more (or one of them is NaN and the other is not)
     */
    public static void assertEquals(double expected, double actual, double tolerance) {
        if (!(tolerance >= 0)) {
            throw new IllegalArgumentException("tolerance must not be negative or NaN: " + tolerance);
        }
        boolean equal = Double.compare(expected, actual) == 0 || Math.abs(expected - actual) <= tolerance;
        if (!equal) {
            throw new AssertionFailure("values differ by more than " + tolerance,
                    Double.toString(expected), Double.toString(actual), null);
        }
    }

    /**
     * Asserts that two values are not equal.
     *
     * <p>Two boxed numbers of different types ({@code Byte}, {@code Short}, {@code Integer},
     * {@code Long}, {@code Float}, {@code Double}) count as equal when they have the same
     * numeric value, so {@code assertNotEquals(0, 0L)} fails instead of passing because an
     * {@code Integer} never equals a {@code Long}.
     *
     * @param unexpected the value {@code actual} must differ from
     * @param actual     the actual value
     * @throws AssertionFailure if they are equal
     */
    public static void assertNotEquals(Object unexpected, Object actual) {
        if (Objects.deepEquals(unexpected, actual)) {
            throw new AssertionFailure("values are equal",
                    "anything but " + Values.describe(unexpected), Values.describe(actual), null);
        }
        if (sameNumber(unexpected, actual)) {
            throw new AssertionFailure("values are numerically equal (only their types differ)",
                    "anything but " + Values.describeTyped(unexpected), Values.describeTyped(actual), null);
        }
    }

    /** Whether two boxed primitive numbers of different types hold the same value. */
    private static boolean sameNumber(Object left, Object right) {
        if (!(isBoxedNumber(left) && isBoxedNumber(right)) || left.getClass() == right.getClass()) {
            return false;
        }
        var a = (Number) left;
        var b = (Number) right;
        if (!Double.isFinite(a.doubleValue()) || !Double.isFinite(b.doubleValue())) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        return exact(a).compareTo(exact(b)) == 0;
    }

    private static boolean isBoxedNumber(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double;
    }

    /** The exact value of a finite boxed number (a float widens to double without loss). */
    private static BigDecimal exact(Number number) {
        return number instanceof Float || number instanceof Double
                ? new BigDecimal(number.doubleValue())
                : BigDecimal.valueOf(number.longValue());
    }

    /**
     * Asserts that two references point to the same object.
     *
     * @param expected the expected object
     * @param actual   the actual object
     * @throws AssertionFailure if they are different objects
     */
    public static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionFailure("not the same object",
                    identity(expected), identity(actual), null);
        }
    }

    private static String identity(Object value) {
        return value == null ? "null"
                : Values.describe(value) + " (" + value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value)) + ")";
    }

    // ---- truth and null ----------------------------------------------------------------

    /**
     * Asserts that a condition holds.
     *
     * @param condition the condition
     * @throws AssertionFailure if it is false
     */
    public static void assertTrue(boolean condition) {
        assertTrue(condition, "condition is false");
    }

    /**
     * Asserts that a condition holds.
     *
     * @param condition the condition
     * @param message   what is checked
     * @throws AssertionFailure if it is false
     */
    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionFailure(message, "true", "false", null);
        }
    }

    /**
     * Asserts that a condition does not hold.
     *
     * @param condition the condition
     * @throws AssertionFailure if it is true
     */
    public static void assertFalse(boolean condition) {
        assertFalse(condition, "condition is true");
    }

    /**
     * Asserts that a condition does not hold.
     *
     * @param condition the condition
     * @param message   what is checked
     * @throws AssertionFailure if it is true
     */
    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionFailure(message, "false", "true", null);
        }
    }

    /**
     * Asserts that a value is {@code null}.
     *
     * @param actual the value
     * @throws AssertionFailure if it is not
     */
    public static void assertNull(Object actual) {
        if (actual != null) {
            throw new AssertionFailure("value is not null", "null", Values.describe(actual), null);
        }
    }

    /**
     * Asserts that a value is not {@code null}.
     *
     * @param actual the value
     * @param <T>    its type
     * @return {@code actual}, for further checks
     * @throws AssertionFailure if it is {@code null}
     */
    public static <T> T assertNotNull(T actual) {
        if (actual == null) {
            throw new AssertionFailure("value is null", "not null", "null", null);
        }
        return actual;
    }

    // ---- exceptions --------------------------------------------------------------------

    /**
     * Asserts that {@code body} throws an exception of type {@code type} (or a subtype).
     *
     * @param type the expected type
     * @param body the code that should throw
     * @param <T>  the expected type
     * @return the thrown exception, for further checks
     * @throws AssertionFailure if nothing or something of another type is thrown; a wrong
     *                          exception is the failure's cause
     */
    public static <T extends Throwable> T assertThrows(Class<T> type, Body body) {
        Objects.requireNonNull(type, "type");
        try {
            body.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return type.cast(thrown);
            }
            throw new AssertionFailure("wrong exception thrown", type.getName(), String.valueOf(thrown), thrown);
        }
        throw new AssertionFailure("nothing thrown", type.getName(), "no exception", null);
    }

    /**
     * Asserts that {@code body} throws an exception of type {@code type} (or a subtype) whose
     * message equals {@code message}.
     *
     * @param type    the expected type
     * @param message the expected message, exactly
     * @param body    the code that should throw
     * @param <T>     the expected type
     * @return the thrown exception, for further checks
     * @throws AssertionFailure if nothing, something of another type, or an exception with
     *                          another message is thrown; a wrong exception is the failure's cause
     */
    public static <T extends Throwable> T assertThrows(Class<T> type, String message, Body body) {
        var thrown = assertThrows(type, body);
        if (!Objects.equals(message, thrown.getMessage())) {
            throw new AssertionFailure("exception message differs (" + thrown.getClass().getName() + ")",
                    Values.describe(message), Values.describe(thrown.getMessage()), thrown);
        }
        return thrown;
    }

    // ---- collections -------------------------------------------------------------------

    /**
     * Asserts the size of a collection.
     *
     * @param expected the expected size
     * @param actual   the collection
     * @throws AssertionFailure if the size differs; the message shows the elements
     */
    public static void assertSize(int expected, Collection<?> actual) {
        Objects.requireNonNull(actual, "actual");
        if (actual.size() != expected) {
            throw new AssertionFailure("collection size differs", Integer.toString(expected),
                    actual.size() + " " + Values.describe(actual), null);
        }
    }

    /**
     * Asserts that a collection is empty.
     *
     * @param actual the collection
     * @throws AssertionFailure if it has elements; the message shows them
     */
    public static void assertEmpty(Collection<?> actual) {
        Objects.requireNonNull(actual, "actual");
        if (!actual.isEmpty()) {
            throw new AssertionFailure("collection is not empty", "[]", Values.describe(actual), null);
        }
    }

    /**
     * Asserts that a collection contains each of the given elements (in any order, possibly
     * among others).
     *
     * @param actual   the collection
     * @param elements the elements it must contain
     * @throws AssertionFailure if an element is missing; the message lists the missing ones
     */
    public static void assertContains(Collection<?> actual, Object... elements) {
        Objects.requireNonNull(actual, "actual");
        var missing = new ArrayList<>();
        for (var element : elements) {
            if (actual.stream().noneMatch(a -> Objects.deepEquals(a, element))) {
                missing.add(element);
            }
        }
        if (!missing.isEmpty()) {
            // Elements that read like one that is there (1 and 1L) are shown with their classes.
            boolean confusing = missing.stream().anyMatch(m -> actual.stream()
                    .anyMatch(a -> Values.describe(a).equals(Values.describe(m))));
            var show = Values.describer(confusing);
            throw new AssertionFailure("collection lacks " + show.apply(missing),
                    "contains " + show.apply(Arrays.asList(elements)), show.apply(actual), null);
        }
    }

    /**
     * Asserts that two sequences have the same elements in the same order.
     *
     * @param expected the expected elements
     * @param actual   the actual elements
     * @throws AssertionFailure if they differ; the message names the first differing index
     */
    public static void assertInOrder(List<?> expected, List<?> actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        int common = Math.min(expected.size(), actual.size());
        for (int i = 0; i < common; i++) {
            if (!Objects.deepEquals(expected.get(i), actual.get(i))) {
                var shown = Values.describeBoth(expected.get(i), actual.get(i));
                var lists = Values.describeBoth(expected, actual);
                throw new AssertionFailure("lists differ at index " + i + ": expected " + shown[0]
                        + " but was " + shown[1], lists[0], lists[1], null);
            }
        }
        if (expected.size() != actual.size()) {
            throw new AssertionFailure("lists differ in size: expected " + expected.size() + " elements but was "
                    + actual.size(), Values.describe(expected), Values.describe(actual), null);
        }
    }

    /**
     * Asserts that two collections have the same elements, each as many times, in any order.
     *
     * @param expected the expected elements
     * @param actual   the actual elements
     * @throws AssertionFailure if they differ; the message lists the missing and the unexpected elements
     */
    public static void assertAnyOrder(Collection<?> expected, Collection<?> actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        var missing = new ArrayList<Object>(expected);
        var unexpected = new ArrayList<Object>();
        for (var element : actual) {
            int at = indexOf(missing, element);
            if (at >= 0) {
                missing.remove(at);
            } else {
                unexpected.add(element);
            }
        }
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            // Elements that read the same although they differ (1 and 1L) are shown with their classes.
            boolean confusing = Values.describe(missing).equals(Values.describe(unexpected));
            var show = Values.describer(confusing);
            throw new AssertionFailure("collections differ: missing " + show.apply(missing) + ", unexpected "
                    + show.apply(unexpected), show.apply(expected), show.apply(actual), null);
        }
    }

    private static int indexOf(List<Object> list, Object element) {
        for (int i = 0; i < list.size(); i++) {
            if (Objects.deepEquals(list.get(i), element)) {
                return i;
            }
        }
        return -1;
    }
}
