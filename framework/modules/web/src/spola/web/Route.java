package spola.web;

import java.util.List;
import java.util.Map;

/**
 * A registered route after {@code build()}: method, pattern, handler and the filters of the
 * groups it was registered in (outermost group first).
 *
 * @param method    the method, case-sensitive
 * @param pattern   the full pattern (group prefixes included)
 * @param handler   the handler
 * @param filters   the group filters, outermost first
 * @param positions the segment index of every variable and catch-all, by name
 * @param catchAll  the name of the catch-all, or {@code null}
 */
record Route(String method, PathPattern pattern, WebHandler handler, List<Filter> filters,
        Map<String, Integer> positions, String catchAll) {

    static Route of(String method, PathPattern pattern, WebHandler handler, List<Filter> filters) {
        var positions = new java.util.HashMap<String, Integer>();
        String catchAll = null;
        var segments = pattern.segments();
        for (int i = 0; i < segments.size(); i++) {
            switch (segments.get(i)) {
                case PathPattern.Variable v -> positions.put(v.name(), i);
                case PathPattern.CatchAll c -> {
                    positions.put(c.name(), i);
                    catchAll = c.name();
                }
                case PathPattern.Literal l -> {
                }
            }
        }
        return new Route(method, pattern, handler, List.copyOf(filters), Map.copyOf(positions), catchAll);
    }
}
