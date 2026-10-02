package spola.json;

/** The JSON {@code null} (RFC 8259 §3). There is exactly one. */
public enum JsonNull implements JsonValue {

    /** {@code null}. */
    INSTANCE;

    /**
     * The literal.
     *
     * @return {@code null}
     */
    @Override
    public String toString() {
        return "null";
    }
}
