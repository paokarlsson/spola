package spola.di;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a set of bindings as a graph before anything is created. Works only on the declared
 * dependencies, which is why every binding declares them (see {@link BindingBuilder}).
 */
final class Validator {

    private Validator() {
    }

    /** Every problem found, in a stable order; empty if the graph is valid. */
    static List<String> problems(List<Definition<?>> definitions) {
        var problems = new ArrayList<String>();
        var byKey = new LinkedHashMap<Key<?>, List<Definition<?>>>();
        for (var definition : definitions) {
            byKey.computeIfAbsent(definition.key(), _ -> new ArrayList<>()).add(definition);
        }
        var graph = new LinkedHashMap<Key<?>, Definition<?>>();
        byKey.forEach((key, all) -> graph.put(key, all.getFirst()));

        byKey.forEach((key, all) -> {
            if (all.size() > 1) {
                problems.add(key + " is bound " + all.size() + " times: at "
                        + String.join(", at ", all.stream().map(Definition::source).toList()));
            }
        });
        for (var definition : definitions) {
            if (definition.factory() == null) {
                problems.add(definition.describe() + " has no target; call to(...) or toInstance(...)");
            }
        }
        for (var definition : graph.values()) {
            for (var dependency : definition.dependencies()) {
                if (!graph.containsKey(dependency)) {
                    problems.add(definition.describe() + " depends on " + dependency + ", which is not bound"
                            + alternatives(dependency, graph.keySet()));
                }
            }
        }
        cycles(graph, problems);
        captives(graph, problems);
        return problems;
    }

    /** Bindings of the same type with other qualifiers, as a hint. */
    private static String alternatives(Key<?> missing, Set<Key<?>> bound) {
        var sameType = bound.stream().filter(key -> key.type() == missing.type()).toList();
        return sameType.isEmpty() ? "" : " (bound for that type: " + sameType + ")";
    }

    /**
     * Depth-first search from every binding in binding order. A dependency that is on the
     * current path closes a cycle, reported from where it entered the path. Every node is
     * finished once, so each back edge, and with it each reported cycle, is seen once; every
     * cyclic part of the graph yields at least one report.
     */
    private static void cycles(Map<Key<?>, Definition<?>> graph, List<String> problems) {
        var finished = new HashSet<Key<?>>();
        var path = new ArrayList<Key<?>>();
        for (var key : graph.keySet()) {
            visit(key, graph, finished, path, problems);
        }
    }

    private static void visit(Key<?> key, Map<Key<?>, Definition<?>> graph, Set<Key<?>> finished,
                              List<Key<?>> path, List<String> problems) {
        if (finished.contains(key) || !graph.containsKey(key)) {
            return;
        }
        int start = path.indexOf(key);
        if (start >= 0) {
            var cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(key);
            problems.add("dependency cycle: " + Key.chain(cycle) + " (" + key + " bound at "
                    + graph.get(key).source() + ")");
            return;
        }
        path.add(key);
        for (var dependency : graph.get(key).dependencies()) {
            visit(dependency, graph, finished, path, problems);
        }
        path.removeLast();
        finished.add(key);
    }

    /**
     * A singleton lives as long as the container; a call-scoped component reached from it, also
     * through prototypes (created once for the singleton), would be captured from the first call
     * scope and used in every later one.
     */
    private static void captives(Map<Key<?>, Definition<?>> graph, List<String> problems) {
        for (var definition : graph.values()) {
            if (definition.scope() == Scope.SINGLETON || definition.scope() == Scope.EAGER_SINGLETON) {
                var path = new ArrayList<Key<?>>(List.of(definition.key()));
                captured(definition, path, new HashSet<>(), graph, problems);
            }
        }
    }

    private static void captured(Definition<?> singleton, List<Key<?>> path, Set<Key<?>> seen,
                                 Map<Key<?>, Definition<?>> graph, List<String> problems) {
        for (var dependency : graph.get(path.getLast()).dependencies()) {
            var target = graph.get(dependency);
            if (target == null || !seen.add(dependency)) {
                continue;
            }
            path.add(dependency);
            if (target.scope() == Scope.CALL) {
                problems.add(singleton.scope().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " "
                        + singleton.describe() + " depends on call-scoped " + dependency + " (" + Key.chain(path)
                        + "); it would keep the instance of the first call scope. Make "
                        + singleton.key() + " CALL or PROTOTYPE scoped");
            } else if (target.scope() == Scope.PROTOTYPE) {
                captured(singleton, path, seen, graph, problems);
            }
            path.removeLast();
        }
    }
}
