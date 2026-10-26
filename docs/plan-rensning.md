# Plan: rensa bort det överflödiga i Spola

Bygger på `docs/overflodigt-i-spola.md`. Skriven 2026-10-26.

Spola är `0.x` och den enda appen som använder det är psykologen. Går psykologen sönder vid en
uppgradering anpassas den efteråt. Det finns inget skyddsnät före releasen. När seriösare appar
börjar bero på Spola bör vi vara försiktigare (se sist).

## Vad som tas bort, och vad som får vara kvar

| Del | Beslut | Varför |
|---|---|---|
| `spola.di` (hela modulen) | **Tas bort** | Ingen ramverksmodul och ingen app använder den, bara `demo`. Wiring för hand i `main` ger samma sak. |
| `ToString` i `spola.util` | **Tas bort** | Records har `toString`. Enda användningen är `demo/AuditSettings.java`. |
| `Unchecked`, `UncheckedException`, `ThrowingRunnable/Supplier/Function/Consumer` | **Tas bort** | Används inte utanför `util`. JDK:n har `UncheckedIOException`, och en vanlig `try`/`catch` räcker. |
| `Lazy` | Får vara kvar | Psykologen använder den. `StableValue` är bara preview i Java 25. Ses över när JEP 502 blir final. |
| `spola.codegen` | Får vara kvar | Motiverad tills derived record creation (JEP 468) finns. |
| `Log`, `spola.http` | Får vara kvar | Diskutabla, men inget som tar skada av att de finns. |

Allt annat (`json`, `web`, `security`, `security.web`, `validation`, `config`, `client`, `test`,
`guardrails`) rörs inte.

## Vad det betyder för psykologen

Psykologen (Spola 0.2.0) hämtar `spola.http`, `json`, `validation`, `web`, `config`, `util`,
`security`, `security.web`, `client` och `test`. Ur `spola.util` använder den bara `Lazy`,
`Logging` och `LoggingSettings`. Inget av det som tas bort används, så uppgraderingen väntas gå
igenom utan ändringar. Gör den inte det rättas psykologen i samband med uppgraderingen.

## Steg

### 1. Uppdrag till loopen

Två handskrivna uppdrag enligt `uppdrag/MALL.md`, med nummer 16 och uppåt.

**`16a-ta-bort-di.md`**
- Skriv om `demo` så att `InventoryApp` kopplar ihop allt för hand i `main`, med
  try-with-resources för det som ska stängas och `ScopedValue` direkt där scopet `CALL` användes.
- Ta bort `framework/modules/di/` och dess rad i `MODULES` i `Build.java`.
- Uppdatera `MONSTER.md` (modulkatalogen och ett stycke om att wiring görs för hand i `main`),
  `README.md`, `docs/app-claude.md`, `ARCHITECTURE.md`, `PROGRESS.md`, `config/README.md` och
  `demo/README.md`. Ändra raden för 03 i `uppdrag/PLAN.md` till "borttagen i 16a".
- Acceptans: `java Build.java` går grönt, `spola.di` nämns inte längre i `framework/`, `README.md`
  eller `docs/app-claude.md`, och `java Build.java release` skriver inga `spola.di`-filer.

**`16b-util-ta-bort-tostring-och-unchecked.md`**
- Ta bort `ToString`, `Unchecked`, `UncheckedException` och de fyra `Throwing*` med tester.
- `demo/AuditSettings` får en egen `toString()` som döljer hemligheten.
- Lägg till i `MONSTER.md` under "Hör det hemma i Spola?": det som Java 25 redan ger med rimlig
  ergonomi (records, try-with-resources, `ScopedValue`, wiring i `main`) byggs inte, även om
  Spring eller Lombok har det.
- Uppdatera `util/README.md`, Lombok-kolumnen för `spola.util` i `MONSTER.md`,
  `ARCHITECTURE.md`, `PROGRESS.md` och `docs/overflodigt-i-spola.md` (vad som togs bort).
- Acceptans: `java Build.java` går grönt, och de borttagna typerna nämns inte längre i `framework/`.

Med nummer 16 tar loopen de här före behovet om omförsök som väntar i `uppdrag/behov/`. Ska
omförsöken komma först får uppdragen nummer efter det uppdrag planeraren gör av behovet.

### 2. Kör loopen

`scripts/gauntlet.sh --iterations 2`, och följ läget med `scripts/gauntlet.sh status`.

### 3. Release 0.3.0

Borttagna publika API:er bryter bakåt, så versionen höjs med MINOR:
`scripts/release.sh 0.3.0 --push`.

### 4. Uppgradera psykologen

1. `cd backend && java Build.java upgrade 0.3.0 && java Build.java`.
2. Om något inte kompilerar eller ett test blir rött: rätta det i psykologen.
3. Ändra "Spola 0.2.0" till "Spola 0.3.0" i `CLAUDE.md`.
4. Committa `lib/SHA256SUMS`, eventuella rättningar och dokumentationen.

## Senare, när fler appar beror på Spola

När det finns appar där en trasig uppgradering kostar något:
- Bygg och testa apparna mot de lokala release-jarfilerna innan en release taggas, till exempel
  med ett skript `scripts/app-check.sh` som `release.sh` kör.
- Fasa ut i två steg: markera först med `@Deprecated` (en JDK-annotering, alltså tillåten) i en
  release och ta bort i nästa.
- Skriv i release-anteckningarna vad som tagits bort och vad man använder i stället.
