package spola.demo;

import java.util.Optional;
import java.util.Set;

/** Rejects orders for SKUs listed in {@code rules.blocked}. */
final class BlockedSkus implements OrderCheck {

    private final Set<String> blocked;

    BlockedSkus(RuleSettings rules) {
        this.blocked = rules.blocked();
    }

    @Override
    public Optional<String> reject(OrderLine order) {
        return blocked.contains(order.sku()) ? Optional.of("sku is blocked") : Optional.empty();
    }
}
