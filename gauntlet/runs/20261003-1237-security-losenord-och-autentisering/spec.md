# Spec: Säkerhet del 1 — lösenordshashning och autentisering (`spola.security`)

## Uppgift
Bygg den nya fristående modulen `spola.security`: PBKDF2-HMAC-SHA256 på `javax.crypto.Mac`
(`Pbkdf2`), `PasswordHasher`/`Pbkdf2PasswordHasher` med PHC-lagringsformat och strikt
fail-closed-tolkning, `User`, `UserStore`, `InMemoryUserStore`, `Authenticator` med skydd för
okända användare, `DISABLED` bara efter rätt lösenord och hashuppgradering via `PasswordUpgrade`,
samt `AuthResult`/`Failure`/`Identity`. Alla designbeslut, gränser och regler står i
`mission.md` (samma katalog) och är bindande; vid tvekan gäller `mission.md` ordagrant.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

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


Dessutom gäller alla punkter under **Krav** och **Designbeslut** i `mission.md` (typer,
signaturer, gränser, felbeteenden, Javadoc, README) som krav på arbetsprodukten.

## Utanför scope
- Filterkedja, `SecurityContext` per förfrågan, HTTP Basic, formulärinloggning,
  auktoriseringsregler, 401/403 och integration i `spola.web` (10b).
- JWT, sessioner, CSRF och CORS (11).
- BCrypt, SCrypt, Argon2 och andra algoritmer, och en delegerande hasher.
- Låsning av konton, hastighetsbegränsning, lösenordspolicy och "kom ihåg mig".
- Användarlagring i databas och integration i `spola.demo`.

## Antaganden
- Inga andra moduler ändras; i `Build.java` ändras bara `MODULES` med den nya raden.
- Tester med 600 000 iterationer hålls till ett fåtal anrop så att bygget inte blir orimligt långsamt;
  övriga tester använder `Pbkdf2PasswordHasher.of(10_000, 16, 32)` eller lägre via `Pbkdf2` direkt.
- Max rundor: 5 (enligt `mission.md`).
