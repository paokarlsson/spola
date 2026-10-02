package spola.json;

/**
 * Paths in the notation of {@link JsonAccessException}: {@code $} is the value a reader started
 * from, {@code .name} a member whose name is an identifier, {@code ["any name"]} any other
 * member (escaped like a JSON string, non-printable characters as {@code \}{@code uXXXX}) and
 * {@code [2]} an array element.
 */
final class Paths {

    static final String ROOT = "$";

    private Paths() {
    }

    static String member(String path, String name) {
        if (isIdentifier(name)) {
            return path + "." + name;
        }
        var out = new StringBuilder(path).append("[\"");
        Text.appendEscaped(out, name, name.length());
        return out.append("\"]").toString();
    }

    static String element(String path, int index) {
        return path + "[" + index + "]";
    }

    private static boolean isIdentifier(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
            if (!letter && !(i > 0 && c >= '0' && c <= '9')) {
                return false;
            }
        }
        return true;
    }
}
