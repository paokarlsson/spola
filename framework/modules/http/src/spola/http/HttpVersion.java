package spola.http;

/** The HTTP versions the server speaks (RFC 9112 §2.3). Any other version gets 505. */
public enum HttpVersion {
    /** {@code HTTP/1.0}: the connection is closed after every response. */
    HTTP_1_0("HTTP/1.0"),
    /** {@code HTTP/1.1}: persistent connections, chunked transfer coding, {@code Host} required. */
    HTTP_1_1("HTTP/1.1");

    private final String text;

    HttpVersion(String text) {
        this.text = text;
    }

    /**
     * The version as it appears on the wire.
     *
     * @return {@code "HTTP/1.0"} or {@code "HTTP/1.1"}
     */
    public String text() {
        return text;
    }
}
