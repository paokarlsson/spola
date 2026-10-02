/**
 * Spola's small helpers that replace the non-builder parts of Lombok, plus console logging
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
 *   <li>{@link spola.util.Unchecked}: throwing functional interfaces and adapters that wrap
 *       checked exceptions; replaces {@code @SneakyThrows} without a sneaky throw.</li>
 *   <li>{@link spola.util.ToString}: a record-style {@code toString} builder that hides secret
 *       values; replaces {@code @ToString.Exclude}.</li>
 * </ul>
 *
 * <p>Nothing is configured as a side effect of loading a class or of logging: the console
 * format and the levels exist only after the application calls
 * {@link spola.util.Logging#configure(spola.util.LoggingSettings)}.
 */
module spola.util {
    requires java.logging;

    exports spola.util;
}
