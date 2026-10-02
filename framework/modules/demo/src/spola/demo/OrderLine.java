package spola.demo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One order from the order file.
 *
 * @param line the line number in the file, from 1
 * @param sku the article
 * @param quantity how many, at least 1
 */
record OrderLine(int line, String sku, int quantity) {

    private static final Pattern FORMAT = Pattern.compile("\\s*([^;\\s]+)\\s*;\\s*([0-9]{1,9})\\s*");

    OrderLine {
        Objects.requireNonNull(sku, "sku");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
    }

    /**
     * Reads one line of the order file: {@code sku;quantity}, whitespace around both allowed.
     *
     * @param file the file's name, for the message
     * @param line the line number
     * @param text the line
     * @return the order
     * @throws OrderFileException if the line is not {@code sku;quantity} with a quantity of at
     *     least 1
     */
    static OrderLine parse(String file, int line, String text) {
        var matcher = FORMAT.matcher(text);
        if (!matcher.matches() || Integer.parseInt(matcher.group(2)) < 1) {
            throw new OrderFileException(file, line, "expected 'sku;quantity' with a whole quantity of at least 1, got '"
                    + printable(text) + "'");
        }
        return new OrderLine(line, matcher.group(1), Integer.parseInt(matcher.group(2)));
    }

    /** At most 60 characters, control characters escaped, so that the message stays one short line. */
    private static String printable(String text) {
        var shown = new StringBuilder();
        text.codePoints().limit(60).forEach(c -> {
            if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) {
                shown.append(String.format("\\u%04x", c));
            } else {
                shown.appendCodePoint(c);
            }
        });
        if (text.codePointCount(0, text.length()) > 60) {
            shown.append("...");
        }
        return shown.toString();
    }

    @Override
    public String toString() {
        return "line " + line + ": " + sku + " x " + quantity;
    }
}
