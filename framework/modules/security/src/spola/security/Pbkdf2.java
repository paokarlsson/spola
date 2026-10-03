package spola.security;

import java.io.Serial;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * PBKDF2 with HMAC-SHA-256 as the pseudorandom function (RFC 8018 §5.2), built on
 * {@link Mac} ({@code HmacSHA256}) from the JDK. Tested against the PBKDF2-HMAC-SHA256 vectors
 * of RFC 7914 §11 and against the JDK's {@code SecretKeyFactory} ({@code PBKDF2WithHmacSHA256}).
 *
 * <p>This is the low-level primitive: it takes the password as bytes, so the caller decides how
 * text becomes bytes ({@link Pbkdf2PasswordHasher} uses UTF-8), and it makes no policy
 * decisions (any iteration count from 1 is accepted). Applications use
 * {@link Pbkdf2PasswordHasher}.
 *
 * <p>The {@code Mac} is initialised once per call, so the key (the password) is processed once
 * and each block costs {@code iterations} HMAC computations. The class is stateless and safe to
 * use from any number of threads.
 */
public final class Pbkdf2 {

    /** The largest derived key, in bytes, that {@link #deriveSha256} produces. */
    public static final int MAX_LENGTH_BYTES = 1024;

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int BLOCK_BYTES = 32;

    private Pbkdf2() {
    }

    /**
     * Derives a key with PBKDF2-HMAC-SHA256.
     *
     * <p>The arguments are not modified; the caller keeps ownership of {@code password} and
     * should clear it when done. The internal copies of the password and of the intermediate
     * blocks are cleared before returning.
     *
     * @param password    the password bytes (P), at least one byte
     * @param salt        the salt (S), at least one byte
     * @param iterations  the iteration count (c), at least 1
     * @param lengthBytes the length of the derived key (dkLen), 1 to {@value #MAX_LENGTH_BYTES}
     * @return a new array of {@code lengthBytes} bytes
     * @throws IllegalArgumentException if {@code password} or {@code salt} is {@code null} or
     *                                  empty, {@code iterations} is less than 1 or
     *                                  {@code lengthBytes} is outside 1 to {@value #MAX_LENGTH_BYTES}
     */
    public static byte[] deriveSha256(byte[] password, byte[] salt, int iterations, int lengthBytes) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must be at least one byte");
        }
        if (salt == null || salt.length == 0) {
            throw new IllegalArgumentException("salt must be at least one byte");
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1");
        }
        if (lengthBytes < 1 || lengthBytes > MAX_LENGTH_BYTES) {
            throw new IllegalArgumentException("lengthBytes must be 1 to " + MAX_LENGTH_BYTES);
        }
        var key = new PasswordKey(password.clone());
        var u = new byte[BLOCK_BYTES];
        var t = new byte[BLOCK_BYTES];
        var result = new byte[lengthBytes];
        try {
            var mac = Mac.getInstance(HMAC_SHA256);
            mac.init(key);
            int blocks = (lengthBytes + BLOCK_BYTES - 1) / BLOCK_BYTES;
            for (int block = 1; block <= blocks; block++) {
                // U_1 = PRF(P, S || INT(i))
                mac.update(salt);
                mac.update((byte) (block >>> 24));
                mac.update((byte) (block >>> 16));
                mac.update((byte) (block >>> 8));
                mac.update((byte) block);
                mac.doFinal(u, 0);
                System.arraycopy(u, 0, t, 0, BLOCK_BYTES);
                // U_j = PRF(P, U_{j-1}); T_i = U_1 xor U_2 xor ... xor U_c
                for (int j = 2; j <= iterations; j++) {
                    mac.update(u);
                    mac.doFinal(u, 0);
                    for (int k = 0; k < BLOCK_BYTES; k++) {
                        t[k] ^= u[k];
                    }
                }
                int offset = (block - 1) * BLOCK_BYTES;
                System.arraycopy(t, 0, result, offset, Math.min(BLOCK_BYTES, lengthBytes - offset));
            }
            return result;
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every Java platform; this cannot happen on a working JDK.
            throw new IllegalStateException("HmacSHA256 is not available: " + e.getClass().getName());
        } finally {
            key.destroy();
            Arrays.fill(u, (byte) 0);
            Arrays.fill(t, (byte) 0);
        }
    }

    /**
     * The password as a raw HMAC key. Unlike {@code SecretKeySpec}, it can be destroyed, so the
     * copy made here does not stay in memory until it is garbage collected. The {@code Mac}
     * receives its own copy from {@link #getEncoded()}.
     */
    private static final class PasswordKey implements SecretKey {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient byte[] bytes; // never serialized: a serialized key would carry the password
        private transient volatile boolean destroyed;

        PasswordKey(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public String getAlgorithm() {
            return HMAC_SHA256;
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            if (destroyed) {
                throw new IllegalStateException("key destroyed");
            }
            return bytes.clone();
        }

        @Override
        public void destroy() {
            Arrays.fill(bytes, (byte) 0);
            destroyed = true;
        }

        @Override
        public boolean isDestroyed() {
            return destroyed;
        }

        @Override
        public String toString() {
            return "PasswordKey";
        }
    }
}
