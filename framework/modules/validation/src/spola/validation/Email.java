package spola.validation;

/**
 * The definition behind {@link Constraints#email()}: one {@code @}, a {@code dot-atom} local
 * part of 1–64 characters (RFC 5322 {@code atext} and dots, no dot first, last or doubled) and
 * a domain of 1–253 characters of LDH labels of 1–63 characters (no hyphen first or last),
 * ASCII only. Plain loops, no regular expression, so the time is linear in the length.
 */
final class Email {

    static final int MAX_LOCAL = 64;
    static final int MAX_DOMAIN = 253;
    static final int MAX_LABEL = 63;

    private Email() {
    }

    static boolean isValid(CharSequence address) {
        int length = address.length();
        if (length > MAX_LOCAL + 1 + MAX_DOMAIN) {
            return false;
        }
        int at = -1;
        for (int i = 0; i < length; i++) {
            if (address.charAt(i) == '@') {
                if (at >= 0) {
                    return false; // a second @
                }
                at = i;
            }
        }
        if (at < 0) {
            return false;
        }
        return isLocalPart(address, 0, at) && isDomain(address, at + 1, length);
    }

    private static boolean isLocalPart(CharSequence text, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_LOCAL) {
            return false;
        }
        char previous = '.'; // so a dot first is rejected
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (previous == '.') {
                    return false;
                }
            } else if (!isAtext(c)) {
                return false;
            }
            previous = c;
        }
        return previous != '.';
    }

    private static boolean isDomain(CharSequence text, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_DOMAIN) {
            return false;
        }
        int labelStart = from;
        for (int i = from; i <= to; i++) {
            if (i == to || text.charAt(i) == '.') {
                if (!isLabel(text, labelStart, i)) {
                    return false;
                }
                labelStart = i + 1;
            }
        }
        return true;
    }

    private static boolean isLabel(CharSequence text, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_LABEL) {
            return false;
        }
        if (text.charAt(from) == '-' || text.charAt(to - 1) == '-') {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            boolean ldh = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
            if (!ldh) {
                return false;
            }
        }
        return true;
    }

    /** RFC 5322 {@code atext}: ASCII letters, digits and {@code !#$%&'*+-/=?^_`{|}~}. */
    private static boolean isAtext(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        return "!#$%&'*+-/=?^_`{|}~".indexOf(c) >= 0;
    }
}
