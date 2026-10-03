package spola.web;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import spola.http.Headers;
import spola.http.Response;
import spola.validation.Validated;
import spola.validation.ValidationException;
import spola.validation.Violation;

/**
 * An exception that carries an HTTP error status, the counterpart of Spring's
 * {@code ResponseStatusException} and {@code ErrorResponseException}. Without a mapper for it
 * (see {@link RouterBuilder#onException}) the router answers with its problem response
 * ({@link #toResponse}). The router's own 400 (invalid path, invalid parameter), 404 and 405
 * are {@code WebException}s too, so a mapper for this class can give them custom pages.
 *
 * <p>Applications may extend it:
 *
 * <pre>{@code
 * final class OrderNotFound extends WebException {
 *     OrderNotFound(long id) {
 *         super(404, "Order not found", "No order " + id + ".");
 *     }
 * }
 * }</pre>
 *
 * <p>The detail becomes part of the response, so it must not hold secrets; text from the
 * request should be quoted short and escaped (the router's own details are).
 */
public class WebException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String title;
    private final String detail;
    private final transient Headers headers;
    private final transient List<Problem.FieldError> errors;

    /**
     * An exception with the status's standard title and no detail.
     *
     * @param status the status, 400–599
     * @throws IllegalArgumentException if the status is outside 400–599
     */
    public WebException(int status) {
        this(status, null, null, Headers.EMPTY);
    }

    /**
     * An exception with the status's standard title and a detail.
     *
     * @param status the status, 400–599
     * @param detail the detail, a human-readable explanation of this occurrence
     * @throws IllegalArgumentException if the status is outside 400–599
     */
    public WebException(int status, String detail) {
        this(status, null, Objects.requireNonNull(detail, "detail"), Headers.EMPTY);
    }

    /**
     * An exception with a title and a detail.
     *
     * @param status the status, 400–599
     * @param title  the title, a short summary of the problem type
     * @param detail the detail, or {@code null} for none
     * @throws IllegalArgumentException if the status is outside 400–599
     */
    public WebException(int status, String title, String detail) {
        this(status, Objects.requireNonNull(title, "title"), detail, Headers.EMPTY);
    }

    /**
     * An exception whose response carries extra headers ({@code Allow} on a 405, for instance).
     *
     * @param status  the status, 400–599
     * @param title   the title, or {@code null} for the status's standard title
     * @param detail  the detail, or {@code null} for none
     * @param headers headers for the response (they must be allowed in a {@link Response})
     * @throws IllegalArgumentException if the status is outside 400–599
     */
    protected WebException(int status, String title, String detail, Headers headers) {
        this(status, title, detail, headers, List.of());
    }

    /**
     * An exception whose problem lists errors in request content (see {@link #errors()}).
     *
     * @param status  the status, 400–599
     * @param title   the title, or {@code null} for the status's standard title
     * @param detail  the detail, or {@code null} for none
     * @param headers headers for the response (they must be allowed in a {@link Response})
     * @param errors  the errors, in order; the problem keeps at most {@value Problem#MAX_ERRORS}
     * @throws IllegalArgumentException if the status is outside 400–599
     */
    protected WebException(int status, String title, String detail, Headers headers,
            List<Problem.FieldError> errors) {
        Problem.checkStatus(status);
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(errors, "errors");
        var resolvedTitle = title == null ? Problem.standardTitle(status) : title;
        super(status + " " + resolvedTitle + (detail == null ? "" : ": " + detail));
        this.status = status;
        this.title = resolvedTitle;
        this.detail = detail;
        this.headers = headers;
        this.errors = List.copyOf(errors);
    }

    /**
     * The 400 for a value that failed validation, the counterpart of Spring's
     * {@code MethodArgumentNotValidException}: the standard title, the detail
     * {@code The request has N validation errors.} ({@code 1 validation error.} for one),
     * followed by {@code  The first 100 are listed.} when more than 100 were counted, and
     * {@link #errors()} with every kept violation in order, its {@code path}, {@code message}
     * and {@code code} unchanged. Neither the detail nor the errors hold a rejected value
     * ({@link Violation} never carries one).
     *
     * <pre>{@code
     * {"type":"about:blank","title":"Bad Request","status":400,"detail":"The request has 2 validation errors.",
     *  "instance":"/orders","errors":[{"path":"$.customer","message":"must not be blank","code":"notBlank"},
     *  {"path":"$.lines[0].qty","message":"must be between 1 and 99","code":"range"}]}
     * }</pre>
     *
     * <p>{@code WebRequest.readJson(codec, validator)}, {@code readForm(binder, validator)} and
     * {@code bindQuery(binder, validator)} throw it; it gives the same problem as
     * {@link #invalid(ValidationException)} for the same violations.
     *
     * @param invalid the result of {@code Validator.check}
     * @return the exception, to be thrown
     * @throws NullPointerException     if {@code invalid} is {@code null}
     * @throws IllegalArgumentException if a violation's code is not a valid
     *                                  {@link Problem.FieldError} code (longer than 100
     *                                  characters, or not printable ASCII without spaces)
     */
    public static WebException invalid(Validated.Invalid<?> invalid) {
        Objects.requireNonNull(invalid, "invalid");
        return invalid(invalid.violations(), invalid.count());
    }

    /**
     * The 400 for a {@link ValidationException}, exactly as {@link #invalid(Validated.Invalid)}
     * for its {@code violations()} and {@code count()}. A {@code ValidationException} is never
     * turned into a 400 by the router on its own (without a mapper it is a 500: a value from
     * the service's own code that fails validation is a bug, not the client's error); register
     * this explicitly where the exception does mean bad input:
     *
     * <pre>{@code
     * Router.builder()
     *         .onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))
     * }</pre>
     *
     * @param exception the exception
     * @return the 400, to be thrown or turned into a response
     * @throws NullPointerException     if {@code exception} is {@code null}
     * @throws IllegalArgumentException if a violation's code is not a valid
     *                                  {@link Problem.FieldError} code
     */
    public static WebException invalid(ValidationException exception) {
        Objects.requireNonNull(exception, "exception");
        return invalid(exception.violations(), exception.count());
    }

    private static WebException invalid(List<Violation> violations, int count) {
        var errors = new ArrayList<Problem.FieldError>(violations.size());
        for (var violation : violations) {
            errors.add(new Problem.FieldError(violation.path(), violation.message(), Optional.of(violation.code())));
        }
        var detail = "The request has " + count + (count == 1 ? " validation error." : " validation errors.")
                + (count > errors.size() ? " The first " + errors.size() + " are listed." : "");
        return new WebException(400, null, detail, Headers.EMPTY, errors);
    }

    /**
     * The status.
     *
     * @return 400–599
     */
    public int status() {
        return status;
    }

    /**
     * The title: the one given, or the status's standard reason phrase.
     *
     * @return the title
     */
    public String title() {
        return title;
    }

    /**
     * The detail.
     *
     * @return the detail, or empty
     */
    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    /**
     * Headers for the response, such as {@code Allow} on the router's 405. A mapper that makes
     * its own response for a {@code WebException} should keep them.
     *
     * @return the headers, often empty
     */
    public Headers headers() {
        return headers;
    }

    /**
     * Errors in request content, such as every mapping problem of a JSON body that
     * {@link WebRequest#readJson} rejected, every violation of {@link #invalid} or every binding
     * error of a {@link FormBinder}; the problem carries them as its {@code errors}
     * member (see {@link Problem#withErrors}). A mapper that makes its own response can read
     * them here.
     *
     * @return the errors, unmodifiable, often empty
     */
    public List<Problem.FieldError> errors() {
        return errors;
    }

    /**
     * The problem for this exception: status, title and detail, {@code type} {@code about:blank},
     * and {@code errors} when {@link #errors()} is not empty.
     *
     * @param instance the {@code instance} member (the request's path), or {@code null}
     * @return the problem
     */
    public Problem problem(String instance) {
        var problem = Problem.of(status).withTitle(title);
        if (detail != null) {
            problem = problem.withDetail(detail);
        }
        if (!errors.isEmpty()) {
            problem = problem.withErrors(errors);
        }
        return instance == null ? problem : problem.withInstance(instance);
    }

    /**
     * The response the router sends when no mapper handles this exception: the
     * {@link #problem} with the request's decoded path as {@code instance}, plus
     * {@link #headers()}. Subclasses may override it.
     *
     * @param request the request
     * @return the response
     */
    public Response toResponse(WebRequest request) {
        var response = problem(request.path()).toResponse();
        for (var field : headers.fields()) {
            response = response.withHeader(field.name(), field.value());
        }
        return response;
    }
}
