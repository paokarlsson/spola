package spola.validation;

/**
 * Where a validator is in the value, as a chain from the root, rendered only when a violation
 * is kept. The notation and the rendering are those of {@code spola.json.codec}'s paths:
 * {@code $} is the root, {@code .name} a member whose name is an identifier,
 * {@code ["any name"]} any other member (escaped like a JSON string, characters outside
 * printable ASCII as {@code \}{@code uXXXX}) and {@code [2]} an element. A declared name
 * ({@link #member}) is rendered in full; a name from data ({@link #key}) is cut after
 * {@link Text#MAX_EXCERPT} characters and followed by its length.
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

    Path member(String name) {
        return new Path(this, name, -1, false);
    }

    Path key(String name) {
        return new Path(this, name, -1, name.length() > Text.MAX_EXCERPT);
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
                out.append('[').append(Text.excerpt(segment.name)).append(']');
            } else if (Text.isIdentifier(segment.name)) {
                out.append('.').append(segment.name);
            } else {
                out.append("[\"");
                Text.appendEscaped(out, segment.name, segment.name.length(), true);
                out.append("\"]");
            }
        }
        return out.toString();
    }
}
