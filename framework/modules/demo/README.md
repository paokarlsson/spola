# spola.demo

En lagertjänst för en liten webbutik, skriven som en applikationsutvecklare skulle skriva den
med `spola.config` och `spola.util`, och med komponenterna ihopkopplade för hand i vanlig Java. Programmet läser en orderfil (`sku;antal`, en
order per rad), drar av varje order från lagersaldot och skriver en sammanfattning: behandlade
ordrar, avvisade ordrar med orsak och slutsaldot per artikel. Varje beslut skrivs också till
ett revisionsspår som signeras med en hemlig nyckel.

Modulen finns för att pröva kärnmodulerna tillsammans. Inget beror på den, och den exporterar
inget paket. Friktionen vi hittade står sist, under [Friktion](#friktion).

## Köra programmet

Bygg först (`java Build.java` i `framework/`). Skapa sedan en katalog med en
konfigurationsfil och en orderfil:

```properties
# inventory.properties
inventory.orders=orders.txt
inventory.stock.apple=10
inventory.stock.pear=5
rules.max.quantity=8
rules.blocked=plum
```

```text
# orders.txt (UTF-8; tomma rader och rader som börjar med # hoppas över)
apple;3
pear;9
kiwi;1
```

Kör från den katalogen:

```sh
INVENTORY_AUDIT_SECRET=change-me-to-something-long \
  java -p <framework>/out/classes -m spola.demo/spola.demo.InventoryApp
```

Sammanfattningen går till `System.out`, loggen och felmeddelanden till `System.err`:

```text
2026-10-02T09:46:50.421+02:00 INFO  [main] spola.demo.AuditTrail : audit trail orders.txt.audit opened
2026-10-02T09:46:50.423+02:00 INFO  [main] spola.demo.OrderProcessor : processing orders from orders.txt
2026-10-02T09:46:50.431+02:00 INFO  [main] spola.demo.OrderProcessor : processed 1 orders, rejected 2
processed 1 orders, rejected 2
processed:
  line 1: apple x 3
rejected:
  line 2: pear x 9: exceeds max quantity 8
  line 3: kiwi x 1: unknown sku
final stock:
  apple: 7
  pear: 5
2026-10-02T09:46:50.436+02:00 INFO  [main] spola.demo.AuditTrail : audit trail orders.txt.audit closed after 3 entries
```

Orderfilen och `inventory.properties` läses som UTF-8. Ett byte order mark först i filen
(som Excel och Notepad skriver) hoppas över. En orderfil som inte är giltig UTF-8 (t.ex.
sparad som Windows-1252) är en trasig fil (exitkod 65), och meddelandet nämner första raden
med fel. En artikel får bara innehålla synliga tecken: en rad vars artikel har ett
styrtecken, formattecken eller avgränsartecken (t.ex. ESC, U+0085, U+2028, U+202E) är en
trasig rad (exitkod 65), eftersom artikeln skrivs som den är till sammanfattningen och
revisionsspåret.

Samma program går att köra utan `main`, vilket testerna gör:
`InventoryApp.run(args, miljö, ut, logg)` tar argumenten som en lista, miljön som en `Map` och
två `PrintStream`, och returnerar exitkoden.

### Konfiguration

Källorna, från lägst till högst prioritet (ordningen som README för `spola.config`
rekommenderar):

1. standardvärden i koden (`InventoryWiring.DEFAULTS`)
2. filen `config.file` (standard `inventory.properties` i arbetskatalogen)
3. en fil per aktiv profil bredvid den, `inventory-<profil>.properties` (profiler från
   nyckeln `profiles`, t.ex. `--profiles=dev`; filen får saknas)
4. miljövariabler med prefixet `INVENTORY_` (`INVENTORY_RULES_MAX_QUANTITY` blir
   `rules.max.quantity`)
5. argument `--nyckel=värde`

| Nyckel | Typ | Standard | Betydelse |
|---|---|---|---|
| `config.file` | sökväg | `inventory.properties` | konfigurationsfilen (läses från standardvärden, miljö och argument) |
| `profiles` | lista | ingen | aktiva profiler |
| `inventory.orders` | sökväg | krävs | orderfilen |
| `inventory.stock.<sku>` | heltal ≥ 0 | inga | startsaldo per artikel |
| `rules.max.quantity` | heltal ≥ 1 | `100` | största antal per order |
| `rules.blocked` | lista | tom | artiklar som inte får säljas |
| `audit.file` | sökväg | `<orderfil>.audit` | revisionsspåret |
| `audit.secret` | text, minst 16 tecken | krävs | nyckeln som signerar revisionsspåret; **känslig**, visas aldrig |
| `logging.level.<prefix>` | nivå | `root=INFO` | loggnivå per loggernamn, som i Spring Boot |

Nycklar som slutar på `.secret` eller `.password` maskeras också. I konfigurationsdumpen
(`describe()`) och i konverteringsfel maskeras även värden som byggs av ett känsligt värde med
en platshållare; binds ett sådant värde som t.ex. en sökväg loggas det däremot som sökväg i
klartext, så lägg inte hemligheter i andra nycklar. `audit.file` får inte vara orderfilen
(exitkod 78). Med `--logging.level.spola.demo=DEBUG` loggas hela den
sammanslagna konfigurationen (maskerad) och varje lagerförändring.

### Exitkoder och fel

| Kod | När |
|---|---|
| 0 | orderfilen är behandlad (avvisade ordrar räknas inte som fel) |
| 65 | en trasig rad i orderfilen; filen och radnumret står i meddelandet |
| 70 | ett oväntat fel |
| 74 | orderfilen kunde inte läsas, eller revisionsspåret kunde inte öppnas eller stängas (t.ex. en katalog som saknas i `audit.file`) |
| 78 | saknad eller ogiltig konfiguration (också `audit.file` som pekar på orderfilen); varje felande nyckel står i meddelandet |

Ett fel skrivs som ett meddelande utan stackspår:

```text
error: invalid configuration: the configuration binding has 2 problems:
  - missing key 'inventory.orders' (expected Path)
  - invalid value for 'rules.max.quantity' from arguments: expected int of at least 1, got 'many' (For input string: "many")
```

```text
error: broken order file: orders.txt line 3: expected 'sku;quantity' with a whole quantity of at least 1, got 'plum;two'
```

## Samma uppstart i Spring Boot

I Spring Boot hittas komponenterna med classpath-scanning, konfigurationen binds med
reflektion och felen blir undantag med stackspår om man inte skriver en egen
`FailureAnalyzer` eller `ExitCodeExceptionMapper`:

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryApplication implements CommandLineRunner {
    private final OrderProcessor processor;
    private final ReportWriter report;

    InventoryApplication(OrderProcessor processor, ReportWriter report) { ... }

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(InventoryApplication.class, args)));
    }

    @Override
    public void run(String... args) {
        report.write(processor.process());
    }
}

@ConfigurationProperties("rules")
record RuleSettings(int maxQuantity, Set<String> blocked) { }

@Component @Qualifier("quantity")
class QuantityLimit implements OrderCheck { ... }

@Component
class AuditTrail implements AutoCloseable { ... }   // stängs av kontexten (inferred destroy method)
```

I Spola står samma sak i vanlig kod, utan container. Ingången (`InventoryApp`) bygger
konfigurationen, binder den till records och konfigurerar loggningen; `InventoryWiring` säger
vilka källor som finns och skapar komponenterna med deras konstruktorer:

```java
Config config = InventoryWiring.config(args, environment);    // källorna i prioritetsordning
AppSettings settings = InventoryWiring.settings(config);      // ett ConfigException med alla fel
Logging.configure(LoggingSettings.defaults().withLevels(settings.logLevels()).withOutput(log));
InventoryWiring.process(settings, out);

// i InventoryWiring:
static void process(AppSettings app, PrintStream summary) throws IOException {
    var stock = new Stock(app.inventory());
    try (var audit = new AuditTrail(app.inventory(), app.audit())) {
        var processor = new OrderProcessor(app.inventory(), stock,
                new QuantityLimit(app.rules()), new BlockedSkus(app.rules()), audit);
        new ReportWriter(summary).write(processor.process());
    }                                                         // AuditTrail stängs här, även vid fel
}
```

Komponenterna (`Stock`, `QuantityLimit`, `BlockedSkus`, `AuditTrail`, `OrderProcessor`,
`ReportWriter`) vet inget om konfigurationen; de får records och varandra i konstruktorn. De
två `OrderCheck`-implementationerna behöver ingen kvalificerare: de skickas som två argument.
Glöms ett beroende bort kompilerar koden inte.

## Friktion

Det här hittade vi när modulerna användes tillsammans. **Åtgärdat** betyder en liten
tilläggsändring i kärnmodulen, med test och dokumentation. **Kvar** betyder att det är för
stort för det här uppdraget; punkten står då också under "Återstår" i `framework/PROGRESS.md`.

### 1. Loggnivåer ur konfigurationen krävde en kopierad `switch` (**Åtgärdat**)

README för `spola.util` visade hur `logging.level.*` läses med `spola.config`, men nivånamnen
måste översättas med en egen `switch` som varje applikation fick kopiera:

```java
static System.Logger.Level level(String text) {
    return switch (text.strip().toUpperCase(Locale.ROOT)) {
        case "TRACE" -> System.Logger.Level.TRACE;
        case "DEBUG" -> System.Logger.Level.DEBUG;
        // ... fem fall till
        default -> throw new IllegalArgumentException("unknown log level: " + text);
    };
}
```

**Åtgärd:** `LoggingSettings.parseLevel(String)` i `spola.util` (tillägg). Den kastar
`IllegalArgumentException` för okända namn och fungerar därför direkt som omvandlare:

```java
var level = logging.value(name, "log level", LoggingSettings::parseLevel);
```

Test: `LoggingTests` "parseLevel reads level names as written in configuration". Dokumenterat i
Javadoc, i README för `spola.util` och i `ARCHITECTURE.md`.

### 2. En map med okända nycklar gick inte att binda i samma rapport (**Åtgärdat**)

Startsaldot (`inventory.stock.<sku>`) och loggnivåerna (`logging.level.<prefix>`) är maps
vars nycklar inte är kända i förväg. `Section` hade ingen metod som listar nycklar, så de
måste läsas med `config.keys()` och `config.get(...)` utanför bindningen. Då kontrolleras
värdena inte av `bind`, och ett ogiltigt saldo eller nivånamn hamnar inte i samma
felrapport som resten:

```java
for (String key : config.keys()) {                 // utanför bind: egen felhantering
    if (key.startsWith("logging.level.")) { ... config.get(key).orElseThrow() ... }
}
```

**Åtgärd:** `Section.keys()` i `spola.config` (tillägg) ger nycklarna under sektionen,
relativt sektionen. Nu är allt en bindning med en rapport:

```java
private static Map<String, Integer> stock(Section stock) {
    var levels = new TreeMap<String, Integer>();
    for (var sku : stock.keys()) {
        levels.put(sku, stock.value(sku, STOCK_LEVEL));
    }
    return levels;
}
```

Test: `BindingTests` "keys lists the relative keys below a section ...", "keys binds a map
whose values are checked in the same report" och "keys cannot be used after the binding has
finished". Dokumenterat i Javadoc, i README för `spola.config` och i `ARCHITECTURE.md`.

### 3. Ett kvalificerat beroende gör att alla måste skrivas som `Key` (**Löst**)

Gällde DI-containern `spola.di`, där ett kvalificerat beroende tvingade alla beroenden att
skrivas som `Key.of(...)`. Containern togs bort i 0.3.0, och demon kopplar ihop komponenterna
med konstruktorer, så friktionen finns inte längre.

### 4. Konfigurationsfilens plats kräver två omgångar (**Kvar**)

Var konfigurationsfilen ligger är i sig konfiguration (`--config.file=...`, som Spring Boots
`spring.config.location`). `Source.file` tar en färdig `Path`, så filen måste hittas i en
första omgång innan hela konfigurationen byggs, med samma källor och samma känsliga nycklar
upprepade:

```java
var bootstrap = Config.builder()
        .add(Source.map("defaults", DEFAULTS))
        .add(Source.environment(ENVIRONMENT_PREFIX, environment))
        .add(Source.arguments(args))
        .sensitive(InventoryWiring::isSensitive)
        .build();
Path file = bootstrap.bind("", c -> c.path(CONFIG_FILE_KEY));
return Config.builder()
        .add(Source.map("defaults", DEFAULTS))
        .add(Source.file(file))
        ...                                        // samma källor och sensitive en gång till
```

En lösning (t.ex. en källa vars sökväg läses från en nyckel, på samma sätt som
`activeProfilesFrom`) är en designfråga för `spola.config` och lämnas till senare.

### 5. Uppstarten och felöversättningen skrivs för hand (**Kvar**)

Ingången är ungefär 50 rader: bygg konfigurationen, bind den, konfigurera loggningen, skapa
komponenterna, kör, och översätt varje feltyp till ett meddelande och en exitkod. Felen har
ingen gemensam supertyp, så varje typ fångas för sig:

```java
} catch (OrderFileException e) {
    return fail(log, DATA_ERROR, "broken order file: " + e.getMessage());
} catch (UncheckedIOException e) {
    return fail(log, IO_ERROR, e.getMessage() + ": " + e.getCause());
} catch (IOException e) {
    return fail(log, IO_ERROR, "cannot close the audit trail: " + e);
}
```

Allt detta är vad en starter ska ta hand om (uppdrag 19
i `PLAN.md`); här skrivs det för hand, enligt uppdraget.

### 6. Loggningen är global för JVM:en (**Kvar**)

`Logging.configure` ändrar JUL-rotloggern, som är gemensam för hela JVM:en. `run` tar en
egen logg-ström, så den måste konfigurera loggningen i början och återställa den i `finally`:

```java
Logging.configure(LoggingSettings.defaults().withLevels(settings.logLevels()).withOutput(log));
try {
    ...
} finally {
    Logging.reset();
}
```

Två samtidiga `run` i samma JVM (t.ex. parallella tester) skulle skriva i varandras strömmar
och dela nivåer. Testerna i `spola.demo` körs i tur och ordning, så det märks inte i dag.
Loggning per anrop (t.ex. med `ScopedValue`) vore en omdesign av `spola.util`.

### 7. Ett ogiltigt logger-prefix hamnar utanför konfigurationsrapporten (**Kvar**)

`LoggingSettings` kontrollerar prefixen (`a..b` är fel) när recorden skapas, alltså efter
bindningen. `logging.level.spola..demo=DEBUG` ger därför inte ett fel i `ConfigException`
utan ett `IllegalArgumentException` som ingången fångar för sig:

```java
} catch (IllegalArgumentException e) { // a logger-name prefix like logging.level.a..b
    return fail(log, CONFIG_ERROR, "invalid configuration: logging.level: " + e.getMessage());
}
```

En publik kontroll av ett prefix (så att bindningen kan rapportera det) vore ett litet tillägg
i `spola.util`, men felet är ovanligt och fångas redan begripligt, så vi lät det vara.

### 8. Ett byte order mark i konfigurationsfilen dolde första inställningen (**Åtgärdat**)

`Source.file` läste filen som UTF-8 men lät ett byte order mark (U+FEFF, som Windows
PowerShell 5.1 med `-Encoding UTF8` och äldre Notepad skriver) stå kvar. Det blev en del av
första nyckeln, och inställningen ignorerades tyst:

```properties
\uFEFFrules.max.quantity=5      # nyckeln blev "\uFEFFrules.max.quantity"
```

`apple;50` gick då igenom trots gränsen 5, med exitkod 0. Applikationen kunde inte rätta det
själv utan att läsa filen på egen hand före `spola.config`.

**Åtgärd:** `Source.file` och `Source.optionalFile` i `spola.config` hoppar över ett byte
order mark först i filen (bara där; inget API ändras). Test: `SourceTests` "a byte order mark
at the start of a file is skipped, not part of the first key" och "only a leading byte order
mark is skipped; ...", och i `spola.demo` `ProgramTests` "a byte order mark at the start of
inventory.properties does not hide the first setting". Dokumenterat i Javadoc, i README för
`spola.config` (avsnittet "Filer") och i `ARCHITECTURE.md`.

### Det som fungerade bra

- **Config-records rakt in i konstruktorerna,** precis som README för `spola.config` visar.
  Komponenterna tar records och vet inget om varifrån värdena kommer.
- **En rapport för alla konfigurationsfel.** Ett saknat `inventory.orders` och ett ogiltigt
  `rules.max.quantity` kommer i samma meddelande, och en ersättnings-`null` som får en
  record-konstruktor att kasta döljs bakom rapporten i stället för att ge ett
  `NullPointerException`.
- **Hemligheter.** `sensitive(...)` maskerar `audit.secret` i `describe()` och i fel, också
  när värdet kopieras med en platshållare (`--rules.max.quantity=${audit.secret}` ger ett fel
  utan hemligheten). Det gäller dumpen och konverteringsfelen; ett kopierat värde som binds
  utan fel (t.ex. som sökväg) är vanlig data efter det. `AuditSettings` skriver över sin `toString()` så att hemligheten inte syns.
- **Stängning vid fel.** try-with-resources stänger `AuditTrail` både när
  programmet lyckas och när en trasig rad avbryter det, utan någon extra kod.
- **Loggnivåer per paket** fungerar som i Spring Boot, från fil, profil, miljö eller argument.
