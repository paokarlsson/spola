package spola.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The bcrypt algorithm (Provos and Mazières, "A Future-Adaptable Password Scheme", USENIX 1999)
 * and its string format, as in OpenBSD's {@code bcrypt.c} and jBCrypt (Spring Security's
 * {@code BCrypt}). Package-private on purpose: this primitive takes any bytes and, like every
 * bcrypt, reads only the first 72 of them, so it would silently treat two long passwords as the
 * same (CVE-2025-22228). {@link BCryptPasswordHasher} holds the rules for passwords and stored
 * hashes and is the only public way in.
 *
 * <p>Stateless and safe to use from any number of threads.
 */
final class BCrypt {

    /** Bytes of salt. */
    static final int SALT_BYTES = 16;
    /** Bytes of the hash that are encoded (the 24th byte of the ciphertext is dropped). */
    static final int HASH_BYTES = 23;
    /** Characters of the encoded salt: four per three bytes, rounded up (22). */
    static final int SALT_CHARS = (SALT_BYTES * 4 + 2) / 3;
    /** Characters of the encoded hash: four per three bytes, rounded up (31). */
    static final int HASH_CHARS = (HASH_BYTES * 4 + 2) / 3;
    /** The lowest cost, as in Spring and OpenBSD. */
    static final int MIN_COST = 4;
    /** The highest cost, as in Spring and OpenBSD. */
    static final int MAX_COST = 31;

    /** bcrypt's Base64 alphabet: not the standard one, and used without padding. */
    static final String ALPHABET = "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** The value of each ASCII character in {@link #ALPHABET}, or -1. */
    private static final byte[] VALUES = values();

    /** The text bcrypt encrypts 64 times: {@code "OrpheanBeholderScryDoubt"} as six words. */
    private static final byte[] MAGIC = "OrpheanBeholderScryDoubt".getBytes(StandardCharsets.US_ASCII);

    private BCrypt() {
    }

    /**
     * The number of rounds of the expensive key schedule for a cost: {@code 2^cost}, as a
     * {@code long} so that cost 31 gives 2 147 483 648 and not {@code 1 << 31}, which is
     * negative in Java.
     *
     * @param cost {@value #MIN_COST} to {@value #MAX_COST}
     * @return {@code 2^cost}
     */
    static long rounds(int cost) {
        checkCost(cost);
        return 1L << cost;
    }

    /**
     * The complete string for {@code password} with the given version, cost and salt:
     * {@code $2<version>$<cost>$<22 characters of salt><31 characters of hash>}. This is the
     * path {@link BCryptPasswordHasher#hash} takes, and the one the test vectors are checked
     * through.
     *
     * @param version  {@code 'a'}, {@code 'b'} or {@code 'y'}; they are computed identically
     * @param cost     {@value #MIN_COST} to {@value #MAX_COST}
     * @param salt     {@value #SALT_BYTES} bytes
     * @param password any bytes, also none or more than 72 (only the first 72 count)
     * @return the 60-character string
     */
    static String format(char version, int cost, byte[] salt, byte[] password) {
        if (version != 'a' && version != 'b' && version != 'y') {
            throw new IllegalArgumentException("version must be a, b or y");
        }
        var hash = hash(password, salt, cost);
        try {
            return "$2" + version + "$" + (cost < 10 ? "0" : "") + cost + "$" + toBase64(salt) + toBase64(hash);
        } finally {
            Arrays.fill(hash, (byte) 0);
        }
    }

    /**
     * The 23 bytes of bcrypt for raw bytes. The key is {@code password} followed by one NUL
     * byte, repeated cyclically; each key expansion reads 72 bytes of it, so bytes after the
     * 72nd never count. The salt is used as is. {@code EksBlowfishSetup} runs
     * {@link #rounds(int) 2^cost} rounds, then {@code "OrpheanBeholderScryDoubt"} is encrypted
     * 64 times in ECB mode and the first 23 of its 24 bytes are the result. The key, the
     * Blowfish state and the intermediate blocks are cleared before returning.
     *
     * @param password any bytes, also none or more than 72; not modified
     * @param salt     {@value #SALT_BYTES} bytes; not modified
     * @param cost     {@value #MIN_COST} to {@value #MAX_COST}
     * @return {@value #HASH_BYTES} new bytes; the caller clears them
     */
    static byte[] hash(byte[] password, byte[] salt, int cost) {
        if (salt.length != SALT_BYTES) {
            throw new IllegalArgumentException("salt must be " + SALT_BYTES + " bytes");
        }
        long rounds = rounds(cost);
        var key = Blowfish.keyWords(password, true);
        var saltKey = Blowfish.keyWords(salt, false);
        var saltWords = new int[4];
        for (int i = 0; i < 4; i++) {
            saltWords[i] = word(salt, 4 * i);
        }
        var text = new int[6];
        var out = new byte[4 * text.length];
        var state = Blowfish.initial();
        try {
            // EksBlowfishSetup(cost, salt, key)
            state.expandKey(key, saltWords);
            for (long round = 0; round < rounds; round++) {
                state.expandKey(key);
                state.expandKey(saltKey);
            }
            for (int i = 0; i < text.length; i++) {
                text[i] = word(MAGIC, 4 * i);
            }
            for (int i = 0; i < 64; i++) {
                for (int block = 0; block < text.length; block += 2) {
                    state.encrypt(text, block);
                }
            }
            for (int i = 0; i < text.length; i++) {
                out[4 * i] = (byte) (text[i] >>> 24);
                out[4 * i + 1] = (byte) (text[i] >>> 16);
                out[4 * i + 2] = (byte) (text[i] >>> 8);
                out[4 * i + 3] = (byte) text[i];
            }
            return Arrays.copyOf(out, HASH_BYTES);
        } finally {
            state.clear();
            Arrays.fill(key, 0);
            Arrays.fill(saltKey, 0);
            Arrays.fill(saltWords, 0);
            Arrays.fill(text, 0);
            Arrays.fill(out, (byte) 0);
        }
    }

    /**
     * bcrypt's Base64 of {@code bytes}: three bytes to four characters, most significant bits
     * first, no padding; a last group of one or two bytes gives two or three characters whose
     * unused low bits are zero.
     *
     * @param bytes the bytes
     * @return the characters
     */
    static String toBase64(byte[] bytes) {
        var text = new StringBuilder((bytes.length * 4 + 2) / 3);
        for (int i = 0; i < bytes.length; i += 3) {
            int b0 = bytes[i] & 0xff;
            int b1 = i + 1 < bytes.length ? bytes[i + 1] & 0xff : 0;
            int b2 = i + 2 < bytes.length ? bytes[i + 2] & 0xff : 0;
            text.append(ALPHABET.charAt(b0 >>> 2));
            text.append(ALPHABET.charAt((b0 & 0x03) << 4 | b1 >>> 4));
            if (i + 1 < bytes.length) {
                text.append(ALPHABET.charAt((b1 & 0x0f) << 2 | b2 >>> 6));
            }
            if (i + 2 < bytes.length) {
                text.append(ALPHABET.charAt(b2 & 0x3f));
            }
        }
        return text.toString();
    }

    /**
     * Decodes bcrypt's Base64 strictly: exactly the characters that {@link #toBase64} gives for
     * {@code length} bytes, every one from {@link #ALPHABET}, and the unused low bits of the last
     * character zero, so that each byte string has exactly one spelling.
     *
     * @param text   the characters
     * @param start  where they start in {@code text}
     * @param length the number of bytes to decode; the number of characters follows from it
     * @return the bytes, or {@code null} if the characters are not the canonical encoding of
     *         {@code length} bytes
     */
    static byte[] fromBase64(CharSequence text, int start, int length) {
        int chars = (length * 4 + 2) / 3;
        if (start < 0 || text.length() - start < chars) {
            return null;
        }
        var bytes = new byte[length];
        int bits = 0;
        int buffered = 0;
        int written = 0;
        for (int i = 0; i < chars; i++) {
            char c = text.charAt(start + i);
            int value = c < VALUES.length ? VALUES[c] : -1;
            if (value < 0) {
                return null;
            }
            buffered = buffered << 6 | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bytes[written++] = (byte) (buffered >>> bits);
                buffered &= (1 << bits) - 1;
            }
        }
        // What is left are the unused low bits of the last character: canonical means zero.
        if (buffered != 0) {
            return null;
        }
        return bytes;
    }

    /** The big-endian word at {@code offset}, with unsigned bytes. */
    private static int word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | bytes[offset + 3] & 0xff;
    }

    /**
     * Refuses a cost outside {@value #MIN_COST} to {@value #MAX_COST}, with the message that
     * {@link BCryptPasswordHasher#of} documents.
     *
     * @param cost the cost
     * @throws IllegalArgumentException if it is outside the range
     */
    static void checkCost(int cost) {
        if (cost < MIN_COST || cost > MAX_COST) {
            throw new IllegalArgumentException("cost must be " + MIN_COST + " to " + MAX_COST);
        }
    }

    private static byte[] values() {
        var values = new byte[128];
        Arrays.fill(values, (byte) -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            values[ALPHABET.charAt(i)] = (byte) i;
        }
        return values;
    }
}
