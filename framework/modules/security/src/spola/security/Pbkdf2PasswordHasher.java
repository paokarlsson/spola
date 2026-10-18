package spola.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Password hashes with PBKDF2-HMAC-SHA256 ({@link Pbkdf2}), stored as PHC strings. The
 * counterpart of Spring Security's {@code Pbkdf2PasswordEncoder}.
 *
 * <h2>Settings</h2>
 * {@link #defaults()} uses 600 000 iterations, a 16-byte salt and a 32-byte hash (OWASP's
 * recommendation for PBKDF2-HMAC-SHA256). {@link #of(int, int, int)} takes other settings
 * within {@value #MIN_ITERATIONS} to {@value #MAX_ITERATIONS} iterations, {@value #MIN_SALT_BYTES}
 * to {@value #MAX_SALT_BYTES} bytes of salt and {@value #MIN_HASH_BYTES} to
 * {@value #MAX_HASH_BYTES} bytes of hash. Salts come from a {@link SecureRandom} created by the
 * hasher. A hasher is immutable and safe to use from any number of threads.
 *
 * <p><b>At most 32 bytes of hash.</b> PBKDF2-HMAC-SHA256 computes the hash in blocks of 32
 * bytes, each costing all the iterations, so a hash of 33 to 64 bytes costs the server twice as
 * much. An attacker who guesses passwords only needs the first block to tell a right guess from
 * a wrong one, so the second block adds nothing to the security. New hashes therefore have at
 * most 32 bytes. Stored hashes of up to 64 bytes (from earlier versions, which allowed them) are
 * still verified, and {@link #needsRehash} is {@code true} for them, so an upgrade moves them to
 * this hasher's length.
 *
 * <h2>Passwords</h2>
 * A password is encoded as UTF-8 (a lone surrogate is an error, not replaced). There is <b>no
 * normalisation</b>: neither Unicode normalisation nor trimming of whitespace, so {@code "é"}
 * (U+00E9) and {@code "e"} followed by U+0301 are different passwords. A password has 1 to
 * {@value #MAX_PASSWORD_CHARS} {@code char}s; the length is checked before encoding and before
 * any hashing. {@link #hash} refuses an empty, too long or malformed password with
 * {@link IllegalArgumentException}; {@link #matches} returns {@code false} for it. The encoded
 * bytes are cleared after use.
 *
 * <h2>Storage format</h2>
 * <pre>{@code $pbkdf2-sha256$i=<iterations>$<salt>$<hash>}</pre>
 * where {@code <salt>} and {@code <hash>} are standard Base64 ({@code +/}) without padding, and
 * the length of the hash is the length of the decoded {@code <hash>}. Parsing is strict and
 * fail-closed; a stored hash is accepted only if
 * <ul>
 *   <li>it has at most {@value #MAX_ENCODED_CHARS} characters (checked first), all printable
 *       ASCII;</li>
 *   <li>it has exactly five parts separated by {@code $}, the first one empty, the id exactly
 *       {@code pbkdf2-sha256} and the parameter exactly {@code i=} followed by decimal digits
 *       without a leading zero, from 1 to {@value #MAX_ITERATIONS};</li>
 *   <li>the Base64 is canonical (decoding and encoding again gives the same text), the salt has
 *       8 to 64 bytes and the hash 16 to 64 bytes (wider bounds than those of {@link #of}, so
 *       that older hashes can be verified and upgraded).</li>
 * </ul>
 * Anything else makes {@link #matches} and {@link #needsRehash} throw
 * {@link IllegalArgumentException} whose message does not contain the string (it may be a hash
 * read from a database). A malformed stored hash is a data error, not a wrong password
 * ({@link Authenticator} answers {@link Failure#BAD_CREDENTIALS} for it and logs it). The
 * lower bound of 1 iteration when parsing (against {@value #MIN_ITERATIONS} when creating)
 * lets older hashes be verified and upgraded; the upper bound means that a corrupt row cannot
 * hold a thread with billions of iterations. Hashes are compared in constant time
 * ({@link MessageDigest#isEqual}).
 */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

    /** The longest password, in {@code char}s. */
    public static final int MAX_PASSWORD_CHARS = 1024;
    /** The fewest iterations {@link #of} accepts. */
    public static final int MIN_ITERATIONS = 10_000;
    /** The most iterations {@link #of} accepts, and the most a stored hash may have. */
    public static final int MAX_ITERATIONS = 10_000_000;

    /** The number of decimal digits in {@link #MAX_ITERATIONS}. */
    private static final int MAX_ITERATION_DIGITS = Integer.toString(MAX_ITERATIONS).length();
    /** The shortest salt {@link #of} accepts, in bytes. */
    public static final int MIN_SALT_BYTES = 16;
    /** The longest salt {@link #of} accepts, and the longest a stored hash may have, in bytes. */
    public static final int MAX_SALT_BYTES = 64;
    /** The shortest hash {@link #of} accepts, and the shortest a stored hash may have, in bytes. */
    public static final int MIN_HASH_BYTES = 16;
    /**
     * The longest hash {@link #of} accepts, in bytes: one block of HMAC-SHA-256. Stored hashes
     * may have up to 64 bytes.
     */
    public static final int MAX_HASH_BYTES = 32;
    /** The longest stored hash that is parsed, in characters. */
    public static final int MAX_ENCODED_CHARS = 256;

    /** Iterations of {@link #defaults()}. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    /** Salt length of {@link #defaults()}, in bytes. */
    public static final int DEFAULT_SALT_BYTES = 16;
    /** Hash length of {@link #defaults()}, in bytes. */
    public static final int DEFAULT_HASH_BYTES = 32;

    /** The shortest salt a stored hash may have, in bytes (older hashes; new ones have 16). */
    static final int MIN_STORED_SALT_BYTES = 8;
    /** The longest hash in a stored hash that is parsed, in bytes (older hashes; new ones have at most 32). */
    static final int MAX_STORED_HASH_BYTES = 64;
    static final String ID = "pbkdf2-sha256";
    private static final String MALFORMED = "malformed stored password hash: ";

    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final int iterations;
    private final int saltBytes;
    private final int hashBytes;
    private final SecureRandom random = new SecureRandom();

    private Pbkdf2PasswordHasher(int iterations, int saltBytes, int hashBytes) {
        this.iterations = iterations;
        this.saltBytes = saltBytes;
        this.hashBytes = hashBytes;
    }

    /**
     * A hasher with 600 000 iterations, a 16-byte salt and a 32-byte hash.
     *
     * @return a new hasher
     */
    public static Pbkdf2PasswordHasher defaults() {
        return new Pbkdf2PasswordHasher(DEFAULT_ITERATIONS, DEFAULT_SALT_BYTES, DEFAULT_HASH_BYTES);
    }

    /**
     * A hasher with the given settings.
     *
     * @param iterations the iteration count, {@value #MIN_ITERATIONS} to {@value #MAX_ITERATIONS}
     * @param saltBytes  the salt length in bytes, {@value #MIN_SALT_BYTES} to {@value #MAX_SALT_BYTES}
     * @param hashBytes  the hash length in bytes, {@value #MIN_HASH_BYTES} to {@value #MAX_HASH_BYTES}
     *                   (one block of HMAC-SHA-256; a longer hash costs the server more but not an
     *                   attacker)
     * @return a new hasher
     * @throws IllegalArgumentException if a setting is outside its bounds
     */
    public static Pbkdf2PasswordHasher of(int iterations, int saltBytes, int hashBytes) {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be " + MIN_ITERATIONS + " to " + MAX_ITERATIONS);
        }
        if (saltBytes < MIN_SALT_BYTES || saltBytes > MAX_SALT_BYTES) {
            throw new IllegalArgumentException("saltBytes must be " + MIN_SALT_BYTES + " to " + MAX_SALT_BYTES);
        }
        if (hashBytes < MIN_HASH_BYTES || hashBytes > MAX_HASH_BYTES) {
            throw new IllegalArgumentException("hashBytes must be " + MIN_HASH_BYTES + " to " + MAX_HASH_BYTES);
        }
        return new Pbkdf2PasswordHasher(iterations, saltBytes, hashBytes);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the password is empty, longer than
     *                                  {@value #MAX_PASSWORD_CHARS} {@code char}s or contains a
     *                                  lone surrogate
     */
    @Override
    public String hash(CharSequence password) {
        Objects.requireNonNull(password, "password");
        var bytes = encode(password);
        if (bytes == null) {
            throw new IllegalArgumentException("password must be 1 to " + MAX_PASSWORD_CHARS
                    + " characters of valid UTF-16 (no lone surrogate)");
        }
        var salt = new byte[saltBytes];
        random.nextBytes(salt);
        try {
            var hash = Pbkdf2.deriveSha256(bytes, salt, iterations, hashBytes);
            return "$" + ID + "$i=" + iterations + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(hash);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stored hash is parsed first, so a malformed one throws whatever the password is.
     * A hash made with other settings (within the parsing bounds) is verified with its own
     * settings.
     */
    @Override
    public boolean matches(CharSequence password, String encoded) {
        Objects.requireNonNull(password, "password");
        var stored = Stored.parse(encoded);
        var bytes = encode(password);
        if (bytes == null) {
            return false;
        }
        byte[] actual = null;
        try {
            actual = Pbkdf2.deriveSha256(bytes, stored.salt(), stored.iterations(), stored.hash().length);
            return MessageDigest.isEqual(actual, stored.hash());
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (actual != null) {
                Arrays.fill(actual, (byte) 0);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code true} when the stored iterations, salt length or hash length differ from this
     * hasher's settings (also when they are higher), so always for a stored hash of more than
     * {@value #MAX_HASH_BYTES} bytes.
     */
    @Override
    public boolean needsRehash(String encoded) {
        var stored = Stored.parse(encoded);
        return stored.iterations() != iterations || stored.salt().length != saltBytes
                || stored.hash().length != hashBytes;
    }

    /**
     * The settings, never a hash or a password.
     *
     * @return {@code Pbkdf2PasswordHasher[iterations=..., saltBytes=..., hashBytes=...]}
     */
    @Override
    public String toString() {
        return "Pbkdf2PasswordHasher[iterations=" + iterations + ", saltBytes=" + saltBytes + ", hashBytes=" + hashBytes + "]";
    }

    /**
     * The password as UTF-8, or {@code null} if it is empty, too long or not valid UTF-16. The
     * length is checked before anything is encoded. The caller clears the result.
     */
    private static byte[] encode(CharSequence password) {
        int length = password.length();
        if (length == 0 || length > MAX_PASSWORD_CHARS) {
            return null;
        }
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // At most 3 bytes per char (a surrogate pair is 4 bytes for 2 chars), so the buffer
        // never grows and no copy of the password is left in a discarded buffer.
        var out = ByteBuffer.allocate(length * 3);
        try {
            var result = encoder.encode(CharBuffer.wrap(password), out, true);
            if (result.isError() || result.isOverflow()) {
                return null;
            }
            result = encoder.flush(out);
            if (result.isError() || result.isOverflow()) {
                return null;
            }
            return Arrays.copyOf(out.array(), out.position());
        } finally {
            Arrays.fill(out.array(), (byte) 0);
        }
    }

    /** A parsed stored hash. */
    private record Stored(int iterations, byte[] salt, byte[] hash) {

        static Stored parse(String encoded) {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.length() > MAX_ENCODED_CHARS) {
                throw malformed("longer than " + MAX_ENCODED_CHARS + " characters");
            }
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (c < 0x21 || c > 0x7e) {
                    throw malformed("contains a character that is not printable ASCII");
                }
            }
            var parts = encoded.split("\\$", -1);
            if (parts.length != 5 || !parts[0].isEmpty()) {
                throw malformed("expected $" + ID + "$i=<iterations>$<salt>$<hash>");
            }
            if (!parts[1].equals(ID)) {
                throw malformed("unsupported algorithm id, expected " + ID);
            }
            int iterations = iterations(parts[2]);
            var salt = base64(parts[3], "salt");
            if (salt.length < MIN_STORED_SALT_BYTES || salt.length > MAX_SALT_BYTES) {
                throw malformed("salt must be " + MIN_STORED_SALT_BYTES + " to " + MAX_SALT_BYTES + " bytes");
            }
            var hash = base64(parts[4], "hash");
            if (hash.length < MIN_HASH_BYTES || hash.length > MAX_STORED_HASH_BYTES) {
                throw malformed("hash must be " + MIN_HASH_BYTES + " to " + MAX_STORED_HASH_BYTES + " bytes");
            }
            return new Stored(iterations, salt, hash);
        }

        private static int iterations(String parameter) {
            var problem = "expected i=<iterations>, decimal without a leading zero, 1 to " + MAX_ITERATIONS;
            if (!parameter.startsWith("i=")) {
                throw malformed(problem);
            }
            var digits = parameter.substring(2);
            // No more digits than MAX_ITERATIONS has, so the int below cannot overflow.
            if (digits.isEmpty() || digits.length() > MAX_ITERATION_DIGITS || digits.charAt(0) == '0') {
                throw malformed(problem);
            }
            int value = 0;
            for (int i = 0; i < digits.length(); i++) {
                char c = digits.charAt(i);
                if (c < '0' || c > '9') {
                    throw malformed(problem);
                }
                value = value * 10 + (c - '0');
            }
            if (value > MAX_ITERATIONS) {
                throw malformed(problem);
            }
            return value;
        }

        private static byte[] base64(String text, String what) {
            if (text.indexOf('=') >= 0) {
                throw malformed(what + " must be Base64 without padding");
            }
            byte[] bytes;
            try {
                bytes = DECODER.decode(text);
            } catch (IllegalArgumentException e) {
                // The decoder's message names the offending character; not repeated here.
                throw malformed(what + " is not standard Base64");
            }
            if (!ENCODER.encodeToString(bytes).equals(text)) {
                throw malformed(what + " is not canonical Base64");
            }
            return bytes;
        }

        private static IllegalArgumentException malformed(String problem) {
            return new IllegalArgumentException(MALFORMED + problem);
        }
    }
}
