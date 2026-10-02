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
     *     least 1, or the SKU holds a character that is not {@linkplain #visible(int) visible}
     */
    static OrderLine parse(String file, int line, String text) {
        var matcher = FORMAT.matcher(text);
        if (!matcher.matches() || Integer.parseInt(matcher.group(2)) < 1) {
            throw new OrderFileException(file, line, "expected 'sku;quantity' with a whole quantity of at least 1, got '"
                    + printable(text) + "'");
        }
        var sku = matcher.group(1);
        if (!sku.codePoints().allMatch(OrderLine::visible)) {
            throw new OrderFileException(file, line, "the sku may only hold visible characters (no control, format or"
                    + " separator characters), got '" + printable(text) + "'");
        }
        return new OrderLine(line, sku, Integer.parseInt(matcher.group(2)));
    }

    /**
     * Whether a character may be part of a SKU: not a control character (Cc), a format
     * character (Cf, e.g. U+FEFF or the bidi overrides), a line, paragraph or space separator
     * (Zl, Zp, Zs), a lone surrogate or unassigned. The SKU is written to the summary and the
     * audit trail as it is, so it must not be able to move the cursor or break a line.
     */
    static boolean visible(int c) {
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                 Character.SPACE_SEPARATOR, Character.SURROGATE, Character.UNASSIGNED -> false;
            default -> true;
        };
    }

    /** At most 60 characters, invisible characters escaped, so that the message stays one short line. */
    private static String printable(String text) {
        var shown = new StringBuilder();
        text.codePoints().limit(60).forEach(c -> {
            if (!visible(c) && c != ' ') {
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
