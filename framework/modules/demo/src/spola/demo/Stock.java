package spola.demo;

import java.util.Map;
import java.util.TreeMap;
import spola.util.Log;

/**
 * The stock per SKU, starting from the configured levels. Not thread-safe: one order file is
 * processed by one thread.
 */
final class Stock {

    private static final Log LOG = Log.forClass(Stock.class);

    private final Map<String, Integer> levels;

    Stock(InventorySettings settings) {
        this.levels = new TreeMap<>(settings.stock());
        LOG.debug("starting stock {}", levels);
    }

    /** Whether the SKU is stocked at all (possibly with 0 left). */
    boolean carries(String sku) {
        return levels.containsKey(sku);
    }

    /** How many are left of a SKU the stock carries. */
    int available(String sku) {
        return levels.getOrDefault(sku, 0);
    }

    /**
     * Takes {@code quantity} of a SKU if that many are left.
     *
     * @return whether they were taken
     */
    boolean take(String sku, int quantity) {
        var left = available(sku);
        if (!carries(sku) || left < quantity) {
            return false;
        }
        levels.put(sku, left - quantity);
        LOG.debug("took {} x {}, {} left", quantity, sku, left - quantity);
        return true;
    }

    /** The current level of every SKU. */
    Map<String, Integer> levels() {
        return Map.copyOf(levels);
    }
}
