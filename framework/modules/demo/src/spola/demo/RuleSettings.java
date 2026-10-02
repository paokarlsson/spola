package spola.demo;

import java.util.Set;

/**
 * The order rules (keys {@code rules.*}).
 *
 * @param maxQuantity the largest quantity one order may ask for ({@code rules.max.quantity})
 * @param blocked SKUs that may not be sold ({@code rules.blocked}, comma-separated)
 */
record RuleSettings(int maxQuantity, Set<String> blocked) {

    RuleSettings {
        if (maxQuantity < 1) {
            throw new IllegalArgumentException("maxQuantity must be at least 1");
        }
        blocked = Set.copyOf(blocked);
    }
}
