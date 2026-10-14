package spola.security;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The implementation of {@link PasswordHasher#delegating}: hashes stored as
 * {@code {id}<the delegate's hash>}, like Spring Security's {@code DelegatingPasswordEncoder}.
 * The behaviour is documented on {@link PasswordHasher#delegating}. Immutable, and safe to use
 * from any number of threads if the delegates are.
 */
final class DelegatingPasswordHasher implements PasswordHasher {

    /** The longest id, in characters. */
    static final int MAX_ID_CHARS = 100;
    private static final String MALFORMED = "malformed stored password hash: ";

    private final String defaultId;
    private final PasswordHasher defaultHasher;
    private final String prefix;
    private final Map<String, PasswordHasher> hashers;
    /** The ids, sorted, for {@code toString()}. */
    private final List<String> ids;

    private DelegatingPasswordHasher(String defaultId, Map<String, PasswordHasher> hashers) {
        this.defaultId = defaultId;
        this.defaultHasher = hashers.get(defaultId);
        this.prefix = "{" + defaultId + "}";
        this.hashers = Map.copyOf(hashers);
        this.ids = hashers.keySet().stream().sorted().toList();
    }

    /** See {@link PasswordHasher#delegating}. */
    static DelegatingPasswordHasher of(String defaultId, Map<String, ? extends PasswordHasher> hashers) {
        Objects.requireNonNull(defaultId, "defaultId");
        Objects.requireNonNull(hashers, "hashers");
        // One pass over the given map; everything below works on the copy, so the map cannot
        // change between the checks and their use.
        var copy = new HashMap<String, PasswordHasher>();
        for (var entry : hashers.entrySet()) {
            var id = Objects.requireNonNull(entry.getKey(), "an id in hashers is null");
            var hasher = Objects.requireNonNull(entry.getValue(), "a hasher in hashers is null");
            copy.put(id, hasher);
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("hashers must not be empty");
        }
        for (var id : copy.keySet()) {
            checkId(id);
        }
        if (!copy.containsKey(defaultId)) {
            throw new IllegalArgumentException("defaultId must be one of the ids in hashers");
        }
        return new DelegatingPasswordHasher(defaultId, copy);
    }

    /** An id is 1 to 100 characters of printable ASCII (0x21 to 0x7E) other than braces. */
    private static void checkId(String id) {
        if (id.isEmpty() || id.length() > MAX_ID_CHARS) {
            throw new IllegalArgumentException("an id must be 1 to " + MAX_ID_CHARS + " characters");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw new IllegalArgumentException("an id must be printable ASCII without spaces (0x21 to 0x7E)");
            }
            if (c == '{' || c == '}') {
                throw new IllegalArgumentException("an id must not contain { or }");
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code "{" + defaultId + "}"} followed by the default hasher's hash. Its exceptions
     * propagate.
     */
    @Override
    public String hash(CharSequence password) {
        Objects.requireNonNull(password, "password");
        var hash = Objects.requireNonNull(defaultHasher.hash(password), "the default hasher returned null");
        return prefix + hash;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The rest after the {@code {id}} prefix goes to that id's hasher; its
     * {@link IllegalArgumentException} for a malformed rest propagates unchanged.
     */
    @Override
    public boolean matches(CharSequence password, String encoded) {
        Objects.requireNonNull(password, "password");
        var prefixed = parse(encoded);
        return prefixed.hasher().matches(password, prefixed.rest());
    }

    /**
     * {@inheritDoc}
     *
     * <p>The hasher of the stored id is always asked about the rest, so a malformed rest throws
     * for every id. {@code true} if the id is not the default id or that hasher says so.
     */
    @Override
    public boolean needsRehash(String encoded) {
        var prefixed = parse(encoded);
        boolean delegateSays = prefixed.hasher().needsRehash(prefixed.rest());
        return !prefixed.id().equals(defaultId) || delegateSays;
    }

    /**
     * The default id and the sorted ids, never a hash.
     *
     * @return {@code DelegatingPasswordHasher[default=<id>, ids=[<id>, ...]]}
     */
    @Override
    public String toString() {
        return "DelegatingPasswordHasher[default=" + defaultId + ", ids=" + ids + "]";
    }

    /** A stored hash split into its id, the id's hasher and the rest. */
    private record Prefixed(String id, PasswordHasher hasher, String rest) {
    }

    /**
     * Splits {@code {id}rest}. The id is the text up to the first <code>&#125;</code>, looked
     * for at most {@value #MAX_ID_CHARS} + 1 characters in, and must be exactly one of the ids.
     * The messages are fixed texts: they never contain the string or the text between the braces
     * (it may be a hash), and not even the word "id", so that {@code {id}...} is not echoed.
     */
    private Prefixed parse(String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.isEmpty() || encoded.charAt(0) != '{') {
            throw new IllegalArgumentException(MALFORMED + "expected a prefix in braces");
        }
        int end = -1;
        int limit = Math.min(encoded.length() - 1, MAX_ID_CHARS + 1);
        for (int i = 1; i <= limit; i++) {
            if (encoded.charAt(i) == '}') {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IllegalArgumentException(MALFORMED + "no closing brace within the first "
                    + (MAX_ID_CHARS + 2) + " characters");
        }
        var id = encoded.substring(1, end);
        var hasher = hashers.get(id);
        if (hasher == null) {
            throw new IllegalArgumentException(MALFORMED + "the prefix in braces is not a configured one");
        }
        return new Prefixed(id, hasher, encoded.substring(end + 1));
    }
}
