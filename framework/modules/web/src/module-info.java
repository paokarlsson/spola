/**
 * Spola's web layer on top of {@link spola.http}: routing as code, path and query parameters,
 * filters, typed request attributes and central exception mapping with RFC 9457 problem
 * responses. It replaces the core of Spring MVC ({@code @RestController},
 * {@code @GetMapping}, {@code @PathVariable}, {@code @RequestParam},
 * {@code OncePerRequestFilter}, {@code @ControllerAdvice}, {@code ProblemDetail}).
 *
 * <ul>
 *   <li>{@link spola.web.Router}: an immutable, thread-safe {@link spola.http.Handler} built
 *       with {@link spola.web.Router#builder()}; start it with
 *       {@code HttpServer.start(options, router)}.</li>
 *   <li>{@link spola.web.WebRequest}: the immutable request a {@link spola.web.WebHandler}
 *       gets, with the decoded path, path variables, query parameters, typed
 *       {@link spola.web.Param} conversion and typed {@link spola.web.AttributeKey}
 *       attributes.</li>
 *   <li>{@link spola.web.Filter}: code around the handlers, for the whole router or a
 *       group.</li>
 *   <li>{@link spola.web.ExceptionMapper}, {@link spola.web.WebException},
 *       {@link spola.web.Problem}: exceptions to responses.</li>
 *   <li>JSON bodies with an explicit {@code JsonCodec}: {@code WebRequest.readJson} in
 *       ({@code @RequestBody}), {@link spola.web.Responses} out ({@code @ResponseBody}), with
 *       strict {@link spola.web.MediaType} and {@link spola.web.AcceptHeader} parsing and
 *       content negotiation (415, 406).</li>
 *   <li>Cookies and forms: {@code WebRequest.cookie} in ({@code @CookieValue}),
 *       {@link spola.web.ResponseCookie} with {@code Responses.withCookie} out
 *       ({@code ResponseCookie}), and {@code application/x-www-form-urlencoded} bodies with
 *       {@code WebRequest.readForm} and {@link spola.web.FormData}.</li>
 *   <li>Sessions ({@code HttpSession}, {@code server.servlet.session.*}):
 *       {@link spola.web.Sessions} with {@link spola.web.Session} and
 *       {@link spola.web.SessionOptions} keeps sessions in memory, identified by a random id in
 *       a cookie, created only when something is stored, with an idle timeout, a limit (503)
 *       and a new id on demand ({@code changeSessionId}); its filter is registered on the
 *       router. The cookie is {@code Secure} when the options say so or when the request's
 *       scheme is {@code https}, as with Tomcat.</li>
 *   <li>Validation and binding ({@code @Valid}, {@code @ModelAttribute},
 *       {@code MethodArgumentNotValidException}): {@code WebRequest.readJson(codec, validator)},
 *       {@code readForm(binder, validator)} and {@code bindQuery(binder, validator)} with a
 *       {@code spola.validation.Validator} and a {@link spola.web.FormBinder} over
 *       {@link spola.web.FormFields}; violations and binding errors are a 400 with
 *       {@code errors} ({@link spola.web.WebException#invalid}).</li>
 *   <li>Behind a reverse proxy ({@code server.forward-headers-strategy=native}, Tomcat's
 *       {@code RemoteIpValve}): the filter {@link spola.web.ForwardedHeaders} with
 *       {@link spola.web.TrustedProxies} reads {@code X-Forwarded-Proto} and
 *       {@code X-Forwarded-For} only when the direct sender is a trusted proxy, walks
 *       {@code X-Forwarded-For} from the right past the trusted proxies, takes strict IP literals
 *       only (no name lookup) and answers 400 for an invalid value from a trusted proxy.
 *       Registered first, it gives every filter and handler after it the client's
 *       {@code WebRequest.scheme()} and {@code clientAddress()} (so the session cookie gets
 *       {@code Secure} behind a TLS proxy), while {@code WebRequest.http()} still describes the
 *       connection.</li>
 * </ul>
 *
 * <p>{@code spola.http} is required transitively because the API uses its {@code Request},
 * {@code Response} and {@code Headers}; {@code spola.json} is required transitively because
 * {@link spola.web.Problem} carries extension members as a {@code JsonObject};
 * {@code spola.validation} is required transitively because the API takes its
 * {@code Validator} and {@code ValidationException} and uses its {@code Validated}. The
 * dependency goes this way only: {@code spola.validation} stays standalone. No other Spola
 * module is required, no reflection is used, and the module logs through
 * {@link java.lang.System.Logger} (logger name {@code spola.web}).
 *
 * <p>{@code requires static java.net.http} is for the module's tests only (they talk to the
 * router through a real server with {@link java.net.http.HttpClient}); no main code uses it.
 */
module spola.web {
    requires transitive spola.http;
    requires transitive spola.json;
    requires transitive spola.validation;
    requires static java.net.http;

    exports spola.web;
}
