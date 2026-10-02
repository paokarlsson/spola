package spola.util;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A logger with SLF4J-style {@code {}} placeholders on top of {@link System.Logger}. Replaces
 * Lombok's {@code @Slf4j}/{@code @Log}: declare it yourself, typically in a
 * {@code static final} field.
 *
 * {@snippet :
 * private static final Log LOG = Log.forClass(OrderService.class);
 *
 * LOG.info("order {} placed by {}", order.id(), customer);
 * LOG.error("payment failed for order {}", order.id(), exception);
 * }
 *
 * <h2>Creating</h2>
 * {@link #forClass(Class)} uses {@link System#getLogger(String)} with the class's fully
 * qualified name ({@link Class#getName()}); {@link #of(System.Logger)} wraps a logger you
 * supply (in tests, say). The class is never found by inspecting the stack.
 *
 * <h2>Levels</h2>
 * <table>
 *   <caption>Log methods and {@code System.Logger} levels</caption>
 *   <tr><th>Method</th><th>{@link System.Logger.Level}</th></tr>
 *   <tr><td>{@code trace}, {@code isTraceEnabled}</td><td>{@code TRACE}</td></tr>
 *   <tr><td>{@code debug}, {@code isDebugEnabled}</td><td>{@code DEBUG}</td></tr>
 *   <tr><td>{@code info}, {@code isInfoEnabled}</td><td>{@code INFO}</td></tr>
 *   <tr><td>{@code warn}, {@code isWarnEnabled}</td><td>{@code WARNING}</td></tr>
 *   <tr><td>{@code error}, {@code isErrorEnabled}</td><td>{@code ERROR}</td></tr>
 * </table>
 *
 * <h2>Placeholder rules</h2>
 * The message is scanned from left to right once (every logging method with a
 * {@code String} message formats it, also the one without arguments):
 * <ol>
 *   <li>Each {@code {}} is replaced by the next argument, in order.</li>
 *   <li>When the arguments run out, the remaining {@code {}} are left as they are.</li>
 *   <li>Arguments left over when the message ends are ignored, except the last argument:
 *       if it is a {@link Throwable} and no placeholder consumed it, it is passed to the
 *       logger as the exception (and so printed with its stack trace, not in the text).
 *       A {@code Throwable} that a placeholder consumed is written as text
 *       ({@code toString()}) and is not logged as the exception.</li>
 *   <li>{@code \{}} (one backslash) writes a literal {@code {}} and consumes no argument.
 *       {@code \\{}} (two backslashes) writes one backslash followed by a placeholder. Any
 *       other backslash is written as it is.</li>
 *   <li>An argument is written as: {@code null} as {@code null}; an object array with
 *       {@link java.util.Arrays#deepToString(Object[])}; a primitive array with
 *       {@link java.util.Arrays#toString(int[]) Arrays.toString}; a {@link Deferred} (from
 *       {@link #defer(Supplier)}) by calling its supplier and writing the result by these
 *       rules; anything else with {@code toString()} (a {@code null} result is written as
 *       {@code null}).</li>
 *   <li>If {@code toString()} or a deferred supplier throws, the message is still logged:
 *       the argument is written as {@code [toString() threw <class name>]} or
 *       {@code [supplier threw <class name>]}. The exception's message is not written, since
 *       it could contain the value. Only {@link VirtualMachineError}s other than
 *       {@link StackOverflowError} (such as {@link OutOfMemoryError}) propagate.</li>
 *   <li>A {@code null} message is written as {@code null}.</li>
 * </ol>
 *
 * <h2>No work when a level is off</h2>
 * Every method first asks {@link System.Logger#isLoggable}. When the level is off, the message
 * is not formatted, no argument's {@code toString()} is called and no supplier is called
 * (neither a message {@link Supplier} nor a {@link #defer(Supplier) deferred} argument).
 * The methods with one or two arguments do not even allocate an argument array then; the
 * varargs methods get their array from the caller (Java allocates it at the call site).
 *
 * <h2>Threads</h2>
 * A {@code Log} is immutable and thread-safe; formatting uses only local state. Whether the
 * underlying {@code System.Logger} is thread-safe is up to it (the JDK's are).
 */
public final class Log {

    private static final Object[] NO_ARGUMENTS = {};

    private final System.Logger logger;

    private Log(System.Logger logger) {
        this.logger = logger;
    }

    /**
     * A log for a class: {@code System.getLogger(type.getName())}.
     *
     * @param type the class whose fully qualified name is the logger name
     * @return the log
     */
    public static Log forClass(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return new Log(System.getLogger(type.getName()));
    }

    /**
     * A log that writes to the given logger, for example a recording logger in a test.
     *
     * @param logger the logger
     * @return the log
     */
    public static Log of(System.Logger logger) {
        return new Log(Objects.requireNonNull(logger, "logger"));
    }

    /**
     * An argument whose value is computed only if the message is logged. Pass the result as
     * an argument: {@code log.debug("state {}", Log.defer(() -> expensiveDump()))}. The
     * supplier is called at most once per logged message that consumes it, and not at all
     * when the level is off.
     *
     * @param supplier computes the argument
     * @return the deferred argument
     */
    public static Deferred defer(Supplier<?> supplier) {
        return new Deferred(Objects.requireNonNull(supplier, "supplier"));
    }

    /**
     * A deferred argument, created by {@link Log#defer(Supplier)}. When a placeholder consumes
     * it, its supplier is called and the result is written by the usual rules. Its own
     * {@link #toString()} does the same, so it also works outside a {@code Log}.
     */
    public static final class Deferred {

        private final Supplier<?> supplier;

        private Deferred(Supplier<?> supplier) {
            this.supplier = supplier;
        }

        Supplier<?> supplier() {
            return supplier;
        }

        /**
         * Calls the supplier and renders the result by the placeholder rules of {@link Log}.
         *
         * @return the rendered value
         */
        @Override
        public String toString() {
            return MessageFormatter.render(this);
        }
    }

    /**
     * The logger this log writes to.
     *
     * @return the logger
     */
    public System.Logger logger() {
        return logger;
    }

    /**
     * The logger's name.
     *
     * @return {@link System.Logger#getName()}
     */
    public String name() {
        return logger.getName();
    }

    /**
     * Shows the logger name.
     *
     * @return {@code Log[<name>]}
     */
    @Override
    public String toString() {
        return "Log[" + logger.getName() + "]";
    }

    /**
     * Whether {@code trace} messages are logged: {@link System.Logger#isLoggable} at
     * {@link System.Logger.Level#TRACE}.
     *
     * @return {@code true} if the level is enabled
     */
    public boolean isTraceEnabled() {
        return logger.isLoggable(Level.TRACE);
    }

    /**
     * Logs a message at {@link System.Logger.Level#TRACE}. The message is formatted with no
     * arguments, so {@code {}} stays and {@code \{}} becomes {@code {}}.
     *
     * @param message the message
     */
    public void trace(String message) {
        log(Level.TRACE, message, NO_ARGUMENTS);
    }

    /**
     * Logs a message with one argument at {@link System.Logger.Level#TRACE}. If the argument is
     * a {@link Throwable} that no placeholder consumed, it is logged as the exception.
     *
     * @param format the message with {@code {}} placeholders
     * @param argument the argument
     */
    public void trace(String format, Object argument) {
        if (logger.isLoggable(Level.TRACE)) {
            emit(Level.TRACE, format, new Object[] {argument});
        }
    }

    /**
     * Logs a message with two arguments at {@link System.Logger.Level#TRACE}.
     *
     * @param format the message with {@code {}} placeholders
     * @param first the first argument
     * @param second the second argument; logged as the exception if it is an unconsumed
     *     {@link Throwable}
     */
    public void trace(String format, Object first, Object second) {
        if (logger.isLoggable(Level.TRACE)) {
            emit(Level.TRACE, format, new Object[] {first, second});
        }
    }

    /**
     * Logs a message with any number of arguments at {@link System.Logger.Level#TRACE}.
     *
     * @param format the message with {@code {}} placeholders
     * @param arguments the arguments; the last one is logged as the exception if it is an
     *     unconsumed {@link Throwable}
     */
    public void trace(String format, Object... arguments) {
        log(Level.TRACE, format, arguments);
    }

    /**
     * Logs a lazily built message at {@link System.Logger.Level#TRACE}. The supplier is called
     * only if the level is enabled. Its result is logged as it is, without placeholder
     * formatting.
     *
     * @param message supplies the message
     */
    public void trace(Supplier<String> message) {
        log(Level.TRACE, message, null);
    }

    /**
     * Logs a lazily built message and an exception at {@link System.Logger.Level#TRACE}. The
     * supplier is called only if the level is enabled.
     *
     * @param message supplies the message
     * @param thrown the exception, logged with its stack trace; may be {@code null}
     */
    public void trace(Supplier<String> message, Throwable thrown) {
        log(Level.TRACE, message, thrown);
    }

    /**
     * Whether {@code debug} messages are logged: {@link System.Logger#isLoggable} at
     * {@link System.Logger.Level#DEBUG}.
     *
     * @return {@code true} if the level is enabled
     */
    public boolean isDebugEnabled() {
        return logger.isLoggable(Level.DEBUG);
    }

    /**
     * Logs a message at {@link System.Logger.Level#DEBUG}. The message is formatted with no
     * arguments, so {@code {}} stays and {@code \{}} becomes {@code {}}.
     *
     * @param message the message
     */
    public void debug(String message) {
        log(Level.DEBUG, message, NO_ARGUMENTS);
    }

    /**
     * Logs a message with one argument at {@link System.Logger.Level#DEBUG}. If the argument is
     * a {@link Throwable} that no placeholder consumed, it is logged as the exception.
     *
     * @param format the message with {@code {}} placeholders
     * @param argument the argument
     */
    public void debug(String format, Object argument) {
        if (logger.isLoggable(Level.DEBUG)) {
            emit(Level.DEBUG, format, new Object[] {argument});
        }
    }

    /**
     * Logs a message with two arguments at {@link System.Logger.Level#DEBUG}.
     *
     * @param format the message with {@code {}} placeholders
     * @param first the first argument
     * @param second the second argument; logged as the exception if it is an unconsumed
     *     {@link Throwable}
     */
    public void debug(String format, Object first, Object second) {
        if (logger.isLoggable(Level.DEBUG)) {
            emit(Level.DEBUG, format, new Object[] {first, second});
        }
    }

    /**
     * Logs a message with any number of arguments at {@link System.Logger.Level#DEBUG}.
     *
     * @param format the message with {@code {}} placeholders
     * @param arguments the arguments; the last one is logged as the exception if it is an
     *     unconsumed {@link Throwable}
     */
    public void debug(String format, Object... arguments) {
        log(Level.DEBUG, format, arguments);
    }

    /**
     * Logs a lazily built message at {@link System.Logger.Level#DEBUG}. The supplier is called
     * only if the level is enabled. Its result is logged as it is, without placeholder
     * formatting.
     *
     * @param message supplies the message
     */
    public void debug(Supplier<String> message) {
        log(Level.DEBUG, message, null);
    }

    /**
     * Logs a lazily built message and an exception at {@link System.Logger.Level#DEBUG}. The
     * supplier is called only if the level is enabled.
     *
     * @param message supplies the message
     * @param thrown the exception, logged with its stack trace; may be {@code null}
     */
    public void debug(Supplier<String> message, Throwable thrown) {
        log(Level.DEBUG, message, thrown);
    }

    /**
     * Whether {@code info} messages are logged: {@link System.Logger#isLoggable} at
     * {@link System.Logger.Level#INFO}.
     *
     * @return {@code true} if the level is enabled
     */
    public boolean isInfoEnabled() {
        return logger.isLoggable(Level.INFO);
    }

    /**
     * Logs a message at {@link System.Logger.Level#INFO}. The message is formatted with no
     * arguments, so {@code {}} stays and {@code \{}} becomes {@code {}}.
     *
     * @param message the message
     */
    public void info(String message) {
        log(Level.INFO, message, NO_ARGUMENTS);
    }

    /**
     * Logs a message with one argument at {@link System.Logger.Level#INFO}. If the argument is
     * a {@link Throwable} that no placeholder consumed, it is logged as the exception.
     *
     * @param format the message with {@code {}} placeholders
     * @param argument the argument
     */
    public void info(String format, Object argument) {
        if (logger.isLoggable(Level.INFO)) {
            emit(Level.INFO, format, new Object[] {argument});
        }
    }

    /**
     * Logs a message with two arguments at {@link System.Logger.Level#INFO}.
     *
     * @param format the message with {@code {}} placeholders
     * @param first the first argument
     * @param second the second argument; logged as the exception if it is an unconsumed
     *     {@link Throwable}
     */
    public void info(String format, Object first, Object second) {
        if (logger.isLoggable(Level.INFO)) {
            emit(Level.INFO, format, new Object[] {first, second});
        }
    }

    /**
     * Logs a message with any number of arguments at {@link System.Logger.Level#INFO}.
     *
     * @param format the message with {@code {}} placeholders
     * @param arguments the arguments; the last one is logged as the exception if it is an
     *     unconsumed {@link Throwable}
     */
    public void info(String format, Object... arguments) {
        log(Level.INFO, format, arguments);
    }

    /**
     * Logs a lazily built message at {@link System.Logger.Level#INFO}. The supplier is called
     * only if the level is enabled. Its result is logged as it is, without placeholder
     * formatting.
     *
     * @param message supplies the message
     */
    public void info(Supplier<String> message) {
        log(Level.INFO, message, null);
    }

    /**
     * Logs a lazily built message and an exception at {@link System.Logger.Level#INFO}. The
     * supplier is called only if the level is enabled.
     *
     * @param message supplies the message
     * @param thrown the exception, logged with its stack trace; may be {@code null}
     */
    public void info(Supplier<String> message, Throwable thrown) {
        log(Level.INFO, message, thrown);
    }

    /**
     * Whether {@code warn} messages are logged: {@link System.Logger#isLoggable} at
     * {@link System.Logger.Level#WARNING}.
     *
     * @return {@code true} if the level is enabled
     */
    public boolean isWarnEnabled() {
        return logger.isLoggable(Level.WARNING);
    }

    /**
     * Logs a message at {@link System.Logger.Level#WARNING}. The message is formatted with no
     * arguments, so {@code {}} stays and {@code \{}} becomes {@code {}}.
     *
     * @param message the message
     */
    public void warn(String message) {
        log(Level.WARNING, message, NO_ARGUMENTS);
    }

    /**
     * Logs a message with one argument at {@link System.Logger.Level#WARNING}. If the argument is
     * a {@link Throwable} that no placeholder consumed, it is logged as the exception.
     *
     * @param format the message with {@code {}} placeholders
     * @param argument the argument
     */
    public void warn(String format, Object argument) {
        if (logger.isLoggable(Level.WARNING)) {
            emit(Level.WARNING, format, new Object[] {argument});
        }
    }

    /**
     * Logs a message with two arguments at {@link System.Logger.Level#WARNING}.
     *
     * @param format the message with {@code {}} placeholders
     * @param first the first argument
     * @param second the second argument; logged as the exception if it is an unconsumed
     *     {@link Throwable}
     */
    public void warn(String format, Object first, Object second) {
        if (logger.isLoggable(Level.WARNING)) {
            emit(Level.WARNING, format, new Object[] {first, second});
        }
    }

    /**
     * Logs a message with any number of arguments at {@link System.Logger.Level#WARNING}.
     *
     * @param format the message with {@code {}} placeholders
     * @param arguments the arguments; the last one is logged as the exception if it is an
     *     unconsumed {@link Throwable}
     */
    public void warn(String format, Object... arguments) {
        log(Level.WARNING, format, arguments);
    }

    /**
     * Logs a lazily built message at {@link System.Logger.Level#WARNING}. The supplier is called
     * only if the level is enabled. Its result is logged as it is, without placeholder
     * formatting.
     *
     * @param message supplies the message
     */
    public void warn(Supplier<String> message) {
        log(Level.WARNING, message, null);
    }

    /**
     * Logs a lazily built message and an exception at {@link System.Logger.Level#WARNING}. The
     * supplier is called only if the level is enabled.
     *
     * @param message supplies the message
     * @param thrown the exception, logged with its stack trace; may be {@code null}
     */
    public void warn(Supplier<String> message, Throwable thrown) {
        log(Level.WARNING, message, thrown);
    }

    /**
     * Whether {@code error} messages are logged: {@link System.Logger#isLoggable} at
     * {@link System.Logger.Level#ERROR}.
     *
     * @return {@code true} if the level is enabled
     */
    public boolean isErrorEnabled() {
        return logger.isLoggable(Level.ERROR);
    }

    /**
     * Logs a message at {@link System.Logger.Level#ERROR}. The message is formatted with no
     * arguments, so {@code {}} stays and {@code \{}} becomes {@code {}}.
     *
     * @param message the message
     */
    public void error(String message) {
        log(Level.ERROR, message, NO_ARGUMENTS);
    }

    /**
     * Logs a message with one argument at {@link System.Logger.Level#ERROR}. If the argument is
     * a {@link Throwable} that no placeholder consumed, it is logged as the exception.
     *
     * @param format the message with {@code {}} placeholders
     * @param argument the argument
     */
    public void error(String format, Object argument) {
        if (logger.isLoggable(Level.ERROR)) {
            emit(Level.ERROR, format, new Object[] {argument});
        }
    }

    /**
     * Logs a message with two arguments at {@link System.Logger.Level#ERROR}.
     *
     * @param format the message with {@code {}} placeholders
     * @param first the first argument
     * @param second the second argument; logged as the exception if it is an unconsumed
     *     {@link Throwable}
     */
    public void error(String format, Object first, Object second) {
        if (logger.isLoggable(Level.ERROR)) {
            emit(Level.ERROR, format, new Object[] {first, second});
        }
    }

    /**
     * Logs a message with any number of arguments at {@link System.Logger.Level#ERROR}.
     *
     * @param format the message with {@code {}} placeholders
     * @param arguments the arguments; the last one is logged as the exception if it is an
     *     unconsumed {@link Throwable}
     */
    public void error(String format, Object... arguments) {
        log(Level.ERROR, format, arguments);
    }

    /**
     * Logs a lazily built message at {@link System.Logger.Level#ERROR}. The supplier is called
     * only if the level is enabled. Its result is logged as it is, without placeholder
     * formatting.
     *
     * @param message supplies the message
     */
    public void error(Supplier<String> message) {
        log(Level.ERROR, message, null);
    }

    /**
     * Logs a lazily built message and an exception at {@link System.Logger.Level#ERROR}. The
     * supplier is called only if the level is enabled.
     *
     * @param message supplies the message
     * @param thrown the exception, logged with its stack trace; may be {@code null}
     */
    public void error(Supplier<String> message, Throwable thrown) {
        log(Level.ERROR, message, thrown);
    }

    private void log(Level level, String format, Object[] arguments) {
        if (logger.isLoggable(level)) {
            emit(level, format, arguments);
        }
    }

    private void emit(Level level, String format, Object[] arguments) {
        var formatted = MessageFormatter.format(format, arguments);
        if (formatted.thrown() == null) {
            logger.log(level, formatted.message());
        } else {
            logger.log(level, formatted.message(), formatted.thrown());
        }
    }

    private void log(Level level, Supplier<String> message, Throwable thrown) {
        if (!logger.isLoggable(level)) {
            return;
        }
        String text;
        try {
            text = String.valueOf(message.get());
        } catch (Throwable failure) {
            text = MessageFormatter.marker("message supplier", failure);
        }
        if (thrown == null) {
            logger.log(level, text);
        } else {
            logger.log(level, text, thrown);
        }
    }
}
