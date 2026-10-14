# BCrypt-hashar och delegerande lösenordshasher med `{id}`-prefix

Projekt: psykologen (paokarlsson/psykologen), backend i Spring Boot som ska kunna gå över till Spola
Spola-version: 0.1.0

## Behov
Psykologens konton ligger i `backend/.env` som BCrypt-hashar i Spring Securitys format,
`{bcrypt}$2a$10$...`. Appen ska kunna gå över till Spola utan att alla användare måste få nya
lösenord, så Spola måste kunna verifiera de befintliga hasharna. Appen skapar också nya
hashar med ett kommando (`--hash-password=...`), och en hash som Spola skriver ska gå att
använda tillbaka i Spring om appen behöver backa. `spola.security` har bara PBKDF2 i
PHC-format i dag och säger uttryckligen nej till BCrypt och delegerande hasher
(`security/README.md`, "Avvikelser").

## Motsvarighet i Spring eller Lombok
Spring Security: `BCryptPasswordEncoder` och `DelegatingPasswordEncoder`
(`PasswordEncoderFactories.createDelegatingPasswordEncoder()`), där prefixet `{id}` väljer
algoritm och `upgradeEncoding` säger när en hash ska bytas mot standardalgoritmen.

## Användningsfall
```java
// Verifiera hashar som Spring skrev, och skapa nya i samma format.
PasswordHasher bcrypt = BCryptPasswordHasher.defaults();          // kostnad 10, $2a$ som Spring
PasswordHasher hasher = PasswordHasher.delegating("bcrypt", Map.of(
        "bcrypt", bcrypt,
        "pbkdf2", Pbkdf2PasswordHasher.defaults()));

hasher.matches("hemligt", storedFromSpring);                     // "{bcrypt}$2a$10$...", som i Spring
String stored = hasher.hash("hemligt");                          // "{bcrypt}$2a$10$..."

var users = new InMemoryUserStore();
for (var account : accounts) {                                    // från APP_AUTH_USERS_<n>_*
    users.add(new User(account.username(), account.passwordHash(), Set.of("USER"), true));
}
var authenticator = Authenticator.of(users, hasher);
```

Acceptans för appen: en hash från Springs `BCryptPasswordEncoder` (med och utan
`{bcrypt}`-prefix i den delegerande varianten) verifieras, och en hash från Spola godtas av
Springs `BCryptPasswordEncoder.matches`. Testa mot publicerade testvektorer för bcrypt
(OpenBSD/jBCrypt), inte bara mot Spolas egen `hash`.

## Krav som inte syns i koden (valfritt)
- Prefixen `$2a$`, `$2b$` och `$2y$` verifieras. Kostnad 4–31.
- BCrypt använder bara de första 72 byten av lösenordet. Spring har haft en sårbarhet här
  (CVE-2025-22228); bestäm och dokumentera vad Spola gör med längre lösenord så att två olika
  långa lösenord aldrig tyst räknas som samma.
- En felaktig lagrad hash eller ett okänt `{id}` är ett datafel, som i resten av
  `spola.security`, inte ett fel lösenord.
- `needsRehash` är sant när hashen har ett annat `{id}` än standard eller lägre kostnad, så att
  den befintliga uppgraderingen i `Authenticator` kan flytta användare till nyare inställningar.
- Konstant tid för jämförelsen. Säkerhetskritiskt: konstruerad indata räknas som realistisk.

## Lokal lösning i appen (valfritt)
Ingen. Appen använder Springs `DelegatingPasswordEncoder`.
