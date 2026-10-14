# Spec: BCrypt-hashar och delegerande {id}-hasher (spola.security)

## Uppgift
Bygg `BCryptPasswordHasher` (kompatibel med Springs `BCryptPasswordEncoder`, `$2a$`/`$2b$`/`$2y$`) och `PasswordHasher.delegating(defaultId, Map)` med `{id}`-prefix som Springs `DelegatingPasswordEncoder`, i `spola.security`, verifierat mot publicerade testvektorer. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261013-2333-security-bcrypt/mission.md` (källa `uppdrag/12-security-bcrypt-och-delegerande-hasher.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

## Krav
- `BCryptPasswordHasher` och `PasswordHasher.delegating` enligt besluten. Javadoc beskriver
  formatet, tolkningen, lösenordsreglerna (72 byte, U+0000, CVE:n), kostnaderna, `needsRehash`
  och formatet med `{id}`. Javadoc för `PasswordHasher` och för `module-info.java` nämner de nya
  implementationerna.
- En paketprivat bcrypt-primitiv som tar råa byte av vilken längd som helst, också 0 och över 72,
  så att alla vektorerna kan testas. Dessutom en paketprivat väg som formaterar hela strängen från
  ett givet salt. `hash` och vektortesterna går genom samma väg.
- `README.md` i `modules/security/` får ett nytt avsnitt "BCrypt och hashar från Spring":
  - Spring-koden (`PasswordEncoderFactories.createDelegatingPasswordEncoder()` eller
    `new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(), ...))`)
    bredvid Spola-exemplet. Exemplet är behovets kod i en testkälla (till exempel
    `BCryptExample.java`) som kompileras, och ett test kontrollerar att README visar den oförändrad.
  - Nya rader i jämförelsetabellen: `BCryptPasswordEncoder` mot `BCryptPasswordHasher`,
    `DelegatingPasswordEncoder` mot `PasswordHasher.delegating` och `upgradeEncoding` mot
    `needsRehash`.
  - I avvikelsetabellen ersätts raden "Bara PBKDF2-SHA256; ingen BCrypt ...". Nya rader tillkommer
    om: 72 byte och U+0000, kanonisk Base64, `needsRehash` bara vid lägre kostnad, alltid `$2a$`,
    exakta id utan reservhasher, och att `{pbkdf2}` är Spolas format.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i
   `spola.security.SecurityTests`.
2. Modulens gränser och de befintliga testerna:
   - `requires` och `exports` i `modules/security/src/module-info.java` är oförändrade (inga
     `requires`, `exports spola.security`).
   - Huvudkoden importerar inget från `java.lang.reflect` eller `java.lang.invoke` och använder
     inte `javax.crypto.Cipher`.
   - `git diff` visar inga ändringar under andra modulers `src/` eller `test/` och ingen ändring i
     `Build.java`.
   - Av de befintliga testfilerna i `spola.security` ändras bara `SecurityTests` (registreringen),
     `SourceRulesTests` (antalet filer, och att de nya filerna följer samma förbjudna lista, att
     `BCryptPasswordHasher` använder `new SecureRandom()` och `MessageDigest.isEqual`) och
     `ReadmeTests` (avvikelseraderna). Deras övriga förväntningar är oförändrade.
3. Behovets acceptans, genom exemplet i README (kompilerad testkälla med behovets kod):
   - `hasher.matches("hemligt", "{bcrypt}$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e")`
     är `true`, och med `"hemligT"` är det `false`.
   - `BCryptPasswordHasher.defaults().matches("hemligt", "$2a$10$C/CQ...")` är `true` (Springs
     hash utan prefix, direkt).
   - `hasher.hash("hemligt")` matchar `\{bcrypt\}\$2a\$10\$[./A-Za-z0-9]{53}`. Delen efter
     prefixet matchar Springs mönster `\A\$2(a|y|b)?\$(\d\d)\$[./0-9A-Za-z]{53}`, saltet är
     kanoniskt, och hashen verifieras både av `hasher.matches` och, utan prefix, av
     `BCryptPasswordHasher.defaults().matches`.
   - Den paketprivata vägen med `"hemligt"`, kostnad 10 och saltet `0x10`–`0x1f` ger exakt
     `$2a$10$C/CQCvOTDfaWEPmZF/ycFuvu6miZtNk3jj/4zDoWuN3Icm9TmKt9e`. Det visar att Spolas hashar
     godtas av Spring.
   - Autentiseraren från exemplet, med kontona `ada` (`{bcrypt}` + hashen för `"hemligt"`) och
     `bob` (`{bcrypt}` + hashen för `"hunter2hunter2"`), ger `Authenticated` med rollen `USER`
     för rätt lösenord och `Failed(BAD_CREDENTIALS)` för fel lösenord och för en okänd användare.
4. Ett tabelltest med de publicerade vektorerna visar att:
   - primitiven ger exakt varje sträng för de 20 vektorerna ur jBCrypt (de 15 utan kostnad 12 om
     de fem utelämnas) och för alla Openwall-vektorer, också de med råa byte `a3` och `ff ff a3`
     (byte ≥ 0x80 teckenutvidgas inte), och att `ff ff a3` med `$2a$` ger samma hashdel som med
     `$2b$`;
   - `BCryptPasswordHasher.of(4).matches(lösenord, hash)` är `true` för varje vektor vars lösenord
     är en sträng (inte råa byte) på 1–72 byte, och `false` för ett lösenord som skiljer sig i ett
     tecken;
   - samma vektorer med `$2a$` utbytt mot `$2b$` och `$2y$` verifieras också;
   - `matches("", ...)` är `false` för vektorerna med tomt lösenord;
   - **CVE-2025-22228:** för `L98`-vektorn är `matches(L72, ...)` `true`, men `matches(L98, ...)`
     är `false` och `hash(L98)` ger `IllegalArgumentException`.
5. Ett tabelltest med de oberoende vektorerna från libxcrypt visar att:
   - primitiven ger exakt varje sträng, med `$2a$`, `$2b$` och `$2y$`;
   - `matches` är `true` för varje lösenord på 1–72 byte;
   - för 73-byteslösenordet ger primitiven samma sträng som för 72 byte, men `matches` på
     72-byteshashen är `false` och `hash` ger `IllegalArgumentException`;
   - `"é"` och `"é"` ger olika hashar och verifierar inte varandra.
6. Ett test av Blowfish-kärnan visar att:
   - de härledda tabellerna har de åtta kända värdena ovan, också efter att hashar räknats (de
     ändras aldrig);
   - kärnan med Blowfishs vanliga nyckelschema och kryptering ger samma chiffertext som JDK:ns
     `Cipher.getInstance("Blowfish/ECB/NoPadding")` för minst 200 slumpade par av nyckel (4–56
     byte) och block;
   - de fem Eric Young-vektorerna stämmer.
7. Ett test av `BCryptPasswordHasher` visar att:
   - `defaults().hash` (ett anrop) matchar `\$2a\$10\$[./A-Za-z0-9]{53}` och har ett kanoniskt
     salt, och att två hashar av samma lösenord skiljer sig;
   - `of(3)`, `of(32)`, `of(-1)` och `of(Integer.MAX_VALUE)` ger `IllegalArgumentException`, och
     `of(4)` och `of(31)` går att skapa;
   - antalet varv räknas utan spill: en paketprivat hjälpmetod ger `16` för kostnad 4 och
     `2_147_483_648L` för kostnad 31, och primitiven använder den;
   - `toString()` är `BCryptPasswordHasher[cost=<kostnad>]`.
8. Ett test av lösenordsreglerna visar att `hash` ger `IllegalArgumentException` (utan lösenordet
   i meddelandet) och att `matches` mot en giltig hash ger `false` för:
   - ett tomt lösenord;
   - 73 byte ASCII;
   - `"€".repeat(24) + "a"` (73 byte) och `"€".repeat(25)` (75 byte);
   - 73 `char`;
   - ett ensamt surrogattecken;
   - `"\u0000"` och `"a\u0000a"`, där `matches("a\u0000a", hash("a"))` är `false`.

   Det visar också att `null` ger `NullPointerException` i båda, och att 72 byte (ASCII och
   `"€".repeat(24)`) godtas.
9. Ett tabelldrivet test med minst 30 felaktiga lagrade hashar visar att `matches` och
   `needsRehash` ger `IllegalArgumentException` vars meddelande inte innehåller strängen, och att
   `matches` gör det också för ett lösenord som annars avvisas. Fallen täcker:
   - tom sträng, 59 och 61 tecken, ett avslutande `\n`, och en sträng på 10 000 tecken;
   - `$2$`, `$2x$`, `$2c$`, `$2A$`, `$3a$` och ett saknat inledande `$`;
   - kostnad `00`, `03`, `32` och `99`, kostnad med mellanslag eller `+`, och en siffra utanför
     ASCII;
   - ett annat tecken än `$` efter kostnaden;
   - `+`, `=`, `-`, `_`, mellanslag, U+0000 och `é` bland de 53 tecknen;
   - icke-kanoniskt salt (`$2a$06$DCq7YPn5Rq63x1Lad4cll/TV4S6...`) och icke-kanonisk hash (sista
     tecknet `/` i stället för `.`);
   - `{bcrypt}` före en annars giltig hash, och en PBKDF2-sträng från `Pbkdf2PasswordHasher`.

   `null` ger `NullPointerException`.
10. Ett test av `needsRehash` med `of(10)` visar `true` för kostnad 04, och `false` för kostnad 10
    med `$2a$`, `$2b$` och `$2y$`, för kostnad 12 och för kostnad 31. Fallet med kostnad 31 är
    klart på under en sekund, alltså räknas ingen bcrypt.
11. Ett test av `PasswordHasher.delegating` visar att:
    - skapandet ger `NullPointerException` och `IllegalArgumentException` enligt besluten,
      inklusive ett id som är tomt, innehåller `{`, `}`, mellanslag eller ett tecken utanför ASCII,
      eller har 101 tecken;
    - en ändring i den givna kartan efteråt inte syns;
    - `hash` ger `{<standard-id>}` följt av delegatens hash, och `matches` fungerar för varje id;
    - följande ger `IllegalArgumentException` vars meddelande varken innehåller strängen eller
      texten mellan klamrarna: en ren bcrypt-hash utan prefix, `{` utan `}`, `{}`, `{BCRYPT}`,
      `{ bcrypt}`, ett okänt id, `{` + en giltig bcrypt-hash + `}` och ett id på 101 tecken;
    - `{bcrypt}` utan rest och `{bcrypt}` + en PBKDF2-sträng ger delegatens
      `IllegalArgumentException`;
    - `needsRehash` med bcrypt `of(10)` som standard ger `true` för `{bcrypt}` med kostnad 04 och
      för en giltig `{pbkdf2}`-hash, `false` för `{bcrypt}` med kostnad 10 (`$2a$` och `$2b$`) och
      12, och `IllegalArgumentException` för `{pbkdf2}` med en felaktig rest;
    - `toString()` visar standard-id:t och de sorterade id:na men ingen hash.
12. Ett test av `Authenticator` med en delegerande hasher (bcrypt `of(5)` som standard, PBKDF2
    `Pbkdf2PasswordHasher.of(10_000, 16, 32)` som `pbkdf2`, och räknande omslag kring båda) och
    `users.upgrader(hasher)` visar att:
    - en användare med `{pbkdf2}`-hash och rätt lösenord loggar in och får exakt ett
      `upgrade`-anrop med en `{bcrypt}$2a$05$`-hash som verifierar samma lösenord, och att nästa
      inloggning inte ger något anrop;
    - en användare med `{bcrypt}$2a$04$` uppgraderas till kostnad 05, medan `{bcrypt}$2a$05$` och
      `{bcrypt}$2b$05$` inte uppgraderas;
    - fel lösenord ger `BAD_CREDENTIALS` och inget anrop;
    - en okänd användare ger `BAD_CREDENTIALS` och exakt ett `matches`-anrop på bcrypt-delegaten;
    - en `{pbkdf2}`-användare med ett lösenord på 80 tecken loggar in och uppgraderingen
      misslyckas. Loggraden på `WARNING` (fångad via autentiserarens paketprivata `of` med logger)
      nämner `java.lang.IllegalArgumentException` men inte lösenordet, och hashen ligger kvar.
13. Ett test med 200 virtuella trådar som samtidigt autentiserar mot samma `Authenticator` med en
    delegerande hasher (bcrypt `of(4)` och PBKDF2 `of(10_000, 16, 32)`, blandat användare med båda
    algoritmerna och rätt och fel lösenord) visar att varje tråd får exakt sitt förväntade
    resultat. 200 samtidiga `hash`-anrop på `BCryptPasswordHasher.of(4)` ger 200 olika salt, och
    varje hash verifieras.
14. Ett test visar med markörsträngar att inget lösenord och ingen lagrad hash finns i `toString()`
    för `BCryptPasswordHasher` och den delegerande hasharen, eller i meddelandet för något undantag
    som testerna i kriterium 7–12 framkallar. Det gäller också texten mellan klamrarna i
    kriterium 11.
15. `README.md` i `modules/security/` har avsnittet "BCrypt och hashar från Spring" enligt kraven,
    med Spola-exemplet som kompilerad testkälla som ett test jämför med README. Jämförelse- och
    avvikelseraderna kontrolleras av `ReadmeTests`.
16. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten. Ingen tabell med Blowfish-konstanter finns i källkoden; de härleds från π.
17. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.
    - PROGRESS.md beskriver det levererade och säger att behovet `2026-10-13-losenord-bcrypt` är
      levererat och att koden är säkerhetskritisk och bör granskas av en människa. Det listar
      kända begränsningar:
      - lösenord över 72 byte och med U+0000 avvisas, så användare med sådana lösenord från Spring
        måste byta lösenord;
      - en lagrad kostnad på 31 kan låsa en tråd i dygn;
      - medan användare flyttas mellan algoritmer skiljer sig tiden för kända användare med en
        annan algoritm från tiden för okända, så en angripare kan se vilka användarnamn som finns;
      - ingen reservhasher för hashar utan prefix;
      - Springs `{pbkdf2}`-format stöds inte;
      - lösenordet som `String` kan inte nollställas.

      Meningen "Bara PBKDF2-HMAC-SHA256 (ingen BCrypt, ...)" under kända problem för
      `spola.security` rättas.
    - ARCHITECTURE.md får ett avsnitt "Lösenord från Spring: BCrypt och delegerande hasher
      (`spola.security`)" som beskriver:
      - varför bcrypt implementeras i Spola trots REGLER §4 och hur det är verifierat;
      - varför konstanterna härleds från π;
      - semantiken för `$2a$`, `$2b$` och `$2y$`, och varför `$2x$` inte stöds;
      - reglerna för 72 byte och U+0000;
      - den strikta tolkningen och varför kanonisk Base64 krävs;
      - kostnadsgränserna och `needsRehash`;
      - formatet med `{id}`, exakta id, att det inte finns någon reservhasher och felmeddelandena
        utan id;
      - varför primitiven är paketprivat.

    Raden för `spola.security` i modulkatalogen i `framework/MONSTER.md` (motsvarar även
    `BCryptPasswordEncoder` och `DelegatingPasswordEncoder`; ingångarna `BCryptPasswordHasher` och
    `PasswordHasher.delegating`) och i modultabellen i `README.md` är uppdaterade.

## Utanför scope
- SCrypt och Argon2, Springs `{pbkdf2}`-format (hex från `Pbkdf2PasswordEncoder`), `{noop}`,
  `{sha256}`, `{MD5}` och andra äldre kodare.
- En reservhasher för hashar utan prefix (`setDefaultPasswordEncoderForMatches`), `$2x$`, `$2$`
  och motåtgärden i crypt_blowfish:s `$2a$`.
- Val av version för nya hashar (`$2b$`/`$2y$`) och en egen `SecureRandom`.
- En publik bcrypt-primitiv, och förhashning av långa lösenord (SHA-512 före bcrypt), som inte
  går ihop med Spring.
- Ändringar i `Authenticator`, `InMemoryUserStore`, `spola.security.web` och andra moduler.
  Låsning och hastighetsbegränsning. Lika tid mellan olika algoritmer medan användare flyttas.
- Release och ny version (görs för hand).

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
