package spola.security;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Password hashes with bcrypt, in the format of Spring Security's {@code BCryptPasswordEncoder}
 * (jBCrypt, OpenBSD). Hashes that Spring wrote verify here, and hashes made here verify in
 * Spring, so an application can move between the two without new passwords. The algorithm is
 * implemented in this module on the JDK's primitives and is tested against the published
 * vectors of jBCrypt and Openwall, against independent vectors from libxcrypt and against the
 * JDK's own Blowfish.
 *
 * <h2>Cost</h2>
 * The cost {@code c} means {@code 2^c} rounds of bcrypt's expensive key schedule, so each step
 * up doubles the time. {@link #defaults()} has cost {@value #DEFAULT_COST}, like
 * {@code new BCryptPasswordEncoder()}; at least 10 is recommended. {@link #of(int)} takes
 * {@value #MIN_COST} to {@value #MAX_COST}, like Spring. Salts are 16 bytes from a
 * {@link SecureRandom} created by the hasher. A hasher is immutable and safe to use from any
 * number of threads.
 *
 * <h2>Passwords</h2>
 * A password is encoded as UTF-8 (a lone surrogate is an error, not replaced) without
 * normalisation, which gives the same bytes as Spring's {@code getBytes(UTF_8)} for every
 * password accepted here. A password is accepted if it is <b>1 to {@value #MAX_PASSWORD_BYTES}
 * bytes</b> of UTF-8 and <b>does not contain U+0000</b>; a password of more than
 * {@value #MAX_PASSWORD_BYTES} {@code char}s is refused before it is encoded. {@link #hash}
 * refuses any other password with an {@link IllegalArgumentException} that does not contain it,
 * and {@link #matches} returns {@code false} for it; in neither case is any bcrypt computed.
 * The reasons:
 * <ul>
 *   <li>bcrypt reads only the first 72 bytes of its key. A longer password would silently be
 *       hashed as its first 72 bytes, so two different long passwords would be accepted as the
 *       same. That is CVE-2025-22228 in Spring Security.</li>
 *   <li>The key is the password followed by a NUL byte, repeated. With NUL in the password,
 *       two different passwords can give the same key: {@code "a"}, and {@code "a"} + U+0000 +
 *       {@code "a"}, both give the bytes {@code 61 00 61 00 61 00 ...}. C implementations also
 *       stop reading at the NUL.</li>
 * </ul>
 * With 1 to 72 bytes and no NUL, two different passwords always give different keys. A
 * consequence: a hash that Spring wrote for a password of more than 72 bytes (before Spring's
 * fix) cannot be verified with the whole password; that user has to choose a new password.
 * The encoded password, the key, the Blowfish state and the computed hash are cleared after
 * use; the {@code CharSequence} itself cannot be.
 *
 * <h2>Storage format</h2>
 * <pre>{@code $2a$<cost>$<salt><hash>}</pre>
 * exactly 60 characters: the cost as two decimal digits, then 22 characters of salt (16 bytes)
 * and 31 characters of hash (23 bytes) in bcrypt's Base64 with the alphabet
 * {@code ./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789} and no padding.
 * New hashes always have the version {@code $2a$}, like Spring's default. Parsing is strict and
 * fail-closed; a stored hash is accepted only if it
 * <ul>
 *   <li>has exactly 60 characters (checked first);</li>
 *   <li>starts with exactly {@code $2a$}, {@code $2b$} or {@code $2y$}, which are computed
 *       identically (as in jBCrypt; the differences between them in other implementations only
 *       concern passwords of more than 255 bytes or bytes that are not valid UTF-8, which are
 *       never accepted here); {@code $2x$} and {@code $2$} are not supported;</li>
 *   <li>has the cost as two ASCII digits from {@code 04} to {@code 31}, followed by
 *       {@code $};</li>
 *   <li>has 53 characters from the alphabet above where both the salt and the hash are
 *       canonical: the unused low bits of their last characters are zero, so the salt ends in
 *       one of {@code .Oeu} and the hash in one of {@code .CGKOSWaeimquy26}.</li>
 * </ul>
 * Anything else makes {@link #matches} and {@link #needsRehash} throw
 * {@link IllegalArgumentException} whose message does not contain the string. The stored hash is
 * parsed before the password is looked at, so a malformed one throws whatever the password is.
 * Spring compares the whole string with a fresh encoding of the salt, so a hash with a
 * non-canonical salt or hash has never verified there either, and nothing is lost by refusing
 * it; it also means each hash has exactly one spelling. The 23 decoded bytes are compared in
 * constant time ({@link MessageDigest#isEqual}).
 *
 * <p>A stored hash is verified with its own cost, and any cost from 4 to 31 is parsed, as
 * Spring does. A high cost takes long: cost 31 is about two million times cost 10, that is days
 * of CPU time for one check. Whoever can write to the user storage can therefore hold a thread
 * with such a row.
 *
 * <h2>Rehashing</h2>
 * {@link #needsRehash} is {@code true} only when the stored cost is <b>lower</b> than this
 * hasher's, like Spring's {@code upgradeEncoding}. The version ({@code $2a$}, {@code $2b$} or
 * {@code $2y$}) and a higher cost give {@code false}. That differs on purpose from
 * {@link Pbkdf2PasswordHasher}, which asks for a rehash on any difference in its settings.
 */
public final class BCryptPasswordHasher implements PasswordHasher {

    /** The lowest cost {@link #of} accepts, and the lowest a stored hash may have. */
    public static final int MIN_COST = 4;
    /** The highest cost {@link #of} accepts, and the highest a stored hash may have. */
    public static final int MAX_COST = 31;
    /** The cost of {@link #defaults()}, as in Spring's {@code BCryptPasswordEncoder()}. */
    public static final int DEFAULT_COST = 10;
    /** The longest password, in bytes of UTF-8: all that bcrypt reads. */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** The length of a stored hash, in characters. */
    static final int ENCODED_CHARS = 60;
    /** Where the 53 characters of salt and hash start. */
    private static final int SALT_START = 7;
    private static final String MALFORMED = "malformed stored password hash: ";

    private final int cost;
    private final SecureRandom random = new SecureRandom();

    private BCryptPasswordHasher(int cost) {
        this.cost = cost;
    }

    /**
     * A hasher with cost {@value #DEFAULT_COST}.
     *
     * @return a new hasher
     */
    public static BCryptPasswordHasher defaults() {
        return new BCryptPasswordHasher(DEFAULT_COST);
    }

    /**
     * A hasher with the given cost. At least 10 is recommended.
     *
     * @param cost {@value #MIN_COST} to {@value #MAX_COST}; {@code 2^cost} rounds
     * @return a new hasher
     * @throws IllegalArgumentException if {@code cost} is outside {@value #MIN_COST} to
     *                                  {@value #MAX_COST}
     */
    public static BCryptPasswordHasher of(int cost) {
        if (cost < MIN_COST || cost > MAX_COST) {
            throw new IllegalArgumentException("cost must be " + MIN_COST + " to " + MAX_COST);
        }
        return new BCryptPasswordHasher(cost);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The result is {@code $2a$<cost>$<salt><hash>}, 60 characters, with this hasher's cost
     * and a fresh 16-byte salt.
     *
     * @throws IllegalArgumentException if the password is empty, longer than
     *                                  {@value #MAX_PASSWORD_BYTES} bytes of UTF-8, contains
     *                                  U+0000 or contains a lone surrogate
     */
    @Override
    public String hash(CharSequence password) {
        Objects.requireNonNull(password, "password");
        var bytes = encode(password);
        if (bytes == null) {
            throw new IllegalArgumentException("password must be 1 to " + MAX_PASSWORD_BYTES
                    + " bytes of UTF-8 without U+0000 (and no lone surrogate)");
        }
        var salt = new byte[BCrypt.SALT_BYTES];
        random.nextBytes(salt);
        try {
            return BCrypt.format('a', cost, salt, bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The stored hash is parsed first, so a malformed one throws whatever the password is.
     * The hash is verified with its own cost and version ({@code $2a$}, {@code $2b$} and
     * {@code $2y$} alike).
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
            actual = BCrypt.hash(bytes, stored.salt(), stored.cost());
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
     * <p>{@code true} only when the stored cost is lower than this hasher's. The version and a
     * higher cost give {@code false}. No bcrypt is computed.
     */
    @Override
    public boolean needsRehash(String encoded) {
        return Stored.parse(encoded).cost() < cost;
    }

    /**
     * The cost, never a hash or a password.
     *
     * @return {@code BCryptPasswordHasher[cost=<cost>]}
     */
    @Override
    public String toString() {
        return "BCryptPasswordHasher[cost=" + cost + "]";
    }

    /**
     * The password as UTF-8, or {@code null} if it is not accepted: empty, more than 72
     * {@code char}s (checked before encoding), not valid UTF-16, more than 72 bytes, or with
     * U+0000. The caller clears the result.
     */
    private static byte[] encode(CharSequence password) {
        int length = password.length();
        if (length == 0 || length > MAX_PASSWORD_BYTES) {
            return null;
        }
        for (int i = 0; i < length; i++) {
            if (password.charAt(i) == '\u0000') {
                return null;
            }
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
            if (result.isError() || result.isOverflow() || out.position() > MAX_PASSWORD_BYTES) {
                return null;
            }
            // No 0x00 byte here: in UTF-8 only U+0000 encodes to one, and it was refused above.
            return Arrays.copyOf(out.array(), out.position());
        } finally {
            Arrays.fill(out.array(), (byte) 0);
        }
    }

    /** A parsed stored hash: its cost, 16 bytes of salt and 23 bytes of hash. */
    private record Stored(int cost, byte[] salt, byte[] hash) {

        static Stored parse(String encoded) {
            Objects.requireNonNull(encoded, "encoded");
            if (encoded.length() != ENCODED_CHARS) {
                throw malformed("expected exactly " + ENCODED_CHARS + " characters");
            }
            char version = encoded.charAt(2);
            if (encoded.charAt(0) != '$' || encoded.charAt(1) != '2'
                    || (version != 'a' && version != 'b' && version != 'y') || encoded.charAt(3) != '$') {
                throw malformed("expected $2a$, $2b$ or $2y$ at the start");
            }
            char tens = encoded.charAt(4);
            char ones = encoded.charAt(5);
            if (tens < '0' || tens > '9' || ones < '0' || ones > '9' || encoded.charAt(6) != '$') {
                throw malformed("expected the cost as two digits followed by $");
            }
            int cost = (tens - '0') * 10 + (ones - '0');
            if (cost < MIN_COST || cost > MAX_COST) {
                throw malformed("cost must be " + MIN_COST + " to " + MAX_COST);
            }
            var salt = BCrypt.decode(encoded, SALT_START, BCrypt.SALT_BYTES);
            if (salt == null) {
                throw malformed("salt is not canonical bcrypt Base64");
            }
            var hash = BCrypt.decode(encoded, SALT_START + BCrypt.SALT_CHARS, BCrypt.HASH_BYTES);
            if (hash == null) {
                throw malformed("hash is not canonical bcrypt Base64");
            }
            return new Stored(cost, salt, hash);
        }

        private static IllegalArgumentException malformed(String problem) {
            return new IllegalArgumentException(MALFORMED + problem);
        }
    }
}
