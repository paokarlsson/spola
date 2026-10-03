package spola.web;

import java.lang.System.Logger.Level;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import spola.http.Handler;
import spola.http.Request;
import spola.http.RequestBodyException;
import spola.http.Response;
import spola.json.JsonLimits;

/**
 * Routes requests to handlers: the counterpart of Spring MVC's {@code DispatcherServlet} with
 * {@code @RequestMapping} methods, written as code. A router is a {@link spola.http.Handler},
 * immutable and thread-safe; start it with {@code HttpServer.start(options, router)}. Build it
 * with {@link #builder()}; see {@link RouterBuilder} for patterns and registration.
 *
 * <p><b>The path, before routing.</b> The raw path is split on {@code /} first, then each
 * segment is percent-decoded as strict UTF-8, so an encoded {@code /} ({@code %2F}) never makes
 * a new segment. The router answers 400 (after its filters) when a segment is not valid
 * percent-encoded UTF-8 (incomplete or overlong sequences, encoded surrogates), when a decoded
 * segment holds {@code /}, {@code \} or a control character (U+0000–U+001F, U+007F), when a
 * segment is {@code .} or {@code ..} (also encoded), and when a segment other than the last is
 * empty ({@code //}). Decoding happens once: {@code %252F} is the text {@code %2F}.
 *
 * <p><b>Matching</b> does not depend on the order routes were registered in. Segment by segment
 * from the left, a literal goes before a variable, which goes before a catch-all, with
 * backtracking: for {@code /a/b/c}, {@code /a/b/{y}} wins over {@code /a/{x}/c}, and
 * {@code /a/{x}/c} matches if it is the only one. The most specific route that matches both
 * the path and the method is chosen. Methods are case-sensitive.
 *
 * <ul>
 *   <li>No route matches the path: 404.</li>
 *   <li>Routes match the path, none with the method: 405 with {@code Allow} (the methods of
 *       every route that matches the path, {@code HEAD} when {@code GET} is there, and
 *       {@code OPTIONS}, sorted).</li>
 *   <li>{@code HEAD} without a {@code HEAD} route uses the {@code GET} route (the server sends
 *       no body); {@code OPTIONS} without an {@code OPTIONS} route gets 204 with
 *       {@code Allow}.</li>
 *   <li>{@code OPTIONS *}: 404.</li>
 * </ul>
 *
 * <p>The router's 400, 404 and 405 are {@link WebException}s and go through the exception
 * mappers like any other exception. The chain for a request is: the router's filters, the
 * filters of the route's groups, the handler. An exception at any step is mapped to a response
 * at that step (see {@link RouterBuilder#onException}), so the filters around it see a
 * response.
 */
public final class Router implements Handler {

    /** A mapper with its exception class. */
    record MapperEntry<E extends Throwable>(Class<E> type, ExceptionMapper<? super E> mapper) {
        Response apply(Throwable exception, WebRequest request) throws Exception {
            return mapper.map(type.cast(exception), request);
        }
    }

    private final RouteTable table;
    private final List<Filter> filters;
    private final Map<Class<?>, MapperEntry<?>> mappers;
    private final JsonLimits jsonLimits;
    private final System.Logger log;

    Router(RouteTable table, List<Filter> filters, Map<Class<?>, MapperEntry<?>> mappers, JsonLimits jsonLimits,
            System.Logger log) {
        this.table = table;
        this.filters = filters;
        this.mappers = mappers;
        this.jsonLimits = jsonLimits;
        this.log = log;
    }

    /**
     * A new builder.
     *
     * @return the builder
     */
    public static RouterBuilder builder() {
        return new RouterBuilder();
    }

    /**
     * Routes {@code http} and runs the filters and the handler.
     *
     * @param http the request
     * @return the response
     * @throws Exception a {@link RequestBodyException} that no mapper took (the server answers
     *                   with its status), or an {@link Error}
     */
    @Override
    public Response handle(Request http) throws Exception {
        var query = new QueryState(http.query().orElse(null));
        var body = new BodyState(jsonLimits);
        var method = http.method();
        var rawPath = http.path();
        if (rawPath.equals("*")) {
            return run(new WebRequest(http, rawPath, List.of(), null, query, body, Map.of()), filters,
                    request -> {
                        throw new WebException(404, "Not Found", "The asterisk-form request-target (OPTIONS *) is not"
                                + " routed.");
                    });
        }
        return switch (Decoding.checkPath(rawPath)) {
            case Decoding.InvalidPath invalid -> run(new WebRequest(http, rawPath, List.of(), null, query, body, Map.of()),
                    filters, request -> {
                        throw new WebException(400, "Bad Request", "Invalid request path: " + invalid.reason() + ".");
                    });
            case Decoding.ValidPath valid -> switch (table.find(method, valid.segments())) {
                case RouteTable.Found found -> {
                    var route = found.route();
                    var chain = route.filters().isEmpty() ? filters
                            : concat(filters, route.filters());
                    yield run(new WebRequest(http, valid.decoded(), valid.segments(), route, query, body, Map.of()), chain,
                            route.handler());
                }
                case RouteTable.NoMethod noMethod -> {
                    var allow = String.join(", ", noMethod.allowed());
                    var request = new WebRequest(http, valid.decoded(), valid.segments(), null, query, body, Map.of());
                    if (method.equals("OPTIONS")) {
                        yield run(request, filters, r -> Response.status(204).withHeader("Allow", allow));
                    }
                    yield run(request, filters, r -> {
                        throw new MethodNotAllowed(allow);
                    });
                }
                case RouteTable.NotFound notFound -> run(
                        new WebRequest(http, valid.decoded(), valid.segments(), null, query, body, Map.of()), filters,
                        request -> {
                            throw new WebException(404);
                        });
            };
        };
    }

    /** The router's 405, with {@code Allow}. */
    private static final class MethodNotAllowed extends WebException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        MethodNotAllowed(String allow) {
            super(405, null, "Allowed methods: " + allow + ".", spola.http.Headers.of("Allow", allow));
        }
    }

    private static List<Filter> concat(List<Filter> first, List<Filter> second) {
        var all = new java.util.ArrayList<Filter>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    private Response run(WebRequest request, List<Filter> chain, WebHandler terminal) throws Exception {
        return new Step(chain, 0, terminal).proceed(request);
    }

    /** One step of the chain: filter {@code index}, or the handler after the last filter. */
    private final class Step implements Filter.Chain {
        private final List<Filter> chain;
        private final int index;
        private final WebHandler terminal;

        Step(List<Filter> chain, int index, WebHandler terminal) {
            this.chain = chain;
            this.index = index;
            this.terminal = terminal;
        }

        @Override
        public Response proceed(WebRequest request) throws Exception {
            Objects.requireNonNull(request, "a filter passed a null request to next.proceed");
            try {
                Response response;
                if (index < chain.size()) {
                    response = chain.get(index).filter(request, new Step(chain, index + 1, terminal));
                    if (response == null) {
                        throw new NullPointerException("the filter returned null");
                    }
                } else {
                    response = terminal.handle(request);
                    if (response == null) {
                        throw new NullPointerException("the handler returned null");
                    }
                }
                return response;
            } catch (Exception e) {
                return failed(e, request);
            }
        }
    }

    /** The response for {@code failure}; see {@link RouterBuilder#onException}. */
    private Response failed(Exception failure, WebRequest request) throws Exception {
        var entry = mapperFor(failure);
        if (entry != null) {
            try {
                var response = entry.apply(failure, request);
                if (response == null) {
                    throw new NullPointerException("the exception mapper returned null");
                }
                return response;
            } catch (Exception e) {
                log.log(Level.ERROR, request + ": the exception mapper for " + entry.type().getName() + " failed on "
                        + LoggedThrowable.describe(failure) + "; answering 500", LoggedThrowable.of(e));
                return internalError(request);
            }
        }
        if (failure instanceof WebException web) {
            try {
                return web.toResponse(request);
            } catch (Exception e) {
                log.log(Level.ERROR, request + ": the response of " + LoggedThrowable.describe(failure)
                        + " failed; answering 500", LoggedThrowable.of(e));
                return internalError(request);
            }
        }
        if (isBodyFailure(failure)) {
            throw failure; // the server answers with the body's status and closes the connection
        }
        log.log(Level.ERROR, request + " failed; answering 500", LoggedThrowable.of(failure));
        return internalError(request);
    }

    /** The mapper for the closest class; a {@link RequestBodyException} only to its own mapper. */
    private MapperEntry<?> mapperFor(Exception failure) {
        if (failure instanceof RequestBodyException) {
            return mappers.get(RequestBodyException.class);
        }
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            var entry = mappers.get(type);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    /** Whether {@code failure} is or wraps a {@link RequestBodyException}. */
    private static boolean isBodyFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof RequestBodyException) {
                return true;
            }
        }
        return false;
    }

    private static Response internalError(WebRequest request) {
        return Problem.of(500).withInstance(request.path()).toResponse();
    }

    /** {@code Router[n routes, m filters, k mappers]}. */
    @Override
    public String toString() {
        return "Router[" + table.size() + " routes, " + filters.size() + " filters, " + mappers.size() + " mappers]";
    }
}
