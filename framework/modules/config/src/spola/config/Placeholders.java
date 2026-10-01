package spola.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Resolves {@code ${key}} and {@code ${key:default}} in the merged values. <code>$${</code> is a
 * literal <code>${</code>. Each key is resolved once; a failure is reported once, at its root cause
 * (a key that refers to a failed key fails without a second report). A value is "tainted" if it
 * is, or was built from, the value of a sensitive key.
 */
final class Placeholders {

    /** A raw value and the description of the source it came from. */
    record Raw(String value, String source) {
    }

    /** A resolved value; {@code tainted} if it contains a sensitive value. */
    record Resolved(String value, boolean tainted) {
    }

    private static final Resolved FAILED = new Resolved("", false);

    private final Map<String, Raw> raw;
    private final Predicate<String> sensitive;
    private final List<ConfigProblem> problems;
    private final Map<String, Resolved> done = new HashMap<>();
    private final List<String> stack = new ArrayList<>();

    private Placeholders(Map<String, Raw> raw, Predicate<String> sensitive, List<ConfigProblem> problems) {
        this.raw = raw;
        this.sensitive = sensitive;
        this.problems = problems;
    }

    /**
     * Resolves every key of {@code raw}, in the map's iteration order. Problems are added to
     * {@code problems}; failed keys are left out of the result.
     */
    static Map<String, Resolved> resolveAll(Map<String, Raw> raw, Predicate<String> sensitive,
            List<ConfigProblem> problems) {
        var resolver = new Placeholders(raw, sensitive, problems);
        var result = new HashMap<String, Resolved>();
        for (var key : raw.keySet()) {
            var resolved = resolver.resolve(key);
            if (resolved != FAILED) {
                result.put(key, resolved);
            }
        }
        return result;
    }

    private Resolved resolve(String key) {
        var known = done.get(key);
        if (known != null) {
            return known;
        }
        var at = stack.indexOf(key);
        if (at >= 0) {
            var chain = new ArrayList<>(stack.subList(at, stack.size()));
            chain.add(key);
            var start = stack.get(at);
            problems.add(new ConfigProblem.PlaceholderError(start, raw.get(start).source(),
                    "placeholder cycle " + String.join(" -> ", chain)));
            return FAILED;
        }
        stack.add(key);
        var entry = raw.get(key);
        var expanded = expand(entry.value(), key, entry.source());
        stack.removeLast();
        var result = expanded == FAILED ? FAILED
                : new Resolved(expanded.value(), expanded.tainted() || sensitive.test(key));
        done.put(key, result);
        return result;
    }

    private Resolved expand(String text, String key, String source) {
        var out = new StringBuilder();
        var ok = true;
        var tainted = false;
        var i = 0;
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {
                out.append("${");
                i += 3;
                continue;
            }
            if (!text.startsWith("${", i)) {
                out.append(text.charAt(i));
                i++;
                continue;
            }
            var end = closingBrace(text, i + 2);
            if (end < 0) {
                problems.add(new ConfigProblem.PlaceholderError(key, source,
                        "unclosed placeholder '${' at position " + i + " (write $${ for a literal ${)"));
                return FAILED;
            }
            var body = text.substring(i + 2, end);
            var colon = body.indexOf(':');
            var name = colon < 0 ? body : body.substring(0, colon);
            if (name.isBlank() || name.contains("${") || name.contains("}")) {
                problems.add(new ConfigProblem.PlaceholderError(key, source,
                        "invalid placeholder name '" + shown(key, name) + "' at position " + i));
                ok = false;
            } else if (raw.containsKey(name)) {
                var referenced = resolve(name);
                if (referenced == FAILED) {
                    ok = false;
                } else {
                    out.append(referenced.value());
                    tainted |= referenced.tainted();
                }
            } else if (colon >= 0) {
                var fallback = expand(body.substring(colon + 1), key, source);
                if (fallback == FAILED) {
                    ok = false;
                } else {
                    out.append(fallback.value());
                    tainted |= fallback.tainted();
                }
            } else {
                problems.add(new ConfigProblem.PlaceholderError(key, source,
                        "placeholder ${" + shown(key, name) + "} has no value and no default"));
                ok = false;
            }
            i = end + 1;
        }
        return ok ? new Resolved(out.toString(), tainted) : FAILED;
    }

    /**
     * A fragment of the value of {@code key} as it may appear in a message: masked if the key
     * is sensitive, since a placeholder name in a sensitive value can be part of the secret.
     */
    private String shown(String key, String fragment) {
        return sensitive.test(key) ? Config.MASK : fragment;
    }

    /** The index of the <code>}</code> that closes a placeholder whose body starts at {@code from}, or -1. */
    private static int closingBrace(String text, int from) {
        var depth = 0;
        var j = from;
        while (j < text.length()) {
            if (text.startsWith("$${", j)) {
                j += 3;
            } else if (text.startsWith("${", j)) {
                depth++;
                j += 2;
            } else if (text.charAt(j) == '}') {
                if (depth == 0) {
                    return j;
                }
                depth--;
                j++;
            } else {
                j++;
            }
        }
        return -1;
    }
}
