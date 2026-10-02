package spola.demo;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The result of processing an order file.
 *
 * @param processed the orders taken from the stock, in file order
 * @param rejected the orders that were not, with the reason, in file order
 * @param finalStock the stock per SKU afterwards, sorted by SKU
 */
record Summary(List<OrderLine> processed, List<Rejection> rejected, SortedMap<String, Integer> finalStock) {

    /**
     * An order that was not taken from the stock.
     *
     * @param order the order
     * @param reason why, e.g. {@code unknown sku}
     */
    record Rejection(OrderLine order, String reason) implements RejectionWith {
    }

    Summary {
        processed = List.copyOf(processed);
        rejected = List.copyOf(rejected);
        finalStock = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(finalStock));
    }

    static Summary of(List<OrderLine> processed, List<Rejection> rejected, Map<String, Integer> finalStock) {
        return new Summary(processed, rejected, new TreeMap<>(finalStock));
    }
}
