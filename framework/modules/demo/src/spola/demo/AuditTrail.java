package spola.demo;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import spola.util.Log;

/**
 * A signed record of every decision: one line per accepted or rejected order, and when it is
 * closed a last line with the number of entries and an HMAC-SHA256 (RFC 2104, JDK
 * {@link Mac}) of everything before it, keyed with {@code audit.secret}. Whoever has the
 * secret can check that the trail was not edited.
 *
 * <pre>
 * accepted line=1 sku=apple quantity=3
 * rejected line=2 sku=pear quantity=6 reason=insufficient stock (5 left)
 * closed entries=2 hmac-sha256=5bdc...
 * </pre>
 *
 * <p>Opens (and truncates) the file when created; {@link #close()} writes the last line and
 * closes the file. The container closes it when the program ends, also after a failure.
 */
final class AuditTrail implements AutoCloseable {

    private static final Log LOG = Log.forClass(AuditTrail.class);
    static final String ALGORITHM = "HmacSHA256";

    private final Path file;
    private final BufferedWriter writer;
    private final Mac mac;
    private int entries;
    private boolean closed;

    AuditTrail(InventorySettings inventory, AuditSettings settings) throws IOException {
        this.file = settings.file().orElseGet(() -> inventory.orders().resolveSibling(
                inventory.orders().getFileName() + ".audit"));
        this.mac = mac(settings.secret());
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        LOG.info("audit trail {} opened", file);
    }

    void accepted(OrderLine order) {
        write("accepted line=" + order.line() + " sku=" + order.sku() + " quantity=" + order.quantity());
    }

    void rejected(OrderLine order, String reason) {
        write("rejected line=" + order.line() + " sku=" + order.sku() + " quantity=" + order.quantity()
                + " reason=" + reason);
    }

    private void write(String entry) {
        if (closed) {
            throw new IllegalStateException("the audit trail is closed");
        }
        var line = entry + "\n";
        mac.update(line.getBytes(StandardCharsets.UTF_8));
        try {
            writer.write(line);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the audit trail " + file, e);
        }
        entries++;
    }

    /** Writes the closing line with the signature and closes the file. Does nothing the second time. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try (writer) {
            writer.write("closed entries=" + entries + " hmac-sha256=" + HexFormat.of().formatHex(mac.doFinal()) + "\n");
        }
        LOG.info("audit trail {} closed after {} entries", file, entries);
    }

    /** An HMAC-SHA256 keyed with the secret's UTF-8 bytes. */
    static Mac mac(String secret) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the JDK has no " + ALGORITHM, e);
        }
    }
}
