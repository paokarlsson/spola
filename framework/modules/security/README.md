# spola.security

Säkerhet i Spola, del 1: lösenordshashning och autentisering utan HTTP. Modulen ersätter den del
av Spring Security som inte har med förfrågningar att göra: `PasswordEncoder`
(`Pbkdf2PasswordEncoder`, `BCryptPasswordEncoder` och `DelegatingPasswordEncoder` med
`{id}`-prefix), `UserDetails`/`UserDetailsService`, `InMemoryUserDetailsManager`,
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
- `BCryptPasswordHasher` (`defaults()`: kostnad 10 som Spring; `of(cost)` med 4–31): bcrypt i
  formatet från Springs `BCryptPasswordEncoder`, `$2a$10$<salt><hash>`. Verifierar `$2a$`, `$2b$`
  och `$2y$`. Lösenord är 1–72 byte UTF-8 utan U+0000. Testad mot de publicerade vektorerna från
  jBCrypt och Openwall, mot oberoende vektorer från libxcrypt och mot JDK:ns Blowfish.
- `PasswordHasher.delegating(defaultId, hashers)`: lagrar `{id}<hash>` och väljer hasher efter
  id:t, som Springs `DelegatingPasswordEncoder`; `needsRehash` flyttar användare till standard-id:t.
- `User(username, passwordHash, roles, enabled)`, `UserStore` (`find`) och `InMemoryUserStore`
  (`add`, `find`, `updatePassword` för lösenordsbyten och `upgrader(hasher)` för villkorad
  uppgradering). Användarnamn jämförs exakt (skiftlägeskänsligt).
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
        return Authenticator.of(users, hasher, users.upgrader(hasher));
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

I produktion skickas `Pbkdf2PasswordHasher.defaults()` in som `hasher`. `users.upgrader(hasher)`
är uppgraderingen: en användare vars hash har andra inställningar (till exempel 20 000
iterationer) får en ny hash med de aktuella inställningarna vid nästa lyckade inloggning.
Uppgraderingen byter bara en hash som fortfarande behöver det (`needsRehash`), i ett atomärt
steg, så ett lösenordsbyte (`users.updatePassword`) som hinner emellan skrivs aldrig över med en
hash av det gamla lösenordet. En databas blir en `UserStore`-lambda och en
`PasswordUpgrade`-lambda som gör samma villkorade uppdatering.

## BCrypt och hashar från Spring

En app som går över från Spring Security kan behålla sina lagrade hashar, så att användarna inte
behöver nya lösenord. `BCryptPasswordHasher` läser och skriver samma format som Springs
`BCryptPasswordEncoder`, och `PasswordHasher.delegating` väljer algoritm efter prefixet `{id}`
som Springs `DelegatingPasswordEncoder`. En hash som Spola skriver (`{bcrypt}$2a$10$...`) godtas
av Spring, så appen kan också backa.

### Spring Security

```java
@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // Eller PasswordEncoderFactories.createDelegatingPasswordEncoder(), som har fler id.
        return new DelegatingPasswordEncoder("bcrypt", Map.of(
                "bcrypt", new BCryptPasswordEncoder(),                     // kostnad 10, $2a$
                "pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    InMemoryUserDetailsManager users(AuthProperties properties) {
        var users = new InMemoryUserDetailsManager();
        for (var account : properties.users()) {                           // från APP_AUTH_USERS_<n>_*
            users.createUser(User.withUsername(account.username()).password(account.passwordHash())
                    .roles("USER").build());
        }
        return users;
    }
}

// encoder.matches("hemligt", storedFromSpring)                            "{bcrypt}$2a$10$..."
// encoder.encode("hemligt")                                               "{bcrypt}$2a$10$..."
```

### Spola

```java
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The README example: hashes from Spring's BCryptPasswordEncoder, verified and made in the same format. */
public final class BCryptExample {

    private BCryptExample() {
    }

    /**
     * An account as the application reads it, from {@code APP_AUTH_USERS_<n>_USERNAME} and
     * {@code APP_AUTH_USERS_<n>_PASSWORD_HASH}.
     *
     * @param username     the username
     * @param passwordHash the stored hash, such as {@code {bcrypt}$2a$10$...} from Spring
     */
    public record Account(String username, String passwordHash) {
    }

    /**
     * The hasher: new hashes with bcrypt, and stored hashes with {@code {bcrypt}} or
     * {@code {pbkdf2}}.
     *
     * @return the hasher
     */
    public static PasswordHasher hasher() {
        // Verifiera hashar som Spring skrev, och skapa nya i samma format.
        PasswordHasher bcrypt = BCryptPasswordHasher.defaults();          // kostnad 10, $2a$ som Spring
        PasswordHasher hasher = PasswordHasher.delegating("bcrypt", Map.of(
                "bcrypt", bcrypt,
                "pbkdf2", Pbkdf2PasswordHasher.defaults()));
        return hasher;
    }

    /**
     * Checks a password against a hash that Spring wrote.
     *
     * @param hasher           the hasher from {@link #hasher()}
     * @param password         the password
     * @param storedFromSpring the stored hash
     * @return whether the password is right
     */
    public static boolean check(PasswordHasher hasher, String password, String storedFromSpring) {
        return hasher.matches(password, storedFromSpring);               // "{bcrypt}$2a$10$...", som i Spring
    }

    /**
     * A new hash to store, for example from the command {@code --hash-password=...}.
     *
     * @param hasher   the hasher from {@link #hasher()}
     * @param password the password
     * @return the hash, which Spring's {@code DelegatingPasswordEncoder} also accepts
     */
    public static String hashPassword(PasswordHasher hasher, String password) {
        String stored = hasher.hash(password);                           // "{bcrypt}$2a$10$..."
        return stored;
    }

    /**
     * The users and the authenticator.
     *
     * @param hasher   the hasher from {@link #hasher()}
     * @param accounts the accounts from the environment
     * @return the authenticator
     */
    public static Authenticator authenticator(PasswordHasher hasher, List<Account> accounts) {
        var users = new InMemoryUserStore();
        for (var account : accounts) {                                    // från APP_AUTH_USERS_<n>_*
            users.add(new User(account.username(), account.passwordHash(), Set.of("USER"), true));
        }
        var authenticator = Authenticator.of(users, hasher);
        return authenticator;
    }
}
```

- `hasher.matches("hemligt", "{bcrypt}$2a$10$...")` verifierar en hash som Spring skrev. En ren
  hash utan prefix, direkt från `BCryptPasswordEncoder`, verifieras med
  `BCryptPasswordHasher.defaults().matches`.
- `hasher.hash("hemligt")` ger `{bcrypt}$2a$10$<22 tecken salt><31 tecken hash>`, som Springs
  `DelegatingPasswordEncoder` godtar (och utan prefix `BCryptPasswordEncoder`).
- `Authenticator.of(users, hasher)` gör dummyhashen för okända användare med standardalgoritmen
  (`{bcrypt}`). Med uppgradering, `Authenticator.of(users, hasher, users.upgrader(hasher))`, får en
  användare med `{pbkdf2}`-hash eller lägre bcrypt-kostnad en ny `{bcrypt}`-hash med kostnad 10
  vid nästa lyckade inloggning.
- **Ge bcrypt minst den högsta lagrade kostnaden.** `defaults()` ovan passar hashar från
  `new BCryptPasswordEncoder()` (kostnad 10). Kommer hasharna från
  `new BCryptPasswordEncoder(12)`, använd `BCryptPasswordHasher.of(12)`. En hash med högre
  kostnad än hasharens byts aldrig ut (`needsRehash` är bara `true` för en lägre kostnad), men
  dummyhashen för okända användare görs med hasharens kostnad. Med `defaults()` och hashar med
  kostnad 12 tar en känd användare med fel lösenord därför ungefär fyra gånger så lång tid som en
  okänd, och skillnaden går aldrig över, så en angripare kan se vilka användarnamn som finns. Höjer
  appen kostnaden senare är användarna med lägre kostnad i stället snabbare än okända tills de har
  loggat in och fått en ny hash, som när användare flyttas mellan algoritmer.
- Ett bcrypt-lösenord är 1–72 byte UTF-8 utan U+0000. bcrypt läser bara 72 byte, och Spring
  godtog förr två lösenord med samma första 72 byte som samma (CVE-2025-22228). Spola avvisar
  längre lösenord: `hash` kastar `IllegalArgumentException` och `matches` ger `false`. En
  användare vars lösenord i Spring var längre än 72 byte måste därför byta lösenord.
- En lagrad hash verifieras med sin egen kostnad (4–31). Kostnad 31 är ungefär 2 miljoner gånger
  kostnad 10, alltså dygn; den som kan skriva i användarlagringen kan låsa en tråd med en sådan rad.
- Id:na är appens namn. `{pbkdf2}` läser här Spolas PHC-format (`Pbkdf2PasswordHasher`), inte
  hex-formatet från Springs `Pbkdf2PasswordEncoder`.

## Spring Security och Spola

| Spring Security | Spola |
|---|---|
| `PasswordEncoder` (`encode`, `matches`, `upgradeEncoding`) | `PasswordHasher` (`hash`, `matches`, `needsRehash`) |
| `Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()` | `Pbkdf2PasswordHasher.defaults()` (600 000 iterationer, 16 byte salt, 32 byte hash) |
| `new Pbkdf2PasswordEncoder(secret, saltLength, iterations, algorithm)` | `Pbkdf2PasswordHasher.of(iterations, saltBytes, hashBytes)` |
| `BCryptPasswordEncoder` (`new BCryptPasswordEncoder()`, `new BCryptPasswordEncoder(strength)`) | `BCryptPasswordHasher` (`defaults()` med kostnad 10, `of(cost)` med 4–31) |
| `DelegatingPasswordEncoder` / `PasswordEncoderFactories.createDelegatingPasswordEncoder()` | `PasswordHasher.delegating(defaultId, Map.of(id, hasher, ...))` |
| `upgradeEncoding` (BCrypt: lägre kostnad; delegerande: ett annat id än standard) | `needsRehash` (samma regler) |
| `UserDetails` / `User.withUsername(...)...build()` | `record User(username, passwordHash, roles, enabled)` |
| `UserDetailsService.loadUserByUsername` (kastar `UsernameNotFoundException`) | `UserStore.find` (ger `Optional`) |
| `InMemoryUserDetailsManager` | `InMemoryUserStore` (`add`, `find`, `updatePassword`, `upgrader`) |
| `UserDetailsPasswordService.updatePassword(user, newPassword)` | `PasswordUpgrade.upgrade(username, newHash)`, t.ex. `store.upgrader(hasher)` |
| `DaoAuthenticationProvider` + `authenticate(token)` | `Authenticator.of(store, hasher, upgrade)` + `authenticate(username, password)` |
| `Authentication` med `getName()` och `getAuthorities()` | `Identity(name, roles)` med `hasRole` |
| `BadCredentialsException`, `DisabledException` | `AuthResult.Failed(Failure.BAD_CREDENTIALS)`, `AuthResult.Failed(Failure.DISABLED)` |
| `userNotFoundEncodedPassword` (skydd mot tidsskillnader) | fast dummyhash, kontrolleras exakt en gång för okända och ogiltiga användarnamn |

## Avvikelser från Spring Security

| Avvikelse | Varför |
|---|---|
| Ingen SCrypt, Argon2 eller äldre kodare (`{noop}`, `{sha256}`, `{MD5}`) | Bara det som har behövts: PBKDF2-SHA256 (officiella testvektorer i RFC 7914 §11, på JDK:ns `Mac`) och BCrypt (för hashar från Spring, verifierad mot publicerade testvektorer). |
| PHC-format för PBKDF2 (`$pbkdf2-sha256$i=…$salt$hash`) i stället för Springs `{pbkdf2}` med hex | Iterationer, salt- och hashlängd står i strängen, så gamla hashar kan verifieras och uppgraderas. |
| BCrypt-lösenord är 1–72 byte UTF-8 och får inte innehålla U+0000 | bcrypt läser bara 72 byte, så ett längre lösenord skulle tyst räknas som sina första 72 (CVE-2025-22228), och med NUL kan två olika lösenord ge samma nyckel. `hash` kastar och `matches` ger `false`; en användare med ett längre lösenord från Spring måste byta det. |
| BCrypt-hashar kräver kanonisk Base64 (oanvända bitar noll) och exakt 60 tecken | Spring jämför hela strängen med en ny kodning av saltet, så en icke-kanonisk hash har aldrig verifierats där heller. Varje hash har en enda stavning, och en felaktig ger undantag. |
| `needsRehash` bara vid lägre kostnad för BCrypt | Som Springs `upgradeEncoding`: versionen (`$2a$`, `$2b$`, `$2y$`) och en högre kostnad ger `false`. `Pbkdf2PasswordHasher` vill däremot ha en ny hash vid varje skillnad i inställningarna. Eftersom en högre kostnad aldrig sänks ska hasharens kostnad vara minst den högsta lagrade (`of(12)` för hashar från `new BCryptPasswordEncoder(12)`); annars tar kända användare för alltid längre tid än okända (dummyhashen har hasharens kostnad), och tiden avslöjar vilka användarnamn som finns. |
| Alltid `$2a$` för nya BCrypt-hashar | Som Springs standard; ingen `BCryptVersion`. `$2b$` och `$2y$` verifieras och räknas likadant (som i jBCrypt); `$2x$` och `$2$` stöds inte. |
| Exakta id, ingen reservhasher för hashar utan prefix | Id:t jämförs skiftlägeskänsligt, och en hash utan `{id}` eller med okänt id är ett datafel (`IllegalArgumentException`), som med Springs standard; ingen `setDefaultPasswordEncoderForMatches`. Meddelandet innehåller aldrig texten mellan klamrarna, som kan vara en hash. En ren hash från `BCryptPasswordEncoder` verifieras med `BCryptPasswordHasher`. |
| `{pbkdf2}` är Spolas format | Id:na är appens namn: `"pbkdf2"` läser Spolas PHC-format (`Pbkdf2PasswordHasher`), inte hex-formatet från Springs `Pbkdf2PasswordEncoder`, som inte stöds. |
| `DISABLED` bara efter rätt lösenord | Spring kontrollerar avstängda konton före lösenordet; då kan den som inte kan lösenordet ta reda på att kontot är avstängt. |
| En felaktig lagrad hash ger undantag i stället för `false` | Det är ett datafel och inte ett fel lösenord, och ska inte se ut som ett misslyckat inloggningsförsök. |
| Ingen normalisering av lösenord eller användarnamn | `"é"` (U+00E9) och `"e"` + U+0301 är olika lösenord; användarnamn jämförs skiftlägeskänsligt. |
| Roller utan `ROLE_`-prefix | `hasRole("ADMIN")` jämför med exakt det som står i `User`. |
| Resultat som värde (`AuthResult`) i stället för undantag | Ett misslyckat försök är ett vanligt utfall; `switch` över det förseglade `AuthResult` är fullständig. |
| Ingen låsning och ingen hastighetsbegränsning | Utanför del 1; görs utanför modulen eller i ett senare uppdrag. |
| Uppgraderingen är villkorad: den byter bara en hash som fortfarande `needsRehash` | Annars kan en inloggning med det gamla lösenordet, vars nya hash räknas fram medan lösenordet byts, skriva över bytet så att det gamla lösenordet fungerar igen. |
| Ett fel i uppgraderingen stoppar inte inloggningen | Lösenordet var rätt; undantaget loggas på `WARNING` (bara klassnamnet) och den gamla hashen ligger kvar. |
