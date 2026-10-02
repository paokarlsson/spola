package spola.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import spola.util.Log;

/**
 * Reads the order file line by line and decides each order: unknown SKU, the checks in
 * order, then the stock. Every decision goes to the audit trail. Blank lines and lines
 * starting with {@code #} are skipped; a broken line stops the processing. A byte order mark
 * at the start of the file (as Excel and Notepad write) is skipped.
 */
final class OrderProcessor {

    /** U+FEFF, the byte order mark; read as a character at the start of a UTF-8 file. */
    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private static final Log LOG = Log.forClass(OrderProcessor.class);

    private final Path orders;
    private final Stock stock;
    private final List<OrderCheck> checks;
    private final AuditTrail audit;

    OrderProcessor(InventorySettings inventory, Stock stock, OrderCheck quantityLimit, OrderCheck blockedSkus,
                   AuditTrail audit) {
        this.orders = inventory.orders();
        this.stock = stock;
        this.checks = List.of(blockedSkus, quantityLimit);
        this.audit = audit;
    }

    /**
     * Processes the whole order file.
     *
     * @return the summary
     * @throws OrderFileException if a line is broken; the orders before it are decided
     * @throws UncheckedIOException if the file cannot be read
     */
    Summary process() {
        LOG.info("processing orders from {}", orders);
        var processed = new ArrayList<OrderLine>();
        var rejected = new ArrayList<Summary.Rejection>();
        try (BufferedReader reader = Files.newBufferedReader(orders, StandardCharsets.UTF_8)) {
            var name = String.valueOf(orders.getFileName());
            var number = 0;
            for (String text; (text = reader.readLine()) != null; ) {
                number++;
                if (number == 1 && !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
                    text = text.substring(1);
                }
                if (text.isBlank() || text.strip().startsWith("#")) {
                    continue;
                }
                var order = OrderLine.parse(name, number, text);
                var reason = decide(order);
                if (reason == null) {
                    processed.add(order);
                    audit.accepted(order);
                } else {
                    LOG.debug("rejected {}: {}", order, reason);
                    rejected.add(new Summary.Rejection(order, reason));
                    audit.rejected(order, reason);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the order file " + orders, e);
        }
        LOG.info("processed {} orders, rejected {}", processed.size(), rejected.size());
        return Summary.of(processed, rejected, stock.levels());
    }

    /** Why the order is rejected, or {@code null} if it was taken from the stock. */
    private String decide(OrderLine order) {
        if (!stock.carries(order.sku())) {
            return "unknown sku";
        }
        for (var check : checks) {
            var reason = check.reject(order);
            if (reason.isPresent()) {
                return reason.get();
            }
        }
        if (!stock.take(order.sku(), order.quantity())) {
            return "insufficient stock (" + stock.available(order.sku()) + " left)";
        }
        return null;
    }
}
