# Spec: Säkerhetsgranskning del 3 – lösenord och autentisering

## Uppgift
Rätta granskningsfynden L1–L4 i `spola.security`: villkorad uppgradering på den verifierade hashen, trasig lagrad hash ger BAD_CREDENTIALS i stället för 500, dokumenterad tidsskillnad mellan algoritmer, och högst 32 byte i nya PBKDF2-hashar. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261018-1723-sakerhet-losenord/mission.md` (källa `uppdrag/15c-sakerhet-losenord.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
