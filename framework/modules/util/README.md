# spola.util

Små hjälpare som ersätter de delar av Lombok som inte gäller builders, plus konsolloggning
som ersätter Spring Boots standardformat och `logging.level.*`. Inga annoteringar, ingen
kodgenerering, ingen reflektion. Modulen har inga `requires` på andra Spola-moduler (bara
`java.logging` från JDK:n).

| Lombok / Spring Boot | Spola |
|---|---|
| `@Slf4j`, `@Log` | `Log.forClass(Order.class)` i ett `static final`-fält |
| Spring Boots konsolformat, `logging.level.*` | `Logging.configure(LoggingSettings)` |
| `@Getter(lazy = true)` | `Lazy.of(() -> ...)` |
| `@SneakyThrows` | inget: `try`/`catch` och `UncheckedIOException` från JDK:n |
| `@ToString.Exclude` | inget: skriv över recordets `toString()` |

Builders och with-metoder för records (Lomboks `@Builder`, `@Builder(toBuilder = true)` och
`@With`) finns i modulen `spola.codegen` som genererad, incheckad källkod; se
[`modules/codegen/README.md`](../codegen/README.md).

## Loggning: `@Slf4j` och `Log`

### Lombok

Lombok genererar fältet åt dig när klassen kompileras. Fältet syns inte i källkoden, och
loggern hämtas från SLF4J, ett tredjepartsbibliotek.

```java
@Slf4j
class OrderService {
    void place(Order order) {
        log.info("order {} placed by {}", order.id(), order.customer());
    }
}
```

### Spola

Fältet skrivs ut. Loggernamnet är klassens fullständiga namn och loggern hämtas med
`System.getLogger`, alltså JDK:ns egen loggning. Klassen anges explicit; Spola letar inte
efter den i anropsstacken.

```java
class OrderService {
    private static final Log LOG = Log.forClass(OrderService.class);

    void place(Order order) {
        LOG.info("order {} placed by {}", order.id(), order.customer());
    }

    void charge(Order order) {
        try {
            payments.charge(order);
        } catch (PaymentException e) {
            LOG.error("payment failed for order {}", order.id(), e); // e loggas med stackspår
        }
    }

    void dump() {
        LOG.debug(() -> "state: " + expensiveDump());           // leverantören körs bara om DEBUG är på
        LOG.debug("cache {}", Log.defer(() -> cache.describe())); // ett lat argument
    }
}
```

Platshållarna fungerar som i SLF4J: `{}` ersätts av argumenten i tur och ordning, `\{}` ger
ett bokstavligt `{}`, ett sista `Throwable` som ingen platshållare tagit loggas som undantag.
Är nivån avstängd formateras ingenting och inga `toString()` eller leverantörer anropas.
Exakta regler och nivåmappningen (`warn` är `System.Logger.Level.WARNING`) står i Javadoc för
`Log`. I tester kan en egen logger injiceras med `Log.of(System.Logger)`.

## Konsolformat och nivåer: Spring Boot och `Logging`

### Spring Boot

Spring Boot installerar sin loggning när applikationen startar och läser nivåerna ur
`application.properties`:

```properties
logging.level.root=INFO
logging.level.com.example=DEBUG
logging.level.com.example.noisy=WARN
```

### Spola

Ingenting installeras av sig självt. Applikationen anropar `Logging.configure` i `main`, och
nivåerna är en vanlig `Map` från loggernamnsprefix till nivå. Längsta prefix vinner och ett
prefix matchar bara hela namnsegment (`com.ex` täcker inte `com.example`). Ett nytt anrop
ersätter det föregående helt.

```java
Logging.configure(LoggingSettings.defaults()
        .withLevel("", System.Logger.Level.INFO)        // roten
        .withLevel("com.example", System.Logger.Level.DEBUG)
        .withLevel("com.example.noisy", System.Logger.Level.WARNING));
```

Raderna ser ut så här (stackspår på följande rader):

```text
2026-10-02T07:51:03.042+02:00 INFO  [main] com.example.OrderService : order 42 placed
2026-10-02T07:51:03.050+02:00 ERROR [virtual-31] com.example.Payment : payment failed
	java.io.IOException: connection reset
		at com.example.Payment.charge(Payment.java:17)
```

Varje stackspårsrad börjar med en tab, och radbrytningar i meddelanden escapas, så en rad som
börjar i kolumn 0 är alltid en ny post: ett loggat värde (inte heller ett undantagsmeddelande)
kan inte förfalska en post.

### Nivåerna ur `spola.config`

`spola.util` läser inte konfiguration; det är avsiktligt, så att modulen kan användas utan
`spola.config`. Vill du ha `logging.level.*` som i Spring Boot binder du nycklarna med
`spola.config` och skickar in dem. `LoggingSettings.parseLevel` läser nivånamnen som Spring
Boot skriver dem (`TRACE`, `DEBUG`, `INFO`, `WARN` eller `WARNING`, `ERROR`, `OFF`, `ALL`,
skiftlägesokänsligt) och kastar `IllegalArgumentException` för allt annat, så den fungerar
direkt som omvandlare. `Section.keys()` ger nycklarna under `logging.level`, så ett felstavat
nivånamn hamnar i samma felrapport som resten av konfigurationen:

```java
Config config = Config.builder()
        .add(Source.optionalFile(Path.of("application.properties")))
        .add(Source.arguments(args))
        .build();

Map<String, System.Logger.Level> levels = config.bind("logging.level", section -> {
    var result = new TreeMap<String, System.Logger.Level>();
    for (String name : section.keys()) {                       // "root", "com.example", ...
        var level = section.value(name, "log level", LoggingSettings::parseLevel);
        result.put(name.equals("root") ? "" : name, level);
    }
    return result;
});
Logging.configure(LoggingSettings.defaults().withLevels(levels));
```

Med `logging.level.root=LOUD` blir felet från `bind`:

```text
the configuration binding of 'logging.level' has 1 problem:
  - invalid value for 'logging.level.root' from arguments: expected log level, got 'LOUD' (not a log level (expected TRACE, DEBUG, INFO, WARN, ERROR, OFF or ALL))
```

Saknas `logging.level.root` blir roten `INFO`. Utskriften går till `System.err` om inget
annat anges; `withOutput(stream)` skriver till en annan ström (t.ex. i tester). Ett komplett
program som gör så här finns i `modules/demo` (`InventoryWiring.logLevels`).

## Lat värde: `@Getter(lazy = true)` och `Lazy`

### Lombok

```java
class Catalog {
    @Getter(lazy = true)
    private final Index index = buildIndex();
}
```

### Spola

```java
class Catalog {
    private final Lazy<Index> index = Lazy.of(this::buildIndex);

    Index index() {
        return index.get();
    }
}
```

Värdet räknas ut högst en gång, även när många virtuella trådar anropar `get()` samtidigt.
Skillnader mot Lombok: ett undantag från leverantören sparas inte (nästa `get()` försöker
igen), `null` är ett fel (`NullPointerException`; använd `Optional`), och leverantören släpps
när värdet är uträknat.

## Checked exceptions: `@SneakyThrows`

Spola har ingen motsvarighet. Fånga undantaget där det uppstår och slå in det med JDK:ns egna
typer, till exempel `UncheckedIOException`, i en liten hjälpmetod:

```java
static String read(Path path) {
    try {
        return Files.readString(path);
    } catch (IOException e) {
        throw new UncheckedIOException(e);
    }
}

List<String> texts = paths.stream().map(Example::read).toList();
```

## Hemliga fält: `@ToString.Exclude`

Spola har ingen motsvarighet. En record skriver alla komponenter i `toString()`. Har den en
hemlighet skriver du över `toString()` med en rad:

```java
record User(String name, String password) {
    @Override
    public String toString() {
        return "User[name=" + name + ", password=***]";
    }
}
```

## Vad records redan ersätter

För oföränderliga typer behövs ingen hjälpare alls: en record har konstruktor, accessorer,
`equals`, `hashCode` och `toString`. Det täcker Lomboks `@Value`, `@Data` (för oföränderliga
typer), `@Getter`, `@EqualsAndHashCode`, `@ToString`, `@AllArgsConstructor` och
`@RequiredArgsConstructor`. `@NonNull` ersätts av `Objects.requireNonNull` i recordens
kompakta konstruktor, och `@Cleanup` av try-with-resources.

```java
record Order(String id, Customer customer, List<Line> lines) {
    Order {
        Objects.requireNonNull(id, "id");
        lines = List.copyOf(lines);
    }
}
```

Det records inte ger är en läsbar builder och kopior med en ändrad komponent; de genereras av
`spola.codegen` (se [`modules/codegen/README.md`](../codegen/README.md)).
