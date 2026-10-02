package spola.demo;

/** The qualifier that tells the {@link OrderCheck} bindings apart. */
enum CheckKind {
    /** {@link QuantityLimit}. */
    QUANTITY,
    /** {@link BlockedSkus}. */
    BLOCKED
}
