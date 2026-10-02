package spola.demo;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The orders to process and the stock to start from (keys {@code inventory.*}).
 *
 * @param orders the order file, one {@code sku;quantity} per line ({@code inventory.orders})
 * @param stock the starting stock per SKU ({@code inventory.stock.<sku>})
 */
record InventorySettings(Path orders, Map<String, Integer> stock) {

    InventorySettings {
        Objects.requireNonNull(orders, "orders");
        stock = Map.copyOf(stock);
    }
}
