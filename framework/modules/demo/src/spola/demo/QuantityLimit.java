package spola.demo;

import java.util.Optional;

/** Rejects orders that ask for more than {@code rules.max.quantity}. */
final class QuantityLimit implements OrderCheck {

    private final int max;

    QuantityLimit(RuleSettings rules) {
        this.max = rules.maxQuantity();
    }

    @Override
    public Optional<String> reject(OrderLine order) {
        return order.quantity() > max ? Optional.of("exceeds max quantity " + max) : Optional.empty();
    }
}
