package spola.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Validation of values of type {@code T} as plain Java code, the Spola counterpart of Spring's
 * {@code org.springframework.validation.Validator} and Jakarta Bean Validation. A validator
 * reports what is wrong with a value to an {@link Errors}; {@link #check} gives the whole
 * report and {@link #require} gives the value or throws {@link ValidationException}.
 *
 * <p>Write one as a lambda, or build one for a record (or any object) from getters and
 * {@link Constraint}s:
 *
 * <pre>{@code
 * static final Validator<OrderLine> LINE = Validator.<OrderLine>builder()
 *         .field("sku", OrderLine::sku, notBlank(), matches(SKU))
 *         .field("quantity", OrderLine::quantity, range(1, 99))
 *         .build();
 *
 * Validated<OrderLine> result = LINE.check(line);   // Valid or Invalid
 * OrderLine valid = LINE.require(line);             // or ValidationException
 * }</pre>
 *
 * <p><b>Order:</b> violations come in declaration order, depth first: each step of a built
 * validator in the order it was added, with the violations of a nested value right after those
 * of the field itself. All constraints of a field run, in order; none stops the others.
 *
 * <p><b>{@code null}:</b> a built validator accepts a {@code null} value (only
 * {@code notNull} and its kin reject {@code null}). Inside it, {@code nested},
 * {@code eachNested}, {@code each} and {@code optional} skip a {@code null} value (and an empty
 * {@code Optional}); a {@code null} element of a list is given to the element constraints of
 * {@code each}, so {@code each(..., notNull())} reports it, but skipped by
 * {@code eachNested}. Predicates of rules and of {@code when} are never called with
 * {@code null}. A validator written as a lambda gets what it is given, {@code null} included.
 *
 * <p><b>Limit:</b> a result keeps at most {@link Validated#MAX_VIOLATIONS} (100) violations
 * and counts all of them.
 *
 * <p><b>Exceptions</b> from application code (a getter, a predicate, a constraint's test, a
 * validator of your own) are not caught: they propagate out of {@code check} and
 * {@code require} unchanged, and no partial report is returned. There is no cycle detection:
 * validating a graph that contains itself does not end (until the stack overflows).
 *
 * <p>Built validators are immutable and can be shared ({@code static final}) by any number of
 * threads; each {@code check}/{@code require} has its own {@code Errors}.
 *
 * @param <T> the type of the validated values
 */
@FunctionalInterface
public interface Validator<T> {

    /**
     * Reports to {@code errors} everything that is wrong with {@code value}. Called by
     * {@link #check} and by validators that contain this one; call {@link Errors#reject} on
     * {@code errors} (or on {@link Errors#at} and its siblings) for each problem.
     *
     * @param value  the value; may be {@code null}
     * @param errors the collector at the value's path
     */
    void validate(T value, Errors errors);

    /**
     * Validates the value and returns the whole report.
     *
     * @param value the value; may be {@code null}
     * @return {@link Validated.Valid} with the same instance if nothing was reported, otherwise
     *         {@link Validated.Invalid} with the first 100 violations in order and the count
     */
    default Validated<T> check(T value) {
        var errors = Errors.root();
        validate(value, errors);
        return errors.finish(value);
    }

    /**
     * Validates the value and returns it if it is valid.
     *
     * @param value the value; may be {@code null}
     * @return the same instance
     * @throws ValidationException with the violations and count of {@link #check} if anything
     *                             was reported
     */
    default T require(T value) {
        return switch (check(value)) {
            case Validated.Valid<T> valid -> valid.value();
            case Validated.Invalid<T> invalid -> throw new ValidationException(invalid);
        };
    }

    /**
     * A validator of a single value at the validated path ({@code $}): every constraint, in
     * order.
     *
     * @param constraints the constraints
     * @param <T>         the type of the values
     * @return the validator
     * @throws NullPointerException if {@code constraints} or one of them is {@code null}
     */
    @SafeVarargs
    static <T> Validator<T> of(Constraint<? super T>... constraints) {
        if (constraints == null) {
            throw new NullPointerException("constraints");
        }
        var copy = new ArrayList<Constraint<? super T>>(constraints.length);
        for (var constraint : constraints) {
            copy.add(constraint);
        }
        var list = Builder.checked(copy);
        return (value, errors) -> {
            for (var constraint : list) {
                constraint.check(value, errors);
            }
        };
    }

    /**
     * A validator that asks {@code validator} for the real one the first time it is used, so a
     * validator can refer to itself (recursive structures, like {@code LazyCodec} in
     * {@code spola.json.codec}):
     *
     * <pre>{@code
     * static final Validator<Node> NODE = Validator.<Node>builder()
     *         .field("name", Node::name, notBlank())
     *         .eachNested("children", Node::children, Validator.lazy(() -> Tree.NODE))
     *         .build();
     * }</pre>
     *
     * The supplier is asked on first use (by each thread that races to it first) and its
     * validator kept.
     *
     * @param validator the supplier of the validator; must not return {@code null}
     * @param <T>       the type of the values
     * @return the validator
     * @throws NullPointerException if {@code validator} is {@code null}
     */
    static <T> Validator<T> lazy(Supplier<Validator<T>> validator) {
        Objects.requireNonNull(validator, "validator");
        return new LazyValidator<>(validator);
    }

    /**
     * A builder of a validator for records and other objects.
     *
     * @param <T> the type of the values
     * @return a new, empty builder
     */
    static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Builds a validator from steps, each added in order and run in that order: fields with
     * constraints, nested validators, lists, rules and other validators. Every argument is
     * checked when the step is added: a {@code null} getter, constraint, validator or predicate
     * throws {@link NullPointerException}; a {@code null} name throws
     * {@link NullPointerException} and an empty one {@link IllegalArgumentException}.
     *
     * <p>A builder is not thread-safe; the validators it builds are immutable and are not
     * affected by later steps.
     *
     * @param <T> the type of the values
     */
    final class Builder<T> {

        private final List<Validator<? super T>> steps = new ArrayList<>();

        private Builder() {
        }

        /**
         * A field (record component, getter) checked by constraints: {@code $.name}.
         * {@code @NotBlank String name} becomes {@code field("name", Person::name, notBlank())}.
         *
         * @param name        the field's name in paths
         * @param getter      the getter; an exception from it propagates
         * @param constraints the constraints, all run in order
         * @param <V>         the type of the field
         * @return this builder
         */
        @SafeVarargs
        public final <V> Builder<T> field(String name, Function<? super T, ? extends V> getter,
                                          Constraint<? super V>... constraints) {
            name(name);
            Objects.requireNonNull(getter, "getter");
            if (constraints == null) {
                throw new NullPointerException("constraints");
            }
            var copy = new ArrayList<Constraint<? super V>>(constraints.length);
            for (var constraint : constraints) {
                copy.add(constraint);
            }
            var list = checked(copy);
            return step((value, errors) -> {
                V field = getter.apply(value);
                var at = errors.at(name);
                for (var constraint : list) {
                    constraint.check(field, at);
                }
            });
        }

        /**
         * An optional field: the constraints check the content if there is one; an empty
         * {@code Optional} (or a {@code null} one) is valid.
         *
         * @param name        the field's name in paths
         * @param getter      the getter
         * @param constraints the constraints for the content, all run in order
         * @param <V>         the type of the content
         * @return this builder
         */
        @SafeVarargs
        public final <V> Builder<T> optional(String name, Function<? super T, Optional<V>> getter,
                                             Constraint<? super V>... constraints) {
            name(name);
            Objects.requireNonNull(getter, "getter");
            if (constraints == null) {
                throw new NullPointerException("constraints");
            }
            var copy = new ArrayList<Constraint<? super V>>(constraints.length);
            for (var constraint : constraints) {
                copy.add(constraint);
            }
            var list = checked(copy);
            return step((value, errors) -> {
                var field = getter.apply(value);
                if (field == null || field.isEmpty()) {
                    return;
                }
                var content = field.get();
                var at = errors.at(name);
                for (var constraint : list) {
                    constraint.check(content, at);
                }
            });
        }

        /**
         * A nested object checked by its own validator, with paths below {@code $.name}
         * ({@code @Valid}). A {@code null} value is skipped; add {@code field(name, getter,
         * notNull())} before it to require one.
         *
         * @param name      the field's name in paths
         * @param getter    the getter
         * @param validator the validator of the nested value
         * @param <V>       the type of the field
         * @return this builder
         */
        public <V> Builder<T> nested(String name, Function<? super T, ? extends V> getter,
                                     Validator<? super V> validator) {
            name(name);
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(validator, "validator");
            return step((value, errors) -> {
                V field = getter.apply(value);
                if (field != null) {
                    validator.validate(field, errors.at(name));
                }
            });
        }

        /**
         * Every element of a list checked by constraints, at {@code $.name[i]}
         * ({@code List<@NotBlank String>}). A {@code null} list is skipped; a {@code null}
         * element is given to the constraints, so {@code notNull()} reports it.
         *
         * @param name        the field's name in paths
         * @param getter      the getter
         * @param constraints the constraints for each element, all run in order
         * @param <V>         the type of the elements
         * @return this builder
         */
        @SafeVarargs
        public final <V> Builder<T> each(String name, Function<? super T, ? extends List<? extends V>> getter,
                                         Constraint<? super V>... constraints) {
            name(name);
            Objects.requireNonNull(getter, "getter");
            if (constraints == null) {
                throw new NullPointerException("constraints");
            }
            var copy = new ArrayList<Constraint<? super V>>(constraints.length);
            for (var constraint : constraints) {
                copy.add(constraint);
            }
            var list = checked(copy);
            return step((value, errors) -> {
                List<? extends V> elements = getter.apply(value);
                if (elements == null) {
                    return;
                }
                var at = errors.at(name);
                int index = 0;
                for (V element : elements) {
                    var here = at.atIndex(index++);
                    for (var constraint : list) {
                        constraint.check(element, here);
                    }
                }
            });
        }

        /**
         * Every element of a list checked by its own validator, with paths below
         * {@code $.name[i]} ({@code List<@Valid X>}). A {@code null} list and {@code null}
         * elements are skipped; use {@link #each} with {@code notNull()} to report them.
         *
         * @param name      the field's name in paths
         * @param getter    the getter
         * @param validator the validator of each element
         * @param <V>       the type of the elements
         * @return this builder
         */
        public <V> Builder<T> eachNested(String name, Function<? super T, ? extends List<? extends V>> getter,
                                         Validator<? super V> validator) {
            name(name);
            Objects.requireNonNull(getter, "getter");
            Objects.requireNonNull(validator, "validator");
            return step((value, errors) -> {
                List<? extends V> elements = getter.apply(value);
                if (elements == null) {
                    return;
                }
                var at = errors.at(name);
                int index = 0;
                for (V element : elements) {
                    int i = index++;
                    if (element != null) {
                        validator.validate(element, at.atIndex(i));
                    }
                }
            });
        }

        /**
         * A rule about the whole object, reported at the object's own path
         * ({@code @AssertTrue} on a method, a class-level constraint).
         *
         * @param test    true when the object is valid
         * @param code    the code: non-empty printable ASCII without spaces
         * @param message the message
         * @return this builder
         * @throws IllegalArgumentException if {@code code} is empty or not printable ASCII
         */
        public Builder<T> rule(Predicate<? super T> test, String code, String message) {
            Objects.requireNonNull(test, "test");
            checkReport(code, message);
            return step((value, errors) -> {
                if (!test.test(value)) {
                    errors.reject(code, message);
                }
            });
        }

        /**
         * A rule across fields, reported at the field {@code name}: {@code $.name}. For example
         * that a delivery date is not before the order date, reported on the delivery date.
         *
         * @param name    the field's name in paths
         * @param test    true when the object is valid
         * @param code    the code: non-empty printable ASCII without spaces
         * @param message the message
         * @return this builder
         * @throws IllegalArgumentException if {@code code} is empty or not printable ASCII
         */
        public Builder<T> rule(String name, Predicate<? super T> test, String code, String message) {
            name(name);
            Objects.requireNonNull(test, "test");
            checkReport(code, message);
            return step((value, errors) -> {
                if (!test.test(value)) {
                    errors.at(name).reject(code, message);
                }
            });
        }

        /**
         * Another validator for the same object, run here at the object's own path: to reuse
         * a set of checks in several validators.
         *
         * @param validator the validator
         * @return this builder
         */
        public Builder<T> include(Validator<? super T> validator) {
            Objects.requireNonNull(validator, "validator");
            return step(validator);
        }

        /**
         * Another validator for the same object, run only when {@code condition} holds. Takes
         * the place of Jakarta's validation groups: in Spola a group is a validator of its own,
         * included always ({@link #include}) or under a condition.
         *
         * @param condition true when {@code validator} should run
         * @param validator the validator
         * @return this builder
         */
        public Builder<T> when(Predicate<? super T> condition, Validator<? super T> validator) {
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(validator, "validator");
            return step((value, errors) -> {
                if (condition.test(value)) {
                    validator.validate(value, errors);
                }
            });
        }

        /**
         * The validator: immutable, with the steps added so far. A {@code null} value is valid
         * and none of the steps run for it.
         *
         * @return the validator
         */
        public Validator<T> build() {
            List<Validator<? super T>> copy = List.copyOf(steps);
            return (value, errors) -> {
                if (value == null) {
                    return;
                }
                for (var step : copy) {
                    step.validate(value, errors);
                }
            };
        }

        private Builder<T> step(Validator<? super T> step) {
            steps.add(step);
            return this;
        }

        private static void name(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a field name must not be empty");
            }
        }

        private static void checkReport(String code, String message) {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Text.code(code);
        }

        /**
         * The constraints, none {@code null}. The varargs methods copy their array with a loop
         * first: handing the array itself on would leak it ({@code -Xlint:varargs}).
         */
        static <V> List<Constraint<? super V>> checked(List<Constraint<? super V>> constraints) {
            for (int i = 0; i < constraints.size(); i++) {
                Objects.requireNonNull(constraints.get(i), "constraint " + i);
            }
            return List.copyOf(constraints);
        }
    }
}
