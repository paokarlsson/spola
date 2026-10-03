package spola.web;

import java.util.function.Consumer;

/**
 * The routes, filters and nested groups below a prefix, registered inside
 * {@link RouterBuilder#group}. Usable only while that lambda runs.
 */
public final class RouteGroup {

    private final RouterBuilder builder;
    private final RouterBuilder.Scope scope;

    RouteGroup(RouterBuilder builder, RouterBuilder.Scope scope) {
        this.builder = builder;
        this.scope = scope;
    }

    /**
     * Registers a {@code GET} route below the prefix ({@code /} is the prefix itself).
     *
     * @param pattern the pattern, appended to the prefix
     * @param handler the handler
     * @return this group
     */
    public RouteGroup get(String pattern, WebHandler handler) {
        return route("GET", pattern, handler);
    }

    /**
     * Registers a {@code POST} route below the prefix.
     *
     * @param pattern the pattern, appended to the prefix
     * @param handler the handler
     * @return this group
     */
    public RouteGroup post(String pattern, WebHandler handler) {
        return route("POST", pattern, handler);
    }

    /**
     * Registers a {@code PUT} route below the prefix.
     *
     * @param pattern the pattern, appended to the prefix
     * @param handler the handler
     * @return this group
     */
    public RouteGroup put(String pattern, WebHandler handler) {
        return route("PUT", pattern, handler);
    }

    /**
     * Registers a {@code PATCH} route below the prefix.
     *
     * @param pattern the pattern, appended to the prefix
     * @param handler the handler
     * @return this group
     */
    public RouteGroup patch(String pattern, WebHandler handler) {
        return route("PATCH", pattern, handler);
    }

    /**
     * Registers a {@code DELETE} route below the prefix.
     *
     * @param pattern the pattern, appended to the prefix
     * @param handler the handler
     * @return this group
     */
    public RouteGroup delete(String pattern, WebHandler handler) {
        return route("DELETE", pattern, handler);
    }

    /**
     * Registers a route for any method below the prefix; see {@link RouterBuilder#route}.
     *
     * @param method  the method, a token, case-sensitive
     * @param pattern the pattern, appended to the prefix
     * @param handler the handler
     * @return this group
     */
    public RouteGroup route(String method, String pattern, WebHandler handler) {
        builder.register(scope, method, pattern, handler);
        return this;
    }

    /**
     * Adds a filter for this group's routes (nested groups included). It runs inside the
     * router's filters and the filters of the groups around this one, in the order added.
     *
     * @param filter the filter
     * @return this group
     */
    public RouteGroup filter(Filter filter) {
        builder.filter(scope, filter);
        return this;
    }

    /**
     * Registers a nested group; its prefix is appended to this group's prefix.
     *
     * @param prefix the prefix, a pattern without a trailing slash
     * @param body   registers the nested group's content
     * @return this group
     */
    public RouteGroup group(String prefix, Consumer<RouteGroup> body) {
        builder.group(scope, prefix, body);
        return this;
    }
}
