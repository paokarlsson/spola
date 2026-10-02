/**
 * Spola's integration program: a stock service for a small web shop that reads an order file,
 * takes each order from the stock and writes a summary. It uses {@code spola.di},
 * {@code spola.config} and {@code spola.util} together the way an application would; nothing
 * depends on it.
 *
 * <p>The entry is {@link spola.demo.InventoryApp}: {@code main}, and
 * {@link spola.demo.InventoryApp#run} for running the whole program with given arguments,
 * environment and streams.
 */
module spola.demo {
    requires spola.config;
    requires spola.di;
    requires spola.util;
}
