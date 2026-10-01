/**
 * Spola's type-safe configuration, a replacement for Spring Boot's
 * {@code application.properties}, {@code @Value}, {@code @ConfigurationProperties} and
 * profiles, without annotations or reflection.
 *
 * <p>A {@link spola.config.Config} is built from explicitly created
 * {@link spola.config.Source sources} (properties files, environment variables, system
 * properties, command-line arguments, maps) in a stated order where a later source wins.
 * Profile sources are added where the application says, placeholders ({@code ${key}},
 * {@code ${key:default}}) are resolved once all sources are merged, and the result is bound
 * to records by functions the application writes ({@link spola.config.Config#bind}). Every
 * problem (missing files, missing keys, invalid values, placeholder cycles) is collected and
 * reported in one {@link spola.config.ConfigException}. Sensitive values are masked in
 * error messages and in {@link spola.config.Config#describe()}.
 */
module spola.config {
    exports spola.config;
}
