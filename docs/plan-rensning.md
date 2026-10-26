# Plan: rensa bort det överflödiga i Spola utan att psykologen går sönder

Bygger på `docs/overflodigt-i-spola.md`. Skriven 2026-10-26.

## Vad som tas bort, och vad som får vara kvar

| Del | Beslut | Varför |
|---|---|---|
| `spola.di` (hela modulen) | **Tas bort** | Ingen ramverksmodul och ingen app använder den, bara `demo`. Wiring för hand i `main` ger samma sak. |
| `ToString` i `spola.util` | **Tas bort** | Records har `toString`. Enda användningen är `demo/AuditSettings.java`. |
| `Unchecked`, `UncheckedException`, `ThrowingRunnable/Supplier/Function/Consumer` | **Tas bort** | Används inte utanför `util`. JDK:n har `UncheckedIOException`, och en vanlig `try`/`catch` räcker. |
| `Lazy` | Får vara kvar | Psykologen använder den. `StableValue` är bara preview i Java 25. Ses över när JEP 502 blir final. |
| `spola.codegen` | Får vara kvar | Motiverad tills derived record creation (JEP 468) finns. |
| `Log`, `spola.http` | Får vara kvar | Diskutabla, men inget som tar skada av att de finns. Rörs inte i den här rensningen. |

Allt annat (`json`, `web`, `security`, `security.web`, `validation`, `config`, `client`, `test`,
`guardrails`) rörs inte.

## Vad psykologen faktiskt använder

Från `backend/lib/SHA256SUMS` och importerna i `backend/src` (läget 2026-10-26, Spola 0.2.0):

- **Jar-filer:** `spola.http`, `spola.json`, `spola.validation`, `spola.web`, `spola.config`,
  `spola.util`, `spola.security`, `spola.security.web`, `spola.client` och `spola.test`.
  Varken `spola.di` eller `spola.codegen` finns med.
- **Ur `spola.util`:** bara `Lazy`, `Logging` och `LoggingSettings`. Ingen `ToString`, ingen
  `Unchecked` och inga `Throwing*`. (`UncheckedIOException` i `FileSessionArtifactStore` kommer
  från JDK:n.)

**Det här betyder att ingen del som ska bort används av psykologen.** Riskerna sitter i
kanterna:

1. `java Build.java upgrade <version>` i psykologen avbryter om releasen saknar en jar som står i
   `SHA256SUMS`. Alla tio modulerna ovan måste finnas kvar i nästa release, med samma namn.
2. En rensning kan av misstag ta med något som psykologen använder, till exempel om `Lazy`,
   `Logging` eller `LoggingSettings` ändras "i förbifarten" medan `util` städas.
3. Psykologen kan under tiden börja använda något som står på listan. Kontrollera därför
   importerna igen precis före releasen (steg 4).

## Steg

### Steg 1: Skydd före ändringarna (för hand, utanför loopen)

Skriv `scripts/app-check.sh <sökväg till appens backend>`. Skriptet bygger och testar en app mot
de lokala jar-filerna i `framework/out/release/` utan att ändra något i appen:

1. Kopiera appens `backend/` till en tillfällig katalog.
2. Läs modulnamnen ur appens `lib/SHA256SUMS` och skriv om filen med samma moduler från
   `framework/out/release/SHA256SUMS`. Saknas en modul i releasen avbryter skriptet med
   modulens namn, precis som `upgrade` hade gjort.
3. Kopiera dit jar-filerna ur `framework/out/release/`. Appens `fetch` ser då att
   kontrollsummorna stämmer och laddar inte ner något.
4. Kör `java Build.java` i kopian (kompilering och appens tester) och skicka vidare exitkoden.

Skriptet ligger i `scripts/` och inte i `framework/`, eftersom det handlar om appar och inte om
ramverket. Prova det först mot 0.2.0 (`java Build.java release` och sedan
`scripts/app-check.sh ../psykologen/backend`). Det ska gå grönt innan något tas bort.

Lägg till en rad i `scripts/release.sh`: om `SPOLA_APPS` är satt (sökvägar med kolon emellan)
körs `app-check.sh` för varje app efter `java Build.java release` och före taggen. En röd app
stoppar releasen.

### Steg 2: Uppdrag till loopen

Tre handskrivna uppdrag enligt `uppdrag/MALL.md`. De får nummer 16 och uppåt, så loopen tar dem
före behovet om omförsök som väntar i `uppdrag/behov/`. Vill du att omförsöken kommer först
(psykologen väntar på dem): numrera i stället uppdragen efter att planeraren gjort om behovet.

**`16a-ta-bort-di.md`**
- Skriv om `demo` så att `InventoryApp` kopplar ihop allt för hand i `main`, med
  try-with-resources för det som ska stängas och `ScopedValue` direkt där scopet `CALL` användes.
- Ta bort `framework/modules/di/` och dess rad i `MODULES` i `Build.java`.
- Uppdatera `MONSTER.md` (modulkatalogen och ett stycke om att wiring görs för hand i `main`),
  `README.md`, `docs/app-claude.md`, `ARCHITECTURE.md`, `PROGRESS.md` och `config/README.md`
  samt `demo/README.md`. Ändra raden för 03 i `uppdrag/PLAN.md` till "borttagen i 16a".
- Acceptans: `java Build.java` grönt, `grep -r "spola.di" framework/ README.md docs/app-claude.md`
  ger ingen träff utanför historiken, och `java Build.java release` skriver inga `spola.di`-filer.

**`16b-util-ta-bort-tostring-och-unchecked.md`**
- Ta bort `ToString`, `Unchecked`, `UncheckedException` och de fyra `Throwing*` med tester.
- `demo/AuditSettings` blir ett record (eller en klass) med en egen `toString()` som döljer
  hemligheten.
- Uppdatera `util/README.md`, `MONSTER.md` (Lombok-kolumnen för `spola.util`), `ARCHITECTURE.md`
  och `PROGRESS.md`.
- Acceptans: `java Build.java` grönt, och **`Lazy`, `Logging`, `LoggingSettings`, `Log` och
  `LineFormatter`/`LineHandler` har samma publika API som i 0.2.0** (inga ändrade signaturer). Det
  sista är psykologens skydd och ska stå med som kriterium, så att kritikerna kontrollerar det.

**`16c-katalogen-och-behovsprovningen.md`** (litet, bara dokumentation)
- Lägg till i `MONSTER.md` under "Hör det hemma i Spola?": det som Java 25 redan ger med rimlig
  ergonomi (records, try-with-resources, `ScopedValue`, wiring i `main`) byggs inte, även om
  Spring eller Lombok har det.
- Uppdatera `docs/overflodigt-i-spola.md` med vad som togs bort och i vilken version.

### Steg 3: Låt loopen köra

`scripts/gauntlet.sh --iterations 3`, följ med `scripts/gauntlet.sh status`. Varje uppdrag blir
en egen commit, så ett uppdrag som går fel kan backas med `git revert` utan att röra de andra.

### Steg 4: Kontroll mot psykologen före releasen

1. Kör sökningen igen i psykologen. Det får inte finnas träffar:
   ```
   grep -rnE "spola\.di|spola\.util\.(ToString|Unchecked|UncheckedException|Throwing)" backend/src
   ```
2. `cd framework && java Build.java release`, sedan `scripts/app-check.sh ../psykologen/backend`.
   Det måste gå grönt.

### Steg 5: Release 0.3.0

Borttagna publika API:er bryter bakåt, så det blir en MINOR-höjning:
`SPOLA_APPS=../psykologen/backend scripts/release.sh 0.3.0`, sedan
`git push origin main v0.3.0`. Vänta tills `release.yml` har publicerat releasen.

### Steg 6: Uppgradera psykologen

På en egen gren i psykologen:
1. `cd backend && java Build.java upgrade 0.3.0 && java Build.java`.
2. Ändra "Spola 0.2.0" till "Spola 0.3.0" i `CLAUDE.md` (och i `README.md`/`backend/README.md`
   om versionen står där).
3. Starta appen lokalt och logga in en gång (`java Build.java run`). Session, CSRF och anropen
   mot AI-tjänsterna (`Lazy<RestClient>`) ska fungera som förut.
4. Committa bara `lib/SHA256SUMS` och dokumentationen, aldrig jar-filerna.

## Om något går fel

- **App-kontrollen blir röd före releasen:** ingen tagg skapas. Rätta i Spola, eller backa det
  uppdrag som orsakade felet (`git revert`), och kör kontrollen igen.
- **Något går sönder efter uppgraderingen:** psykologen backar med `git revert` av
  `SHA256SUMS`-commiten. 0.2.0 ligger kvar som release, så `fetch` hämtar de gamla jar-filerna igen.
- **Psykologen visar sig behöva något som togs bort:** skriv ett behov som vanligt. Det prövas
  mot regeln i `MONSTER.md`, med tillägget från 16c.

## Ordning i korthet

1. `scripts/app-check.sh` och raden i `release.sh`, provade grönt mot 0.2.0
2. Uppdragen 16a, 16b och 16c i `uppdrag/`
3. Loopen kör dem
4. Kontroll mot psykologen (sökningen och app-kontrollen)
5. Release 0.3.0
6. Psykologen uppgraderar till 0.3.0
