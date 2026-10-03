package spola.validation;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * See {@link Validator#lazy}: asks the supplier on first use and keeps its validator. Two
 * threads racing on first use may both ask; the supplier is expected to return the same
 * validator each time (a {@code static final} field).
 */
final class LazyValidator<T> implements Validator<T> {

    private final Supplier<Validator<T>> supplier;
    private volatile Validator<T> resolved;

    LazyValidator(Supplier<Validator<T>> supplier) {
        this.supplier = supplier;
    }

    @Override
    public void validate(T value, Errors errors) {
        var validator = resolved;
        if (validator == null) {
            validator = Objects.requireNonNull(supplier.get(), "the supplier of a lazy validator returned null");
            resolved = validator;
        }
        validator.validate(value, errors);
    }
}
