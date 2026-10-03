package spola.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import spola.json.JsonLimits;

/**
 * Builds a {@link Router}. Routes, filters, groups and exception mappers are registered with
 * plain method calls; nothing is checked until {@link #build()}, which reports every problem at
 * once. Not thread-safe; the router it builds is.
 *
 * <pre>{@code
 * Router router = Router.builder()
 *         .get("/orders/{id}", request -> show(request.pathParam("id", Param.LONG)))
 *         .post("/orders", this::create)
 *         .filter(timing)
 *         .group("/admin", admin -> admin
 *                 .filter(requireAdmin)
 *                 .delete("/orders/{id}", this::remove))
 *         .onException(OrderNotFound.class, (e, request) -> Problem.of(404, e.getMessage()).toResponse())
 *         .build();
 * }</pre>
 *
 * <p><b>Patterns.</b> A pattern starts with {@code /} and has segments separated by {@code /}:
 * a literal ({@code orders}), a variable ({@code {id}}, exactly one whole segment, the name a
 * Java identifier, unique in the pattern) or, as the last segment only, a catch-all
 * ({@code {path...}}, the rest of the path, at least one segment). {@code /} is the root. A
 * pattern that ends with {@code /} matches only paths that end with {@code /} (trailing slashes
 * are strict). Literals are compared with the decoded path segments, case-sensitively; a
 * pattern holds no {@code %}, no wildcards ({@code *}, {@code ?}), no partial segments
 * ({@code {id}.json}) and no {@code .} or {@code ..} segments.
 *
 * <p><b>Problems found by {@code build()}</b>, all listed in one
 * {@link RouterDefinitionException}: invalid patterns and group prefixes, methods that are not
 * a {@code token} (RFC 9110 §5.6.2), two routes with the same method and shape (such as
 * {@code /a/{x}} and {@code /a/{y}}), two mappers for the same exception class, and mappers for
 * an {@link Error} class (errors are not mapped).
 */
public final class RouterBuilder {

    /** Where a route or a filter was registered: the router itself or a group, with its prefix. */
    static final class Scope {
        final Scope parent;
        final String prefix;
        final List<Filter> filters = new ArrayList<>();
        boolean open = true;

        Scope(Scope parent, String prefix) {
            this.parent = parent;
            this.prefix = prefix;
        }
    }

    private record Registration(String method, String pattern, Scope scope, WebHandler handler) {
    }

    private final Scope root = new Scope(null, "/");
    private final List<Scope> groups = new ArrayList<>();
    private final List<Registration> registrations = new ArrayList<>();
    private final List<Router.MapperEntry<?>> mappers = new ArrayList<>();
    private JsonLimits jsonLimits = JsonLimits.DEFAULT;
    private int maxFormSize = FormBody.DEFAULT_MAX_SIZE;
    private System.Logger log = System.getLogger("spola.web");

    RouterBuilder() {
    }

    /**
     * Registers a {@code GET} route (it also answers {@code HEAD} unless a {@code HEAD} route is
     * registered for the same path).
     *
     * @param pattern the pattern
     * @param handler the handler
     * @return this builder
     */
    public RouterBuilder get(String pattern, WebHandler handler) {
        return route("GET", pattern, handler);
    }

    /**
     * Registers a {@code POST} route.
     *
     * @param pattern the pattern
     * @param handler the handler
     * @return this builder
     */
    public RouterBuilder post(String pattern, WebHandler handler) {
        return route("POST", pattern, handler);
    }

    /**
     * Registers a {@code PUT} route.
     *
     * @param pattern the pattern
     * @param handler the handler
     * @return this builder
     */
    public RouterBuilder put(String pattern, WebHandler handler) {
        return route("PUT", pattern, handler);
    }

    /**
     * Registers a {@code PATCH} route.
     *
     * @param pattern the pattern
     * @param handler the handler
     * @return this builder
     */
    public RouterBuilder patch(String pattern, WebHandler handler) {
        return route("PATCH", pattern, handler);
    }

    /**
     * Registers a {@code DELETE} route.
     *
     * @param pattern the pattern
     * @param handler the handler
     * @return this builder
     */
    public RouterBuilder delete(String pattern, WebHandler handler) {
        return route("DELETE", pattern, handler);
    }

    /**
     * Registers a route for any method ({@code HEAD} and {@code OPTIONS} included; they then
     * replace the automatic answers for the paths the pattern matches).
     *
     * @param method  the method, a token, case-sensitive ({@code "GET"} is not {@code "get"})
     * @param pattern the pattern
     * @param handler the handler
     * @return this builder
     */
    public RouterBuilder route(String method, String pattern, WebHandler handler) {
        register(root, method, pattern, handler);
        return this;
    }

    /**
     * Adds a filter for every request the router gets, also those it answers with 400, 404,
     * 405 or an automatic {@code OPTIONS}. Filters run in the order they are added, the first
     * one outermost, and outside every group's filters.
     *
     * @param filter the filter
     * @return this builder
     */
    public RouterBuilder filter(Filter filter) {
        root.filters.add(Objects.requireNonNull(filter, "filter"));
        return this;
    }

    /**
     * Registers routes, filters and nested groups below a prefix. The prefix is a pattern
     * (variables allowed, no trailing slash); a route's pattern is appended to it, and the
     * route pattern {@code /} stands for the prefix itself. The group's filters run only for
     * its routes (nested groups included), inside the router's filters and the filters of
     * the groups around it.
     *
     * <pre>{@code
     * router.group("/users/{userId}", user -> user
     *         .filter(loadUser)
     *         .get("/", request -> ...)                // /users/{userId}
     *         .get("/orders/{orderId}", request -> ...)); // /users/{userId}/orders/{orderId}
     * }</pre>
     *
     * @param prefix the prefix
     * @param body   registers the group's content; the group cannot be used after it returns
     * @return this builder
     */
    public RouterBuilder group(String prefix, Consumer<RouteGroup> body) {
        group(root, prefix, body);
        return this;
    }

    /**
     * Maps an exception class to a response. For an exception from a handler or a filter, the
     * router uses the mapper registered for the closest class in the exception's superclass
     * chain, whatever the registration order. Without one, a {@link WebException} becomes its
     * {@link WebException#toResponse problem response}, a
     * {@link spola.http.RequestBodyException} goes to the server (which answers with the body's
     * status and closes the connection), and anything else becomes a 500 problem response
     * without the exception's message, logged at {@code ERROR}. A {@code RequestBodyException}
     * goes only to a mapper registered for exactly that class, never to one for
     * {@code IOException} or {@code Exception}. If the mapper throws, the router answers 500
     * without calling a mapper again.
     *
     * @param type   the exception class
     * @param mapper the mapper
     * @param <E>    the exception type
     * @return this builder
     */
    public <E extends Throwable> RouterBuilder onException(Class<E> type, ExceptionMapper<? super E> mapper) {
        mappers.add(new Router.MapperEntry<>(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(mapper, "mapper")));
        return this;
    }

    /**
     * Sets the parser limits {@link WebRequest#readJson(spola.json.codec.JsonCodec)} uses for
     * every route ({@link JsonLimits#DEFAULT} unless set: 4 MiB, depth 512). A body larger than
     * {@code maxInputSize()} gets 413, other limits 400. A handler can pass its own limits to
     * {@link WebRequest#readJson(spola.json.codec.JsonCodec, JsonLimits)}. The server's
     * {@code HttpLimits.maxBodySize} still applies to every body, whatever this says.
     *
     * @param limits the limits
     * @return this builder
     */
    public RouterBuilder jsonLimits(JsonLimits limits) {
        this.jsonLimits = Objects.requireNonNull(limits, "limits");
        return this;
    }

    /**
     * Sets the largest form body {@link WebRequest#readForm()} reads for every route: 2 MiB
     * ({@code 2 * 1024 * 1024} bytes, as Tomcat's {@code maxPostSize}) unless set. A larger body
     * gets 413, and reading stops one byte past the limit. A handler can pass its own limit to
     * {@link WebRequest#readForm(int)}. The server's {@code HttpLimits.maxBodySize} still applies
     * to every body, whatever this says.
     *
     * @param maxBytes the limit in bytes, at least 1
     * @return this builder
     * @throws IllegalArgumentException if {@code maxBytes} is less than 1
     */
    public RouterBuilder maxFormSize(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxFormSize must be at least 1, was " + maxBytes);
        }
        this.maxFormSize = maxBytes;
        return this;
    }

    /**
     * Checks everything registered and builds the router. The builder can be used further;
     * later changes do not affect the router.
     *
     * @return the router, immutable and thread-safe
     * @throws RouterDefinitionException with every problem, if there are any
     */
    public Router build() {
        var problems = new ArrayList<String>();
        var validPrefixes = new HashMap<Scope, Boolean>();
        for (var group : groups) {
            var prefixProblems = new ArrayList<String>();
            PathPattern.parse(group.prefix, false, prefixProblems);
            for (var problem : prefixProblems) {
                problems.add("group prefix " + show(group.prefix) + ": " + problem);
            }
            validPrefixes.put(group, prefixProblems.isEmpty());
        }
        var routes = new ArrayList<Route>();
        var shapes = new HashMap<String, Route>();
        for (var registration : registrations) {
            var route = route(registration, validPrefixes, problems);
            if (route == null) {
                continue;
            }
            var key = route.method() + " " + route.pattern().shape();
            var earlier = shapes.putIfAbsent(key, route);
            if (earlier != null) {
                problems.add(route.method() + " " + show(route.pattern().text()) + ": same method and shape as "
                        + earlier.method() + " " + show(earlier.pattern().text()));
                continue;
            }
            routes.add(route);
        }
        var byClass = new LinkedHashMap<Class<?>, Router.MapperEntry<?>>();
        for (var entry : mappers) {
            if (Error.class.isAssignableFrom(entry.type())) {
                problems.add("exception mapper for " + entry.type().getName()
                        + ": errors are not mapped (they go to the server)");
            } else if (byClass.putIfAbsent(entry.type(), entry) != null) {
                problems.add("two exception mappers for " + entry.type().getName());
            }
        }
        if (!problems.isEmpty()) {
            throw new RouterDefinitionException(problems);
        }
        return new Router(new RouteTable(routes), List.copyOf(root.filters), Map.copyOf(byClass), jsonLimits, maxFormSize,
                log);
    }

    /** The route of a registration, or {@code null} after adding its problems. */
    private static Route route(Registration registration, Map<Scope, Boolean> validPrefixes, List<String> problems) {
        var method = registration.method();
        var pattern = registration.pattern();
        boolean valid = true;
        if (!isToken(method)) {
            problems.add("method " + show(method) + " for " + show(pattern) + ": not a token (RFC 9110 5.6.2)");
            valid = false;
        }
        var chain = new ArrayList<Scope>();
        boolean prefixesValid = true;
        for (var scope = registration.scope(); scope.parent != null; scope = scope.parent) {
            chain.addFirst(scope);
            prefixesValid &= validPrefixes.get(scope);
        }
        var full = new StringBuilder();
        for (var scope : chain) {
            full.append(scope.prefix.equals("/") ? "" : scope.prefix);
        }
        if (!pattern.equals("/") || full.isEmpty()) {
            full.append(pattern);
        }
        // with a broken prefix only the route's own pattern is checked (the prefix is reported once)
        var text = prefixesValid ? full.toString() : pattern;
        var own = new ArrayList<String>();
        List<PathPattern.Segment> segments = null;
        if (pattern.isEmpty() || pattern.charAt(0) != '/') {
            own.add("does not start with /");
        } else {
            segments = PathPattern.parse(text, true, own);
        }
        for (var problem : own) {
            problems.add(method + " " + show(pattern.startsWith("/") ? text : pattern) + ": " + problem);
        }
        if (!valid || !prefixesValid || segments == null) {
            return null;
        }
        var filters = new ArrayList<Filter>();
        for (var scope : chain) {
            filters.addAll(scope.filters);
        }
        return Route.of(method, new PathPattern(text, segments), registration.handler(), filters);
    }

    /** Adds a route to {@code scope}. */
    void register(Scope scope, String method, String pattern, WebHandler handler) {
        checkOpen(scope);
        registrations.add(new Registration(Objects.requireNonNull(method, "method"),
                Objects.requireNonNull(pattern, "pattern"), scope, Objects.requireNonNull(handler, "handler")));
    }

    /** Adds a filter to {@code scope}. */
    void filter(Scope scope, Filter filter) {
        checkOpen(scope);
        scope.filters.add(Objects.requireNonNull(filter, "filter"));
    }

    /** Runs {@code body} on a new group below {@code parent}. */
    void group(Scope parent, String prefix, Consumer<RouteGroup> body) {
        checkOpen(parent);
        Objects.requireNonNull(body, "body");
        var scope = new Scope(parent, Objects.requireNonNull(prefix, "prefix"));
        groups.add(scope);
        try {
            body.accept(new RouteGroup(this, scope));
        } finally {
            scope.open = false;
        }
    }

    private static void checkOpen(Scope scope) {
        if (!scope.open) {
            throw new IllegalStateException("the group " + show(scope.prefix)
                    + " is closed: register its content inside the group's lambda");
        }
    }

    /** Sets the logger (tests capture what the router logs). */
    RouterBuilder log(System.Logger log) {
        this.log = Objects.requireNonNull(log, "log");
        return this;
    }

    private static String show(String text) {
        return "\"" + Text.escape(text, Text.MAX_LOGGED) + "\"";
    }

    /** Whether {@code text} is a {@code token} (RFC 9110 §5.6.2): one or more {@code tchar}. */
    static boolean isToken(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean tchar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) {
                return false;
            }
        }
        return true;
    }
}
