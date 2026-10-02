package spola.util;

/**
 * The single rule for which errors {@code spola.util}'s logging lets through. Logging catches
 * everything a bad value, a bad exception or a bad backend can throw, so that writing a log
 * record never breaks the caller. The one exception is a fatal {@link VirtualMachineError}
 * other than {@link StackOverflowError} (such as {@link OutOfMemoryError}): the JVM is in
 * trouble and that must not be hidden. A {@code StackOverflowError} is caught, since a recursive
 * {@code toString}/{@code getMessage} is a bug in one value, not in the JVM, and the stack is
 * unwound by the time it is caught.
 *
 * <p>Used by {@link Log}, {@link MessageFormatter}, {@link LineFormatter} and
 * {@link LineHandler}. The rule is written here and nowhere else.
 */
final class Failures {

    private Failures() {
    }

    /**
     * Rethrows {@code failure} if it is fatal by the rule above; otherwise returns, and the
     * caller handles (logs or swallows) it.
     *
     * @param failure what was caught
     */
    static void rethrowIfFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal && !(fatal instanceof StackOverflowError)) {
            throw fatal;
        }
    }
}
