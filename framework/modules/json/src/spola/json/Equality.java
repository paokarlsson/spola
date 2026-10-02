package spola.json;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Deep equality of two trees without recursion, so that trees of any depth (built in code, or
 * parsed with a high {@link JsonLimits#maxDepth()}) can be compared on any thread stack.
 */
final class Equality {

    private Equality() {
    }

    static boolean equal(JsonValue first, JsonValue second) {
        var pending = new ArrayDeque<JsonValue>();
        pending.push(second);
        pending.push(first);
        while (!pending.isEmpty()) {
            var a = pending.pop();
            var b = pending.pop();
            if (a == b) {
                continue;
            }
            if (a.hashCode() != b.hashCode()) {
                return false;
            }
            switch (a) {
                case JsonObject x -> {
                    if (!(b instanceof JsonObject y) || x.size() != y.size()) {
                        return false;
                    }
                    for (var member : x.members().entrySet()) {
                        var other = y.members().get(member.getKey());
                        if (other == null) {
                            return false;
                        }
                        pending.push(other);
                        pending.push(member.getValue());
                    }
                }
                case JsonArray x -> {
                    if (!(b instanceof JsonArray y) || x.size() != y.size()) {
                        return false;
                    }
                    Iterator<JsonValue> ys = y.values().iterator();
                    for (var value : x.values()) {
                        // pushed in pairs: the next pop takes the value of x, then the one of y
                        var other = ys.next();
                        pending.push(other);
                        pending.push(value);
                    }
                }
                // scalars: their own equals does not recurse
                default -> {
                    if (!a.equals(b)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
