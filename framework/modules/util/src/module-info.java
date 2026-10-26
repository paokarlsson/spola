/**
 * Spola's small helpers that replace the parts of Lombok Java 25 does not already cover, plus console logging
 * that replaces Spring Boot's default log format and {@code logging.level.*}.
 *
 * <ul>
 *   <li>{@link spola.util.Log}: a logger with SLF4J-style {@code {}} placeholders on top of
 *       {@link System.Logger}, created explicitly ({@code Log.forClass(Order.class)}); replaces
 *       Lombok's {@code @Slf4j} and {@code @Log}.</li>
 *   <li>{@link spola.util.Logging}: an explicit call that installs a one-line console format on
 *       the {@code java.util.logging} root logger and sets levels per logger-name prefix.</li>
 *   <li>{@link spola.util.Lazy}: a thread-safe, compute-once value; replaces
 *       {@code @Getter(lazy = true)}.</li>
 * </ul>
 *
 * <p>Lombok's {@code @ToString} and {@code @SneakyThrows} have no counterpart here: a record's
 * own {@code toString} (overridden where a value is secret) and a plain {@code try}/{@code catch}
 * with {@link java.io.UncheckedIOException} already cover them in Java 25.
 *
 * <p>Nothing is configured as a side effect of loading a class or of logging: the console
 * format and the levels exist only after the application calls
 * {@link spola.util.Logging#configure(spola.util.LoggingSettings)}.
 */
module spola.util {
    requires java.logging;

    exports spola.util;
}
