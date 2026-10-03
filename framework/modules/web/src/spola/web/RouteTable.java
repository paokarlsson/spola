package spola.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The routes as a trie with one level per path segment. Each node has literal children by
 * their decoded text, at most one variable child, the routes that end at the node and the
 * catch-all routes that start below it, both by method. Immutable after construction.
 *
 * <p>Lookup walks the decoded segments from the left. At each node it tries the literal child,
 * then the variable child, then the catch-all, and backtracks when a branch has no route for
 * the method. The first route found in that order is the most specific one for the request;
 * the work is bounded by the trie nodes on the request's path, not by the number of routes,
 * and the result does not depend on the order the routes were registered in.
 */
final class RouteTable {

    private static final class Node {
        final Map<String, Node> literals;
        final Node variable;
        final Map<String, Route> routes;
        final Map<String, Route> catchAll;

        Node(Map<String, Node> literals, Node variable, Map<String, Route> routes, Map<String, Route> catchAll) {
            this.literals = literals;
            this.variable = variable;
            this.routes = routes;
            this.catchAll = catchAll;
        }
    }

    /** A mutable node while the table is built. */
    private static final class Draft {
        final Map<String, Draft> literals = new HashMap<>();
        Draft variable;
        final Map<String, Route> routes = new HashMap<>();
        final Map<String, Route> catchAll = new HashMap<>();

        Node freeze() {
            var frozen = new HashMap<String, Node>();
            literals.forEach((text, child) -> frozen.put(text, child.freeze()));
            return new Node(Map.copyOf(frozen), variable == null ? null : variable.freeze(), Map.copyOf(routes),
                    Map.copyOf(catchAll));
        }
    }

    /** The outcome of a lookup. */
    sealed interface Match {
    }

    /**
     * A route for the path and the method.
     *
     * @param route    the route
     * @param segments the request's decoded segments
     */
    record Found(Route route, List<String> segments) implements Match {
    }

    /**
     * Routes match the path, none with the method.
     *
     * @param allowed the value of {@code Allow}: the methods of every route matching the path,
     *                {@code HEAD} when {@code GET} is there, and {@code OPTIONS}, sorted
     */
    record NoMethod(SortedSet<String> allowed) implements Match {
    }

    /** No route matches the path. */
    record NotFound() implements Match {
    }

    private final Node root;
    private final int size;

    /** Routes whose method and shape are unique (checked by the builder). */
    RouteTable(List<Route> routes) {
        var draft = new Draft();
        for (var route : routes) {
            var node = draft;
            var segments = route.pattern().segments();
            boolean catchAll = false;
            for (var segment : segments) {
                switch (segment) {
                    case PathPattern.Literal l -> node = node.literals.computeIfAbsent(l.text(), t -> new Draft());
                    case PathPattern.Variable v -> {
                        if (node.variable == null) {
                            node.variable = new Draft();
                        }
                        node = node.variable;
                    }
                    case PathPattern.CatchAll c -> catchAll = true;
                }
            }
            var target = catchAll ? node.catchAll : node.routes;
            if (target.putIfAbsent(route.method(), route) != null) {
                throw new IllegalStateException("two routes with the same method and shape reached the table");
            }
        }
        root = draft.freeze();
        size = routes.size();
    }

    int size() {
        return size;
    }

    /** Finds the most specific route for the decoded segments and the method. */
    Match find(String method, List<String> segments) {
        var allowed = new TreeSet<String>();
        var route = search(root, segments, 0, method, allowed);
        if (route != null) {
            return new Found(route, segments);
        }
        if (allowed.isEmpty()) {
            return new NotFound();
        }
        if (allowed.contains("GET")) {
            allowed.add("HEAD");
        }
        allowed.add("OPTIONS");
        return new NoMethod(java.util.Collections.unmodifiableSortedSet(allowed));
    }

    private static Route search(Node node, List<String> segments, int index, String method, TreeSet<String> allowed) {
        if (index == segments.size()) {
            return pick(node.routes, method, allowed);
        }
        var segment = segments.get(index);
        var literal = node.literals.get(segment);
        if (literal != null) {
            var found = search(literal, segments, index + 1, method, allowed);
            if (found != null) {
                return found;
            }
        }
        if (node.variable != null && !segment.isEmpty()) {
            var found = search(node.variable, segments, index + 1, method, allowed);
            if (found != null) {
                return found;
            }
        }
        if (!node.catchAll.isEmpty() && noEmptySegment(segments, index)) {
            return pick(node.catchAll, method, allowed);
        }
        return null;
    }

    private static boolean noEmptySegment(List<String> segments, int from) {
        for (int i = from; i < segments.size(); i++) {
            if (segments.get(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The route for the method ({@code HEAD} falls back to {@code GET}); else notes the methods. */
    private static Route pick(Map<String, Route> routes, String method, TreeSet<String> allowed) {
        if (routes.isEmpty()) {
            return null;
        }
        var route = routes.get(method);
        if (route == null && method.equals("HEAD")) {
            route = routes.get("GET");
        }
        if (route == null) {
            allowed.addAll(routes.keySet());
        }
        return route;
    }
}
