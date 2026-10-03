Source: uppdrag/10a-security-losenord-och-autentisering.md

# Säkerhet del 1: lösenordshashning och autentisering (`spola.security`)

## Mål
En ny, fristående modul `spola.security` ska ersätta den del av Spring Security som inte har med
HTTP att göra: `PasswordEncoder` (`Pbkdf2PasswordEncoder`), `UserDetails`/`UserDetailsService`,
`InMemoryUserDetailsManager`, `DaoAuthenticationProvider` (med skyddet mot tidsskillnader för
okända användare och uppgradering av gamla hashar) och `Authentication` med roller. Allt ska vara
vanlig Java-kod utan annoteringar och utan reflektion, och PBKDF2 ska testas mot officiella
testvektorer (REGLER §4). Det här är **10a**. Filterkedjan i `spola.web`, autentisering av
förfrågningar (HTTP Basic och formulärinloggning), auktoriseringsregler och 401/403 kommer i
**10b**. Det här uppdraget rör säkerhetskritisk kod: PROGRESS.md ska säga att en människa bör
granska det innan ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–09 är klara (rad 08 med 08a–08c, rad 09 med 09a och 09b). Läs
`framework/REGLER.md`, `framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du börjar,
särskilt avsnitten om `spola.validation` (en fristående modul utan `requires` på andra
Spola-moduler, som mönster) och om `spola.http` (loggning direkt på `System.Logger` med
loggernamnet lika med modulnamnet). Alla tester skrivs med `spola.test`.

Det som finns och som 10a ska passa ihop med:
- `Build.java` har en uttrycklig modullista (`MODULES`) med en testingång per modul och ordnar
  modulerna efter deras `requires`. En ny modul läggs till där.
- `spola.http` och `spola.web` loggar direkt på `System.Logger` (inte via `spola.util`), så att
  de är fristående. `spola.security` gör likadant.
- I 10b ska `spola.web` få `requires spola.security` (eller tvärtom, beslutas då). 10a får därför
  inte bero på `spola.web`, `spola.http` eller någon annan Spola-modul.

Erfarenhet från tidigare iterationer: text som kan hamna i en loggrad eller ett undantag ska
vara kort och fri från råa styrtecken, och hemligheter (lösenord, hashar) får aldrig ekas.
Gränser ska kontrolleras innan arbete eller minne hinner växa. Beteendet för `null`, tomma
värden, felaktiga data och fel i användarkod ska vara bestämt och testat, inte underförstått.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Modul:** `spola.security` i `modules/security/`, paketet `spola.security`. Inga `requires` på
  andra Spola-moduler. Loggning sker på `System.Logger` med namnet `spola.security`. Modulen läggs
  till i `MODULES` i `Build.java` med testingången `spola.security.SecurityTests`. Inga andra
  moduler ändras.
- **PBKDF2 implementeras på `javax.crypto.Mac` (`HmacSHA256`) enligt RFC 8018 §5.2**, i en
  publik lågnivåklass `Pbkdf2` med
  `static byte[] deriveSha256(byte[] password, byte[] salt, int iterations, int lengthBytes)`.
  Motivering: lösenordet blir bytes på ett enda, uttryckligt sätt (UTF-8, se nedan) i stället för
  att bero på hur en `SecretKeyFactory`-leverantör kodar `char[]`. `Mac`-instansen initieras en
  gång per anrop (nyckeln hashas då en gång), så kostnaden är `iterations` HMAC-anrop per block.
  Lågnivåmetoden kräver `iterations >= 1`, `1 <= lengthBytes <= 1024`, ett icke-tomt lösenord
  och ett salt; annars `IllegalArgumentException`. Den gör inga policybedömningar. Bara
  HMAC-SHA-256 stöds: det finns officiella testvektorer för den (RFC 7914 §11), och fler
  algoritmer utan officiella vektorer läggs inte till.
- **`PasswordHasher` är gränssnittet** (motsvarar `PasswordEncoder`):
  ```java
  public interface PasswordHasher {
      String hash(CharSequence password);
      boolean matches(CharSequence password, String encoded);
      boolean needsRehash(String encoded);
  }
  ```
  `Pbkdf2PasswordHasher` är den enda implementationen i 10a. Den skapas med
  `Pbkdf2PasswordHasher.defaults()` (600 000 iterationer, 16 byte salt, 32 byte hash, enligt
  OWASP:s rekommendation för PBKDF2-HMAC-SHA256) eller `Pbkdf2PasswordHasher.of(iterations,
  saltBytes, hashBytes)` med gränserna `10_000 <= iterations <= 10_000_000`,
  `16 <= saltBytes <= 64` och `16 <= hashBytes <= 64` (annars `IllegalArgumentException`).
  Salt tas från en `SecureRandom` som skapas i hasharen; ingen annan slumpkälla används.
- **Lösenordet** kodas som UTF-8 (`StandardCharsets.UTF_8`, `CharsetEncoder` med
  `CodingErrorAction.REPORT`). Byte-arrayen nollställs efter användning. Regler:
  - `null` ger `NullPointerException` (i både `hash` och `matches`).
  - Ett tomt lösenord, ett lösenord längre än **1 024 tecken** (`Pbkdf2PasswordHasher.MAX_PASSWORD_CHARS`)
    och ett lösenord med ett ensamt surrogattecken ger `IllegalArgumentException` i `hash` och
    `false` i `matches`. Längdgränsen kontrolleras före kodningen och före PBKDF2.
  - Ingen normalisering (varken Unicode eller blanksteg). Det står i Javadoc.
- **Lagringsformatet är en PHC-sträng:** `$pbkdf2-sha256$i=<iterations>$<salt>$<hash>`, där
  `<salt>` och `<hash>` är standard-Base64 (`+/`) **utan** utfyllnad. Hashens längd är längden på
  den avkodade `<hash>`. Tolkningen är strikt och fail-closed:
  - exakt fem delar avgränsade av `$` med tom första del, id exakt `pbkdf2-sha256`, parametern
    exakt `i=` följt av decimala siffror utan inledande nolla, `1 <= i <= 10_000_000`;
  - Base64 är kanonisk (avkodning och ny kodning ger samma text), salt 8–64 byte och hash 16–64
    byte;
  - hela strängen är högst 256 tecken (kontrolleras först).

  En sträng som bryter mot formatet ger `IllegalArgumentException` i `matches` och `needsRehash`
  med ett meddelande som **inte** innehåller strängen (den kan vara en hash från databasen).
  Motivering: en felaktig lagrad hash är ett datafel och inte ett felaktigt lösenord, och ska
  inte se ut som "fel lösenord". Iterationsgränsen vid tolkning (lägre än vid skapande) gör att
  äldre hashar med färre iterationer kan verifieras och uppgraderas, medan en korrupt rad med
  miljarder iterationer inte kan låsa en tråd.
- **Jämförelsen sker i konstant tid** med `MessageDigest.isEqual`.
- **`needsRehash`** är `true` när den lagrade hashens iterationer, saltlängd eller hashlängd
  skiljer sig från hasharens inställningar.
- **Användare och lagring** (motsvarar `UserDetails`/`UserDetailsService`):
  - `User(String username, String passwordHash, Set<String> roles, boolean enabled)` är ett
    record. Konstruktorn gör en oföränderlig kopia av `roles` och kräver icke-`null` överallt,
    ett användarnamn med 1–256 tecken och roller som är 1–100 tecken utskrivbar ASCII utan
    mellanslag. `toString()` visar **inte** `passwordHash`.
  - `UserStore` är ett funktionellt gränssnitt: `Optional<User> find(String username)`.
  - `InMemoryUserStore` är trådsäker och har `add(User)` (dubblett ger
    `IllegalArgumentException`), `find` och `updatePassword(String username, String newHash)`.
  - Användarnamn jämförs exakt (skiftlägeskänsligt, ingen normalisering). Det står i Javadoc.
- **`Authenticator`** (motsvarar `DaoAuthenticationProvider`) skapas med
  `Authenticator.of(UserStore store, PasswordHasher hasher)` och
  `Authenticator.of(store, hasher, PasswordUpgrade upgrade)`, där `PasswordUpgrade` är ett
  funktionellt gränssnitt `void upgrade(String username, String newHash)`.
  `authenticate(String username, CharSequence password)` ger det förseglade
  `AuthResult`:
  - `Authenticated(Identity identity)`;
  - `Failed(Failure reason)` med `enum Failure { BAD_CREDENTIALS, DISABLED }`.

  Regler:
  - `Identity(String name, Set<String> roles)` är ett record med `hasRole(String)`. Det
    motsvarar Springs `Authentication` efter inloggning och bär aldrig lösenord eller hash.
  - **Okänd användare:** hasharen anropas ändå exakt en gång, mot en fast dummyhash som skapas
    med samma hasher när `Authenticator` skapas, och resultatet är `BAD_CREDENTIALS`. Samma
    arbete görs alltså för okända och kända användare (Springs `userNotFoundEncodedPassword`).
  - Ett användarnamn som är `null`, tomt eller längre än 256 tecken och ett lösenord som är
    `null` ger `BAD_CREDENTIALS` (inget undantag), men dummyhashen kontrolleras ändå en gång.
  - **`DISABLED` ges bara efter att lösenordet stämt.** Annars är resultatet `BAD_CREDENTIALS`,
    så att den som inte kan lösenordet inte kan ta reda på om ett konto är avstängt.
  - **Uppgradering:** när lösenordet stämt, kontot är aktivt och `needsRehash` är `true` anropas
    `upgrade` med en ny hash av samma lösenord. Ett undantag från `upgrade` (inte ett `Error`)
    fångas och loggas på `WARNING` på loggern `spola.security` med bara undantagets klassnamn,
    och inloggningen lyckas ändå. Utan `PasswordUpgrade` görs ingen uppgradering.
  - Ett undantag från `UserStore.find` och ett `IllegalArgumentException` från en felaktig lagrad
    hash släpps igenom oförändrade (datafel, inte inloggningsfel).
  - Ingen låsning efter misslyckade försök och ingen hastighetsbegränsning i 10a.

## Krav
- Modulen `spola.security` med `module-info.java` (Javadoc som beskriver modulen och att den är
  fristående) och typerna `Pbkdf2`, `PasswordHasher`, `Pbkdf2PasswordHasher`, `User`,
  `UserStore`, `InMemoryUserStore`, `Authenticator`, `PasswordUpgrade`, `AuthResult`
  (med `Authenticated` och `Failed`), `Failure` och `Identity` enligt besluten ovan.
- Publikt API dokumenteras med Javadoc, inklusive lagringsformatet, gränserna, UTF-8-kodningen
  utan normalisering, felbeteendet för felaktiga hashar och skyddet för okända användare.
- `README.md` i `modules/security/` med ett exempel bredvid motsvarande Spring Security-kod
  (`PasswordEncoder`-böna, `InMemoryUserDetailsManager`, `DaoAuthenticationProvider` med
  `UserDetailsPasswordService`). Spola-delen är en testkälla som kompileras, och ett test
  kontrollerar att README visar den oförändrad (som README-exemplen i `spola.web`). README har en
  jämförelsetabell och avvikelserader (bara PBKDF2-SHA256, PHC-format i stället för
  `{pbkdf2}`-prefix, `DISABLED` bara efter rätt lösenord, felaktig hash ger undantag i stället
  för `false`, ingen normalisering).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.security.SecurityTests`
   körs som modulens svit.
2. `modules/security/src/module-info.java` har inga `requires` på andra Spola-moduler. Ett test
   fäster modulens exakta `requires` (som `SourceRulesTests` i `spola.web`). Huvudkoden
   importerar inget från `java.lang.reflect` eller `java.lang.invoke`. `git diff` visar inga
   ändringar under andra modulers `src/` eller `test/` och i `Build.java` bara den nya raden i
   `MODULES`.
3. Ett test visar att `Pbkdf2.deriveSha256` ger exakt RFC 7914 §11:s vektorer för
   PBKDF2-HMAC-SHA256:
   - `P="passwd"`, `S="salt"`, `c=1`, `dkLen=64` ger
     `55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783`;
   - `P="Password"`, `S="NaCl"`, `c=80000`, `dkLen=64` ger
     `4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d`.

   Testet visar också att resultatet är lika med JDK:ns `SecretKeyFactory`
   (`PBKDF2WithHmacSHA256`) för minst 50 slumpade fall (lösenord med ASCII och tecken utanför
   ASCII inklusive surrogatpar, salt 8–64 byte, 1–3 000 iterationer, längder 1–100 byte, så att
   både ett och flera block täcks), och att ogiltiga argument ger `IllegalArgumentException`.
4. Ett test av `Pbkdf2PasswordHasher` visar att:
   - `hash` ger en sträng som exakt matchar `\$pbkdf2-sha256\$i=600000\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}`
     för `defaults()` (ett anrop), och att två hashar av samma lösenord skiljer sig (olika salt);
   - `matches` är `true` för rätt lösenord och `false` för fel lösenord, ett lösenord som skiljer
     sig i ett tecken, ett tomt lösenord, ett lösenord över 1 024 tecken och ett ensamt
     surrogattecken;
   - en hash som byggts för hand med `Pbkdf2.deriveSha256` och ett känt salt verifieras (formatet
     är exakt det dokumenterade);
   - lösenord med tecken utanför ASCII kodas som UTF-8 (verifierat mot `deriveSha256` med
     `getBytes(UTF_8)`), och `"é"` (U+00E9) och `"é"` ger olika hashar;
   - `hash` ger `IllegalArgumentException` för tomt, för långt och ensamt surrogat, och
     `NullPointerException` för `null`; `of(...)` utanför gränserna ger `IllegalArgumentException`;
   - `needsRehash` är `false` för hasharens egna hashar och `true` när iterationer, saltlängd
     eller hashlängd skiljer sig.
5. Ett tabelldrivet test med minst 25 felaktiga lagrade hashar visar att `matches` och
   `needsRehash` ger `IllegalArgumentException` vars meddelande inte innehåller strängen. Fallen
   täcker: fel id, `pbkdf2-sha512`, saknad del, en extra del, utfyllnad `=`, Base64 med `-_`,
   icke-kanonisk Base64, inledande nolla i `i`, `i=0`, `i=10000001`, `i` som inte är ett tal, ett
   `i` som spiller över `int`, för kort och för långt salt, för kort och för lång hash, en sträng
   på 257 tecken, styrtecken, tom sträng och `null` (`NullPointerException`).
6. Ett test av `User`, `InMemoryUserStore` och `Identity` visar konstruktorns kontroller
   (`null`, tomt och för långt användarnamn, ogiltiga roller), att `roles` inte går att ändra
   (inte heller via den ursprungliga mängden), att `toString()` inte innehåller hashen, att en
   dubblett ger `IllegalArgumentException`, att `updatePassword` byter hashen och att sökningen
   är skiftlägeskänslig.
7. Ett test av `Authenticator` med en räknande hasher (som omsluter en
   `Pbkdf2PasswordHasher.of(10_000, 16, 32)`) visar att:
   - rätt lösenord ger `Authenticated` med exakt namn och roller;
   - fel lösenord ger `Failed(BAD_CREDENTIALS)`;
   - en okänd användare, ett `null`-, tomt eller 257 tecken långt användarnamn och ett
     `null`-lösenord ger `Failed(BAD_CREDENTIALS)` och anropar `matches` **exakt en gång**
     (samma antal som för en känd användare med fel lösenord);
   - ett avstängt konto ger `Failed(DISABLED)` med rätt lösenord och `Failed(BAD_CREDENTIALS)`
     med fel lösenord;
   - en hash med 20 000 iterationer leder till exakt ett `upgrade`-anrop med en hash som
     `needsRehash` godkänner och som verifierar samma lösenord, och efter uppgraderingen i
     `InMemoryUserStore` sker inget nytt anrop; fel lösenord eller avstängt konto ger inget
     anrop;
   - ett `RuntimeException` från `upgrade` ger ändå `Authenticated`, och ett `Error` släpps
     igenom;
   - ett undantag från `UserStore.find` och en felaktig lagrad hash släpps igenom som samma
     instans respektive som `IllegalArgumentException`.
8. Ett test med 200 virtuella trådar som samtidigt autentiserar mot samma `Authenticator` och
   `InMemoryUserStore` (blandat rätt och fel lösenord, med `Pbkdf2PasswordHasher.of(10_000, 16, 32)`)
   visar att varje tråd får exakt sitt eget förväntade resultat.
9. Ett test visar att varken lösenord eller hash finns i `toString()` för `User`, `Identity`,
   `AuthResult` eller i meddelandet för något undantag som tester i kriterium 4–7 framkallar
   (kontrollerat med markörsträngar).
10. `README.md` i `modules/security/` har exemplet bredvid Spring Security (som kompilerad
    testkälla som ett test jämför med README), jämförelsetabellen och avvikelseraderna enligt
    kraven.
11. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
12. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md beskriver det levererade,
    anger att rad 10 i `PLAN.md` fortsätter i 10b, listar kända begränsningar (bara PBKDF2,
    ingen låsning eller hastighetsbegränsning, ingen normalisering) och säger att modulen är
    säkerhetskritisk och bör granskas av en människa. ARCHITECTURE.md får ett avsnitt
    "Säkerhet del 1" som beskriver:
    - varför modulen är fristående och loggar på `System.Logger`;
    - varför PBKDF2 byggs på `Mac` och hur lösenordet kodas;
    - standardparametrarna och gränserna vid skapande respektive tolkning;
    - PHC-formatet och den strikta tolkningen;
    - varför en felaktig hash ger undantag och inte `false`;
    - skyddet för okända användare och varför `DISABLED` bara ges efter rätt lösenord;
    - uppgraderingen och varför ett fel i den inte stoppar inloggningen.

## Utanför scope
- Filterkedja, `SecurityContext` per förfrågan, HTTP Basic, formulärinloggning,
  auktoriseringsregler, 401/403 och integration i `spola.web` (10b).
- JWT, sessioner, CSRF och CORS (11).
- BCrypt, SCrypt, Argon2 och andra algoritmer, och en delegerande hasher med flera algoritmer.
- Låsning av konton, hastighetsbegränsning, lösenordspolicy (längd, komplexitet) och
  "kom ihåg mig".
- Användarlagring i databas (kommer med `spola.data`), och integration i `spola.demo`.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion och inga
namnkonventioner. Kryptografin bygger bara på JDK:ns primitiver (`Mac`, `SecureRandom`,
`MessageDigest.isEqual`) och testas mot RFC 7914 §11. Inga nya filtyper i `framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
