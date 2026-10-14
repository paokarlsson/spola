package spola.security;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Blowfish (Schneier, 1993) as bcrypt needs it: a mutable state of the P-array and the four
 * S-boxes that a key schedule can change in place, which the JDK's {@code Cipher} does not
 * allow. Package-private; {@link BCrypt} is the only user outside the tests.
 *
 * <p>The initial P-array (18 words) and S-boxes (4 × 256 words) are, by Blowfish's definition,
 * the first 33 344 bits of the fractional part of π, in order. They are not copied into the
 * source: {@link Pi} computes them once per JVM with {@link BigInteger} (Machin's formula) the
 * first time they are needed. Every state works on its own copy, and the derived tables are
 * never changed.
 *
 * <p>A state is not thread-safe; each hash creates its own and {@linkplain #clear() clears} it
 * when done.
 */
final class Blowfish {

    /** Words in the P-array: 16 rounds plus two output whitening words. */
    static final int P_WORDS = 18;
    /** Words in the four S-boxes together, S0 to S3 one after the other. */
    static final int S_WORDS = 4 * 256;

    private final int[] p;
    private final int[] s;

    private Blowfish() {
        this.p = Arrays.copyOf(Pi.WORDS, P_WORDS);
        this.s = Arrays.copyOfRange(Pi.WORDS, P_WORDS, P_WORDS + S_WORDS);
    }

    /**
     * A state with the initial tables (the digits of π), before any key schedule.
     *
     * @return a new state with its own copy of the tables
     */
    static Blowfish initial() {
        return new Blowfish();
    }

    /**
     * A state after Blowfish's ordinary key schedule (for the tests against the JDK's
     * {@code Cipher} and the published Blowfish vectors).
     *
     * @param key 1 to 72 bytes, used cyclically (Blowfish proper takes 4 to 56)
     * @return the keyed state
     */
    static Blowfish withKey(byte[] key) {
        var state = initial();
        var words = keyWords(key, false);
        try {
            state.expand(words, null);
            return state;
        } finally {
            Arrays.fill(words, 0);
        }
    }

    /**
     * The 18 words a key schedule XORs into the P-array: the key's bytes (followed by one NUL
     * byte when {@code nulTerminated}) repeated cyclically, read big-endian, 72 bytes in all.
     * Bytes are unsigned: a byte of 0x80 or more is never sign-extended into the other three.
     *
     * @param key           the key bytes; may be empty only when {@code nulTerminated}
     * @param nulTerminated whether a NUL byte follows the key in the cycle
     * @return 18 new words; the caller clears them
     */
    static int[] keyWords(byte[] key, boolean nulTerminated) {
        int cycle = key.length + (nulTerminated ? 1 : 0);
        if (cycle == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        var words = new int[P_WORDS];
        int position = 0;
        for (int i = 0; i < P_WORDS; i++) {
            int word = 0;
            for (int b = 0; b < 4; b++) {
                int value = position < key.length ? key[position] & 0xff : 0;
                word = word << 8 | value;
                position = position + 1 == cycle ? 0 : position + 1;
            }
            words[i] = word;
        }
        return words;
    }

    /**
     * One key expansion: XORs {@code keyWords} into the P-array, then replaces the P-array and
     * the S-boxes, two words at a time, with the encryption of a running block that starts at
     * zero. With {@code saltWords}, the running block is first XORed with the next two salt
     * words (cyclically) before each encryption: bcrypt's {@code ExpandKey(state, salt, key)}.
     * Without, this is Blowfish's ordinary key schedule ({@code ExpandKey(state, 0, key)}).
     *
     * @param keyWords  the 18 words from {@link #keyWords}
     * @param saltWords the four words of a 16-byte salt, or {@code null}
     */
    void expand(int[] keyWords, int[] saltWords) {
        int[] p = this.p;
        int[] s = this.s;
        for (int i = 0; i < P_WORDS; i++) {
            p[i] ^= keyWords[i];
        }
        int l = 0;
        int r = 0;
        int salt = 0;
        for (int i = 0; i < P_WORDS; i += 2) {
            if (saltWords != null) {
                l ^= saltWords[salt];
                r ^= saltWords[salt + 1];
                salt ^= 2;
            }
            long block = encrypt(l, r);
            l = (int) (block >>> 32);
            r = (int) block;
            p[i] = l;
            p[i + 1] = r;
        }
        for (int i = 0; i < S_WORDS; i += 2) {
            if (saltWords != null) {
                l ^= saltWords[salt];
                r ^= saltWords[salt + 1];
                salt ^= 2;
            }
            long block = encrypt(l, r);
            l = (int) (block >>> 32);
            r = (int) block;
            s[i] = l;
            s[i + 1] = r;
        }
    }

    /**
     * Encrypts one 64-bit block in place.
     *
     * @param block  the words; {@code block[offset]} is the left (high) half
     * @param offset where the block starts
     */
    void encrypt(int[] block, int offset) {
        long result = encrypt(block[offset], block[offset + 1]);
        block[offset] = (int) (result >>> 32);
        block[offset + 1] = (int) result;
    }

    /**
     * Encrypts the block {@code (left, right)}: 16 Feistel rounds with P[0] to P[15], then the
     * halves swapped back and whitened with P[16] and P[17].
     *
     * @return the left half in the high 32 bits, the right half in the low 32 bits
     */
    private long encrypt(int left, int right) {
        int[] p = this.p;
        int[] s = this.s;
        int l = left ^ p[0];
        int r = right;
        // Two rounds per pass; after a pass, l already carries the next round's P word.
        for (int i = 1; i < 17; i += 2) {
            r ^= f(s, l) ^ p[i];
            l ^= f(s, r) ^ p[i + 1];
        }
        r ^= p[17];
        return (long) r << 32 | (l & 0xffffffffL);
    }

    /** Blowfish's round function: {@code ((S0[a] + S1[b]) ^ S2[c]) + S3[d]} for the bytes a, b, c, d of x. */
    private static int f(int[] s, int x) {
        return ((s[x >>> 24] + s[0x100 | (x >>> 16) & 0xff]) ^ s[0x200 | (x >>> 8) & 0xff]) + s[0x300 | x & 0xff];
    }

    /** Overwrites the P-array and the S-boxes with zeros; the state is unusable afterwards. */
    void clear() {
        Arrays.fill(p, 0);
        Arrays.fill(s, 0);
    }

    /**
     * A copy of the initial P-array, as derived from π (for the tests).
     *
     * @return 18 new words
     */
    static int[] initialP() {
        return Arrays.copyOf(Pi.WORDS, P_WORDS);
    }

    /**
     * A copy of the initial S-boxes, S0 to S3 one after the other (for the tests).
     *
     * @return 1024 new words
     */
    static int[] initialS() {
        return Arrays.copyOfRange(Pi.WORDS, P_WORDS, P_WORDS + S_WORDS);
    }

    /**
     * The fractional part of π as 32-bit words, computed when the class is first used (once per
     * JVM; class initialisation is thread-safe). The array is never handed out or written.
     */
    private static final class Pi {

        /** The P-array's 18 words followed by the S-boxes' 1024: 33 344 bits of π. */
        static final int[] WORDS = fraction(P_WORDS + S_WORDS);

        /** Extra bits below the last needed one, so that rounding errors never reach it. */
        private static final int GUARD_BITS = 64;

        private Pi() {
        }

        /**
         * The first {@code words} × 32 bits of π's fractional part, from Machin's formula
         * {@code π = 16·arctan(1/5) − 4·arctan(1/239)} in fixed point with
         * {@value #GUARD_BITS} guard bits. Each of the roughly 9 300 series terms is truncated
         * by at most two units in the last place, so the error stays below 2^20 units, far
         * under the guard bits.
         */
        static int[] fraction(int words) {
            int bits = words * 32;
            var one = BigInteger.ONE.shiftLeft(bits + GUARD_BITS);
            var pi = arctanOfInverse(5, one).shiftLeft(4).subtract(arctanOfInverse(239, one).shiftLeft(2));
            var fraction = pi.subtract(one.multiply(BigInteger.valueOf(3))).shiftRight(GUARD_BITS);
            var result = new int[words];
            for (int i = 0; i < words; i++) {
                result[i] = fraction.shiftRight(bits - 32 * (i + 1)).intValue();
            }
            return result;
        }

        /** {@code arctan(1/x)} scaled by {@code one}: the series {@code Σ (−1)^k / ((2k+1)·x^(2k+1))}. */
        private static BigInteger arctanOfInverse(int x, BigInteger one) {
            var square = BigInteger.valueOf((long) x * x);
            var power = one.divide(BigInteger.valueOf(x));
            var sum = power;
            for (int k = 1; power.signum() != 0; k++) {
                power = power.divide(square);
                var term = power.divide(BigInteger.valueOf(2L * k + 1));
                sum = (k & 1) == 1 ? sum.subtract(term) : sum.add(term);
            }
            return sum;
        }
    }
}
