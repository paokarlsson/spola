package spola.demo;

import java.util.Optional;

/** A business rule an order must pass before it is taken from the stock. */
interface OrderCheck {

    /**
     * Checks an order.
     *
     * @param order the order
     * @return why the order is rejected, or empty if it passes
     */
    Optional<String> reject(OrderLine order);
}
