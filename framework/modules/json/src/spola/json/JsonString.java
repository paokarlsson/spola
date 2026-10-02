package spola.json;

import java.util.Objects;

/**
 * A JSON string (RFC 8259 §7). The value is the decoded text, without quotes or escapes.
 *
 * @param value the text; never contains an unpaired surrogate, so that every string can be
 *              written as JSON and encoded as UTF-8
 */
public record JsonString(String value) implements JsonValue {

    /**
     * Checks the value.
     *
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} contains an unpaired surrogate (such a
     *                                  string is not Unicode text and has no UTF-8 form)
     */
    public JsonString {
        checkText(value, "string");
    }

    /**
     * A JSON string.
     *
     * @param value the text
     * @return the string
     * @throws IllegalArgumentException if {@code value} contains an unpaired surrogate
     */
    public static JsonString of(String value) {
        return new JsonString(value);
    }

    /**
     * The string as JSON text, quoted and escaped as {@link Json#write(JsonValue)} does.
     *
     * @return the compact JSON text
     */
    @Override
    public String toString() {
        return Json.write(this);
    }

    /** Rejects {@code null} and unpaired surrogates in a string value or a member name. */
    static String checkText(String text, String what) {
        Objects.requireNonNull(text, what);
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new IllegalArgumentException(what + " contains an unpaired surrogate "
                        + Text.codePoint(c) + " at index " + i);
            }
        }
        return text;
    }
}
