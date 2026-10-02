package spola.json.codec;

/**
 * Where a codec is in the document, as a chain from the root. Rendered only when a problem is
 * reported, in the notation of {@code spola.json.JsonAccessException}: {@code $} is the value
 * given to {@code decode}/{@code encode}, {@code .name} a member whose name is an identifier,
 * {@code ["any name"]} any other member (escaped like a JSON string, characters outside
 * printable ASCII as {@code \}{@code uXXXX}) and {@code [2]} an array element.
 *
 * <p>Names come in two kinds. A name the codec declares (a field of {@code object}, a sealed
 * codec's discriminator) is added with {@link #member} and always rendered in full, exactly as
 * {@code spola.json} renders it, so that two fields never share a path. A name taken from the
 * data (an unknown member, a map key) is added with {@link #key}; if it is longer than
 * {@link Messages#MAX_EXCERPT} characters it is cut and followed by its length, outside the
 * quotes ({@code ["aaaa..."... (10000 characters)]}). Up to 100 problems can share an ancestor,
 * so an uncut name of a megabyte from the document would otherwise be copied into every one of
 * their paths.
 */
final class Path {

    static final Path ROOT = new Path(null, null, -1, false);

    private final Path parent;
    private final String name;
    private final int index;
    private final boolean cut;
    private final int depth;

    private Path(Path parent, String name, int index, boolean cut) {
        this.parent = parent;
        this.name = name;
        this.index = index;
        this.cut = cut;
        this.depth = parent == null ? 0 : parent.depth + 1;
    }

    /** A member whose name the codec declares; rendered in full. */
    Path member(String name) {
        return new Path(this, name, -1, false);
    }

    /** A member whose name comes from the data (unknown member, map key); cut when long. */
    Path key(String name) {
        return new Path(this, name, -1, name.length() > Messages.MAX_EXCERPT);
    }

    Path element(int index) {
        return new Path(this, null, index, false);
    }

    /** The rendered path; not recursive, so any depth renders on any stack. */
    @Override
    public String toString() {
        var segments = new Path[depth];
        var at = this;
        for (int i = depth - 1; i >= 0; i--) {
            segments[i] = at;
            at = at.parent;
        }
        var out = new StringBuilder("$");
        for (var segment : segments) {
            if (segment.name == null) {
                out.append('[').append(segment.index).append(']');
            } else if (segment.cut) {
                out.append('[').append(Messages.excerpt(segment.name)).append(']');
            } else if (isIdentifier(segment.name)) {
                out.append('.').append(segment.name);
            } else {
                out.append("[\"");
                Messages.appendEscaped(out, segment.name, segment.name.length(), true);
                out.append("\"]");
            }
        }
        return out.toString();
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
