/**
 * Spola's validation: validators as plain Java code, without annotations and without
 * reflection. It replaces what Spring Boot applications use Jakarta Bean Validation (Hibernate
 * Validator) for: {@code @NotNull}, {@code @NotBlank}, {@code @NotEmpty}, {@code @Size},
 * {@code @Min}, {@code @Max}, {@code @DecimalMin}, {@code @Digits}, {@code @Positive},
 * {@code @Email}, {@code @Pattern}, {@code @Past}, {@code @Future}, {@code @Valid} on nested
 * objects and lists, class-level rules ({@code @AssertTrue}), Spring's
 * {@code org.springframework.validation.Validator} and {@code ConstraintViolationException}.
 *
 * <ul>
 *   <li>{@link spola.validation.Validator}: the core, a functional interface
 *       ({@code validate(value, errors)}) with {@code check} and {@code require}, and
 *       {@link spola.validation.Validator#builder()} for records and other objects.</li>
 *   <li>{@link spola.validation.Errors}: the collector of one validation, with paths in the
 *       notation of {@code spola.json.codec} ({@code $.lines[3].qty}).</li>
 *   <li>{@link spola.validation.Constraint} and {@link spola.validation.Constraints}: checks
 *       of single values, each with a stable code and an English default message.</li>
 *   <li>{@link spola.validation.Validated}, {@link spola.validation.Violation},
 *       {@link spola.validation.ValidationException}: the result.</li>
 * </ul>
 *
 * <p>The module requires no other Spola module, so it can be used on its own. Type errors
 * (a text constraint on an {@code int} field) are compile errors.
 *
 * <p>{@code requires static java.compiler} is for the module's tests only (they compile small
 * sources in memory to show which validators do not compile); no main code uses it.
 */
module spola.validation {
    requires static java.compiler;

    exports spola.validation;
}
