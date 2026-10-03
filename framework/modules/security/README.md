# spola.security

Säkerhet i Spola, del 1: lösenordshashning och autentisering utan HTTP. Modulen ersätter den del
av Spring Security som inte har med förfrågningar att göra: `PasswordEncoder`
(`Pbkdf2PasswordEncoder`), `UserDetails`/`UserDetailsService`, `InMemoryUserDetailsManager`,
`DaoAuthenticationProvider` (med skyddet mot tidsskillnader för okända användare och
uppgraderingen av gamla hashar) och `Authentication` med roller. Allt är vanlig Java-kod utan
annoteringar och utan reflektion. Modulen är fristående och kräver ingen annan Spola-modul.
Filterkedjan, HTTP Basic, formulärinloggning och 401/403 kommer i del 2 (10b).

> **Säkerhetskritisk kod.** Låt en människa granska modulen innan den används på riktigt.

- `Pbkdf2.deriveSha256(password, salt, iterations, lengthBytes)`: PBKDF2-HMAC-SHA256 (RFC 8018
  §5.2) på `javax.crypto.Mac`, testad mot RFC 7914 §11 och mot JDK:ns `SecretKeyFactory`.
- `PasswordHasher` (`hash`, `matches`, `needsRehash`) och `Pbkdf2PasswordHasher`
  (`defaults()`: 600 000 iterationer, 16 byte salt, 32 byte hash; `of(iterations, saltBytes,
  hashBytes)`). Lösenord kodas som UTF-8 utan normalisering och får vara 1–1 024 tecken.
- Lagringsformat: `$pbkdf2-sha256$i=<iterationer>$<salt>$<hash>` med standard-Base64 utan
  utfyllnad. Tolkningen är strikt; en felaktig lagrad hash ger `IllegalArgumentException` vars
  meddelande inte innehåller strängen.
- `User(username, passwordHash, roles, enabled)`, `UserStore` (`find`) och `InMemoryUserStore`
  (`add`, `find`, `updatePassword`). Användarnamn jämförs exakt (skiftlägeskänsligt).
- `Authenticator.of(store, hasher[, upgrade])` och `authenticate(username, password)`, som ger
  `AuthResult.Authenticated(Identity)` eller `AuthResult.Failed(Failure)` med `BAD_CREDENTIALS`
  eller `DISABLED`.

## Ett komplett exempel

Två användare i minnet (en avstängd), PBKDF2-hashar, och en autentiserare som uppgraderar hashar
med gamla inställningar när användaren loggar in.

### Spring Security

```java
@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    InMemoryUserDetailsManager users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("ada").password(encoder.encode("correct horse battery staple"))
                        .roles("USER", "ADMIN").build(),
                User.withUsername("bob").password(encoder.encode("hunter2hunter2"))
                        .roles("USER").disabled(true).build());
    }

    @Bean
    AuthenticationProvider authenticationProvider(InMemoryUserDetailsManager users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        provider.setUserDetailsPasswordService(users); // uppgraderar gamla hashar
        return provider;
    }
}

@Service
class LoginService {
    private final AuthenticationProvider provider;

    LoginService(AuthenticationProvider provider) {
        this.provider = provider;
    }

    String login(String username, String password) {
        try {
            var auth = provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
            var admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return admin ? "welcome, administrator " + auth.getName() : "welcome, " + auth.getName();
        } catch (DisabledException e) {
            return "your account is disabled";
        } catch (BadCredentialsException e) {
            return "wrong username or password";
        }
    }
}
```

### Spola

```java
import java.util.Set;

/** The README example: users in memory, PBKDF2 hashes, and an authenticator that upgrades old hashes. */
public final class LoginExample {

    private LoginExample() {
    }

    /**
     * The users and the authenticator, wired by hand.
     *
     * @param hasher {@code Pbkdf2PasswordHasher.defaults()} in production
     * @param users  where the users live; also stores upgraded hashes
     * @return the authenticator
     */
    public static Authenticator authenticator(PasswordHasher hasher, InMemoryUserStore users) {
        users.add(new User("ada", hasher.hash("correct horse battery staple"), Set.of("USER", "ADMIN"), true));
        users.add(new User("bob", hasher.hash("hunter2hunter2"), Set.of("USER"), false));
        return Authenticator.of(users, hasher, users::updatePassword);
    }

    /**
     * One login attempt, as a controller would answer it.
     *
     * @param authenticator the authenticator
     * @param username      the username from the request
     * @param password      the password from the request
     * @return what to tell the user
     */
    public static String login(Authenticator authenticator, String username, String password) {
        return switch (authenticator.authenticate(username, password)) {
            case AuthResult.Authenticated(Identity identity) when identity.hasRole("ADMIN") ->
                    "welcome, administrator " + identity.name();
            case AuthResult.Authenticated(Identity identity) -> "welcome, " + identity.name();
            case AuthResult.Failed(Failure reason) when reason == Failure.DISABLED -> "your account is disabled";
            case AuthResult.Failed failed -> "wrong username or password";
        };
    }
}
```

I produktion skickas `Pbkdf2PasswordHasher.defaults()` in som `hasher`. `users::updatePassword`
är uppgraderingen: en användare vars hash har andra inställningar (till exempel 20 000
iterationer) får en ny hash med de aktuella inställningarna vid nästa lyckade inloggning. En
databas blir en `UserStore`-lambda och en `PasswordUpgrade`-lambda.

## Spring Security och Spola

| Spring Security | Spola |
|---|---|
| `PasswordEncoder` (`encode`, `matches`, `upgradeEncoding`) | `PasswordHasher` (`hash`, `matches`, `needsRehash`) |
| `Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()` | `Pbkdf2PasswordHasher.defaults()` (600 000 iterationer, 16 byte salt, 32 byte hash) |
| `new Pbkdf2PasswordEncoder(secret, saltLength, iterations, algorithm)` | `Pbkdf2PasswordHasher.of(iterations, saltBytes, hashBytes)` |
| `UserDetails` / `User.withUsername(...)...build()` | `record User(username, passwordHash, roles, enabled)` |
| `UserDetailsService.loadUserByUsername` (kastar `UsernameNotFoundException`) | `UserStore.find` (ger `Optional`) |
| `InMemoryUserDetailsManager` | `InMemoryUserStore` (`add`, `find`, `updatePassword`) |
| `UserDetailsPasswordService.updatePassword(user, newPassword)` | `PasswordUpgrade.upgrade(username, newHash)`, t.ex. `store::updatePassword` |
| `DaoAuthenticationProvider` + `authenticate(token)` | `Authenticator.of(store, hasher, upgrade)` + `authenticate(username, password)` |
| `Authentication` med `getName()` och `getAuthorities()` | `Identity(name, roles)` med `hasRole` |
| `BadCredentialsException`, `DisabledException` | `AuthResult.Failed(Failure.BAD_CREDENTIALS)`, `AuthResult.Failed(Failure.DISABLED)` |
| `userNotFoundEncodedPassword` (skydd mot tidsskillnader) | fast dummyhash, kontrolleras exakt en gång för okända och ogiltiga användarnamn |

## Avvikelser från Spring Security

| Avvikelse | Varför |
|---|---|
| Bara PBKDF2-SHA256; ingen BCrypt, SCrypt, Argon2 eller delegerande hasher | Det finns officiella testvektorer för PBKDF2-HMAC-SHA256 (RFC 7914 §11), och algoritmen bygger på JDK:ns `Mac`. |
| PHC-format (`$pbkdf2-sha256$i=…$salt$hash`) i stället för `{pbkdf2}`-prefix och hex | Iterationer, salt- och hashlängd står i strängen, så gamla hashar kan verifieras och uppgraderas. |
| `DISABLED` bara efter rätt lösenord | Spring kontrollerar avstängda konton före lösenordet; då kan den som inte kan lösenordet ta reda på att kontot är avstängt. |
| En felaktig lagrad hash ger undantag i stället för `false` | Det är ett datafel och inte ett fel lösenord, och ska inte se ut som ett misslyckat inloggningsförsök. |
| Ingen normalisering av lösenord eller användarnamn | `"é"` (U+00E9) och `"e"` + U+0301 är olika lösenord; användarnamn jämförs skiftlägeskänsligt. |
| Roller utan `ROLE_`-prefix | `hasRole("ADMIN")` jämför med exakt det som står i `User`. |
| Resultat som värde (`AuthResult`) i stället för undantag | Ett misslyckat försök är ett vanligt utfall; `switch` över det förseglade `AuthResult` är fullständig. |
| Ingen låsning och ingen hastighetsbegränsning | Utanför del 1; görs utanför modulen eller i ett senare uppdrag. |
| Ett fel i uppgraderingen stoppar inte inloggningen | Lösenordet var rätt; undantaget loggas på `WARNING` (bara klassnamnet) och den gamla hashen ligger kvar. |
