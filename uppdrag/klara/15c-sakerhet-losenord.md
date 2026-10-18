# Säkerhetsgranskning del 3: lösenord och autentisering (`spola.security`)

## Mål
Granskningen (`gauntlet/granskningar/2026-10-18-sakerhet.md`) bekräftade att BCrypt och Blowfish
stämmer mot libxcrypt i över 1 200 fall, men hittade fyra svagheter runt dem: en uppgradering av en
hash kan skriva tillbaka det gamla lösenordet efter ett lösenordsbyte, en trasig lagrad hash avslöjar
att användarnamnet finns, tidsskillnaden mellan algoritmer är odokumenterad, och PBKDF2 med lång hash
kostar servern dubbelt men inte angriparen. Efter uppdraget ska alla fyra vara rättade eller
dokumenterade. Det här är **15c**, tredje delen av rättningarna efter granskningen.

## Bakgrund / kontext
Läs `framework/REGLER.md`, `PROGRESS.md` och `ARCHITECTURE.md`, särskilt "Säkerhet del 1" och
"Lösenord från Spring: BCrypt och delegerande hasher". Så länge Spola är `0.x` får API:er ändras.

- **L1 (LOW).** `PasswordUpgrade.upgrade(username, newHash)` och `InMemoryUserStore.upgrader` byter
  bara en hash som fortfarande `needsRehash`, men kontrollerar inte att den lagrade hashen är den som
  just verifierades. Skrivs ett lösenordsbyte med äldre inställningar medan en uppgradering pågår (en
  rullande driftsättning som höjer kostnaden) skriver uppgraderingen över det med en hash av det
  gamla lösenordet. En prob (två instanser, kostnad 4 och 5, låst uppgradering) gav: gamla lösenordet
  `Authenticated`, nya `BAD_CREDENTIALS`.
- **L2 (LOW).** För en känd användare med en lagrad hash som inte går att tolka kastar `matches`
  `IllegalArgumentException` oavsett lösenord, vilket blir 500, medan okända användare får 401. En
  förfrågan per namn avslöjar vilka konton som finns. Efter en flytt från Springs
  `createDelegatingPasswordEncoder()` gäller det alla rader med `{noop}`, `{sha256}`, `{MD5}`, Springs
  hex-`{pbkdf2}`, `$2$` m.fl.
- **L3 (LOW).** `Authenticator`s Javadoc lovar utan villkor att okända användare kostar lika mycket.
  Med flera algoritmer i den delegerande hashern syns användare som ännu ligger som `{pbkdf2}`, och
  ett lösenord över 72 byte ger ett brusfritt mått (bcrypt nekar det utan arbete, PBKDF2 räknar).
- **L4 (LOW).** `Pbkdf2PasswordHasher.of(iterations, saltBytes, hashBytes)` tar upp till 64 byte.
  Över 32 byte räknar PBKDF2-HMAC-SHA256 två block, så servern betalar dubbelt medan en angripare bara
  behöver första blocket.

## Krav
- **Villkorad uppgradering (L1).** Uppgraderingen blir en compare-and-set på den verifierade hashen:
  `PasswordUpgrade.upgrade(username, verifiedHash, newHash)`, och `InMemoryUserStore` byter bara om
  den lagrade hashen fortfarande är `verifiedHash`. Javadoc och README visar motsvarande SQL
  (`UPDATE ... SET hash = ? WHERE username = ? AND hash = ?`). Befintliga anropare och tester
  uppdateras.
- **Trasig hash ger inte bort kontot (L2).** `Authenticator.authenticate` fångar
  `IllegalArgumentException` från `matches` för en hittad användare, kör samma dummykontroll som för
  en okänd användare, loggar på `WARNING` med bara undantagets klassnamn (aldrig hashen eller
  lösenordet) och svarar `Failed(BAD_CREDENTIALS)`. Datafelet syns alltså i loggen men inte utåt.
  Hasharnas egna `matches` kastar som i dag.
- **Dokumenterad tidsskillnad (L3).** `Authenticator`s och `PasswordHasher.delegating`s Javadoc,
  README och PROGRESS säger att tidslikheten bara gäller inom en algoritm och kostnad, och att
  användare som ligger kvar i en gammal algoritm kan särskiljas tills de loggat in en gång.
- **Högst 32 byte i nya PBKDF2-hashar (L4).** `Pbkdf2PasswordHasher.of` godtar högst 32 byte för
  nya hashar; befintliga hashar upp till 64 byte verifieras fortfarande, och `needsRehash` är sant för
  dem så att de flyttas till 32.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. Inga ändringar utanför `framework/modules/security`, `framework/modules/security-web` (bara om ett
   anrop till `PasswordUpgrade` måste följa med), `framework/modules/demo` (samma skäl) och
   dokumentationen. `requires`/`exports` oförändrade, ingen reflektion, inga nya filtyper.
3. L1: ett test återskapar probens förlopp (uppgradering spärrad med en latch, lösenordsbyte med
   äldre inställningar emellan) och visar att det nya lösenordet fungerar och det gamla inte gör det
   efteråt. Ett test med 200 virtuella trådar som loggar in och byter lösenord om vartannat visar att
   det sista lösenordet alltid gäller.
4. L2: ett test visar att en känd användare med en trasig eller okänd hash (minst `{noop}x`,
   `{sha256}...`, en avkortad bcrypt-hash och en tom sträng) ger `Failed(BAD_CREDENTIALS)` och inte
   ett undantag, att exakt en dummykontroll körs, att en `WARNING`-rad med bara klassnamnet loggas,
   och att varken hashen eller lösenordet finns i loggen. Genom `HttpBasic` ger samma användare 401,
   inte 500.
5. L4: ett test visar att `of(..., ..., 33)` och `of(..., ..., 64)` ger `IllegalArgumentException`,
   att en lagrad hash med 64 byte fortfarande verifieras och att `needsRehash` är sant för den.
6. L3: Javadoc för `Authenticator` och `PasswordHasher.delegating` innehåller förbehållet; README och
   PROGRESS likaså.
7. Guardrails gröna och ingen regel i `framework/REGLER.md` bruten.
8. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade (den villkorade uppgraderingen, felhanteringen
   för trasiga hashar, gränsen 32 byte och att L1–L4 är rättade).

## Utanför scope
- Att utjämna tiden mellan algoritmer i koden.
- Hastighetsbegränsning och låsning av konton.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Säkerhetskritiskt: konstruerad indata räknas som realistisk.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
