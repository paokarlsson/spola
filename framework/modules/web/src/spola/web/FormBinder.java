package spola.web;

import java.util.Objects;

/**
 * Binds form fields or query parameters to a value, usually a record, with plain code: the
 * counterpart of Spring's {@code @ModelAttribute} data binding, without reflection. The binder
 * reads every field it needs from a {@link FormFields}, which collects every binding error
 * instead of stopping at the first:
 *
 * <pre>{@code
 * record Signup(String email, Optional<Integer> age, List<String> interests, boolean terms) {
 * }
 *
 * static final FormBinder<Signup> SIGNUP = f -> new Signup(
 *         f.required("email", Param.STRING),
 *         f.optional("age", Param.INT),
 *         f.all("interests", Param.STRING),
 *         f.flag("terms"));
 *
 * Signup signup = request.readForm(SIGNUP, SIGNUP_RULES); // or request.bindQuery(SIGNUP)
 * }</pre>
 *
 * <p>Use it with {@link WebRequest#readForm(FormBinder)}, {@link WebRequest#bindQuery(FormBinder)}
 * (each also with a {@code Validator}), or {@link #bind(FormData)} without a request. A binder
 * that only calls {@link FormFields} and a constructor is stateless and can be shared as a
 * {@code static final} field by any number of threads.
 *
 * <p><b>Errors and exceptions.</b> When {@link FormFields} collected errors, the result of the
 * binder is thrown away and binding ends in a {@link WebException} 400 with those errors (see
 * {@link FormFields}). When the binder throws a {@link RuntimeException} after errors were
 * collected (typically a record constructor that rejects the {@code null} a failed
 * {@code required} returned), the exception is discarded and the answer is the same 400. When
 * the binder throws without collected errors, the exception passes unchanged (the same
 * instance), so the router's mappers or its 500 handle it. An {@link Error} always passes.
 *
 * @param <T> the bound type
 */
@FunctionalInterface
public interface FormBinder<T> {

    /**
     * Reads the fields and builds the value. Called once per binding with a fresh
     * {@code FormFields}, which must not be kept or used after this call returns.
     *
     * @param fields the fields of one form or query, and the collector of binding errors
     * @return the value; ignored if errors were collected
     */
    T bind(FormFields fields);

    /**
     * Binds a form without a request, exactly as {@link WebRequest#readForm(FormBinder)} binds
     * the body: useful for tests and for a {@link FormData} read with {@link WebRequest#readForm()}.
     *
     * @param data the form
     * @return the value
     * @throws WebException         400 with {@link WebException#errors()} and the detail
     *                              {@code Request body has N problems.} (followed by
     *                              {@code  The first 100 are listed.} when more than 100 were
     *                              counted) if binding collected errors
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws RuntimeException     whatever the binder throws when no error was collected
     */
    default T bind(FormData data) {
        Objects.requireNonNull(data, "data");
        return FormFields.bind(this, data.asMap(), FormFields.BODY);
    }
}
