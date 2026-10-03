/**
 * Spola's security, part 1: password hashing and authentication without HTTP. It replaces the
 * parts of Spring Security that have nothing to do with requests: {@code PasswordEncoder}
 * ({@code Pbkdf2PasswordEncoder}), {@code UserDetails}/{@code UserDetailsService},
 * {@code InMemoryUserDetailsManager}, {@code DaoAuthenticationProvider} (with its protection
 * against timing differences for unknown users and its upgrade of old hashes) and an
 * authenticated principal with roles.
 *
 * <ul>
 *   <li>{@link spola.security.Pbkdf2}: PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) on
 *       {@code javax.crypto.Mac}, tested against RFC 7914 §11.</li>
 *   <li>{@link spola.security.PasswordHasher} and {@link spola.security.Pbkdf2PasswordHasher}:
 *       salted password hashes in the PHC string format, parsed strictly and fail-closed.</li>
 *   <li>{@link spola.security.User}, {@link spola.security.UserStore} and
 *       {@link spola.security.InMemoryUserStore}: users and where they are looked up.</li>
 *   <li>{@link spola.security.Authenticator}, {@link spola.security.PasswordUpgrade},
 *       {@link spola.security.AuthResult}, {@link spola.security.Failure} and
 *       {@link spola.security.Identity}: checking a username and a password.</li>
 * </ul>
 *
 * <p>The module requires no other Spola module, so it can be used on its own. It logs directly
 * on the {@link java.lang.System.Logger} named {@code spola.security}. Everything is plain Java
 * code: no annotations, no reflection. This code is security critical; have a human review it
 * before relying on it.
 */
module spola.security {
    exports spola.security;
}
