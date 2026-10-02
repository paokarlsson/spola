package spola.demo;

import java.io.PrintStream;

/**
 * Writes the summary as text:
 *
 * <pre>
 * processed 2 orders, rejected 1
 * processed:
 *   line 1: apple x 3
 *   line 3: pear x 5
 * rejected:
 *   line 2: kiwi x 1: unknown sku
 * final stock:
 *   apple: 7
 *   pear: 0
 * </pre>
 */
final class ReportWriter {

    private final PrintStream out;

    ReportWriter(PrintStream out) {
        this.out = out;
    }

    void write(Summary summary) {
        out.println("processed " + summary.processed().size() + " orders, rejected " + summary.rejected().size());
        out.println("processed:");
        summary.processed().forEach(order -> out.println("  " + order));
        out.println("rejected:");
        summary.rejected().forEach(rejection -> out.println("  " + rejection.order() + ": " + rejection.reason()));
        out.println("final stock:");
        summary.finalStock().forEach((sku, level) -> out.println("  " + sku + ": " + level));
        out.flush();
    }
}
