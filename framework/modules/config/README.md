# spola.config

Typsäker konfiguration utan annoteringar, scanning eller reflektion. Ersätter Spring Boots
`application.properties`, `@Value`, `@ConfigurationProperties` och profiler
(`@Profile`, `spring.profiles.active`).

- Varje källa är ett värde som skapas i koden (`Source.file`, `Source.optionalFile`,
  `Source.environment`, `Source.environmentTable`, `Source.systemProperties`,
  `Source.arguments`, `Source.map`) och läggs till i en uttalad ordning. **En senare källa
  vinner över en tidigare.**
- Värden binds till records med en funktion du skriver. Alla fel (saknade nycklar, värden som
  inte går att tolka, saknade filer, platshållarcykler) samlas och kastas i **ett**
  `ConfigException` vid uppstart, inte första gången ett värde används.
- Känsliga nycklar maskeras (`******`) i felmeddelanden och i `config.describe()`, som visar
  varje nyckel med värde och vilken källa som vann.
- Den färdiga `Config` är oföränderlig och trådsäker att läsa.

## Exempel bredvid Spring Boot

En tjänst med en databas (url, användare, lösenord, poolstorlek, timeout), en profil `dev`
som byter databas lokalt och en port som kan sättas från kommandoraden.

### Spring Boot

Filnamnen (`application.properties`, `application-dev.properties`) och prioritetsordningen
är inbyggda i ramverket. Records binds med reflektion, och miljövariabler matchas med
"relaxed binding" (`DB_POOLSIZE`, `DB_POOL_SIZE` och `db.pool-size` kan alla träffa samma
fält).

```properties
# application.properties
spring.profiles.active=dev
db.url=jdbc:postgresql://prod/shop
db.user=shop
db.pool-size=10
db.timeout=30s
server.port=8080

# application-dev.properties
db.url=jdbc:postgresql://localhost/shop
```

```java
@ConfigurationProperties(prefix = "db")
record DbConfig(String url, String user, String password, int poolSize, Duration timeout) { }

@SpringBootApplication
@EnableConfigurationProperties(DbConfig.class)
class ShopApplication {
    public static void main(String[] args) { SpringApplication.run(ShopApplication.class, args); }
}

@Component
class Server {
    Server(@Value("${server.port:8080}") int port, DbConfig db) { ... }
}

@Configuration
@Profile("dev")
class DevOnlyBeans { ... }
```

### Spola

Samma sak i vanlig Java-kod. Filnamnen, ordningen, miljövariablernas namn och vilka nycklar
som är känsliga står i koden, och "Go to definition" leder hela vägen.

```java
record DbConfig(String url, String user, String password, int poolSize, Duration timeout) { }
record ServerConfig(int port) { }
record AppConfig(DbConfig db, ServerConfig server) { }

public static void main(String[] args) {
    Config config = Config.builder()
            .add(Source.map("defaults", Map.of("server.port", "8080", "db.timeout", "30s")))
            .add(Source.file(Path.of("config/app.properties")))           // obligatorisk
            .activeProfilesFrom("app.profiles")                            // t.ex. --app.profiles=dev
            .addForEachProfile(p -> Source.optionalFile(Path.of("config/app-" + p + ".properties")))
            .add(Source.environment("APP_"))                               // APP_DB_PASSWORD -> db.password
            .add(Source.arguments(args))                                   // --server.port=9090
            .sensitive("db.password")
            .build();

    AppConfig app = config.bind("", c -> new AppConfig(
            c.section("db", db -> new DbConfig(
                    db.string("url"),
                    db.string("user"),
                    db.string("password"),
                    db.intValue("pool.size", 10),
                    db.duration("timeout"))),
            c.section("server", s -> new ServerConfig(s.intValue("port")))));

    System.out.println(config.describe());   // varje nyckel, värde (maskerat om känsligt) och källa
}
```

Saknas `db.url` och `db.user` och är `db.pool.size` satt till `many` blir det ett enda fel:

```text
the configuration binding has 3 problems:
  - missing key 'db.url' (expected String)
  - missing key 'db.user' (expected String)
  - invalid value for 'db.pool.size' from file /srv/shop/config/app.properties: expected int, got 'many' (For input string: "many")
```

Motsvarigheten till `@Profile("dev")` på bönor är vanlig Java-kod:
`if (config.profiles().contains("dev")) { ... }`.

### Koppla in i spola.di

`spola.config` beror inte på `spola.di`. Applikationen bygger sitt config-record först och
binder det med `toInstance`; komponenterna tar det färdiga recordet i konstruktorn.

```java
AppConfig app = config.bind("", c -> new AppConfig(...));   // bindningen ovan; ConfigException om något saknas

ContainerModule configModule = binder -> {
    binder.bind(DbConfig.class).toInstance(app.db());
    binder.bind(ServerConfig.class).toInstance(app.server());
};
ContainerModule shop = binder -> {
    binder.bind(DataSource.class).to(DbConfig.class, PooledDataSource::new);
    binder.bind(Server.class).to(ServerConfig.class, DataSource.class, Server::new);
};

try (var container = Container.create(configModule, shop)) {
    container.get(Server.class).start();
}
```

## Regler i korthet

### Prioritetsordning
Källorna läses när `build()` körs, i den ordning de lades till; för samma nyckel vinner den
källa som lades till sist. Ordningen i exemplet ovan (standardvärden, fil, profilfiler, miljö,
argument) är en rekommendation, inte inbyggd.

### Miljövariabler
Ingen implicit "relaxed binding". Två explicita former:
- **Prefix:** `Source.environment("APP_")`. Bara variabler som börjar med prefixet (skiftlägeskänsligt)
  läses. Prefixet tas bort, resten görs till gemener och `_` blir `.`:
  `APP_DB_POOL_SIZE` → `db.pool.size`. Variabler utan prefixet läses inte. Två variabler som
  ger samma nyckel (`APP_DB_URL` och `APP_db_url`) är ett fel. Prefixet får inte vara tomt.
- **Tabell:** `Source.environmentTable(Map.of("DATABASE_URL", "db.url"))`. Bara de listade
  variablerna läses, under de angivna nycklarna.

Båda finns också med en extra `Map`-parameter som läses i stället för processens miljö
(för tester).

### Profiler
Aktiva profiler anges i koden (`activeProfiles("dev", "local")`) eller som en kommaseparerad
nyckel (`activeProfilesFrom("app.profiles")`), som då letas upp i de vanliga källorna
(`add`), oavsett var i ordningen de står. Profilkällor kan inte aktivera profiler.
`addForEachProfile(funktion)` lägger till en källa per aktiv profil **där anropet står** i
ordningen; en senare profil vinner över en tidigare. Inga filnamn är inbyggda.

### Platshållare
`${nyckel}` och `${nyckel:standard}` ersätts när alla källor slagits ihop, så en platshållare
i en fil kan använda ett värde från ett argument. Standardvärdet kan själv innehålla
platshållare. En cykel (`a=${b}`, `b=${a}`) är ett fel med hela kedjan: `a -> b -> a`.
**Bokstavligt `${`:** skriv `$${`. `price=$${amount}` ger värdet `${amount}`.

### Typer
`string`, `intValue`, `longValue`, `booleanValue` (bara `true`/`false`), `doubleValue`,
`duration` (`30s`, `500ms`, `5m`, `2h`, `1d`, `10us`, `7ns` eller ISO-8601 `PT30S`; ett tal
utan enhet är fel), `uri`, `path`, `enumValue(nyckel, Typ.class)` (exakt namn via
`Enum.valueOf`), `list` (kommaseparerat, varje element trimmas, tomt värde ger tom lista, tomt
element är fel), `optional` och egna omvandlare: `c.value("color", "Color", Color::parse)`
eller `new Converter<>("Color", Color::parse)`, som också går att använda med `list` och
`optional`. Nästlade records binds med `c.section("pool", p -> ...)`.

### Nycklar som inte är kända i förväg
`section.keys()` ger nycklarna under en sektion, relativt sektionen och sorterade. Det gör att
en map kan bindas inne i bindningsfunktionen, så dess värden kontrolleras i samma rapport som
resten (exemplet är från `modules/demo`):

```java
// inventory.stock.apple=10, inventory.stock.pear=5
Map<String, Integer> stock = config.bind("inventory.stock", s -> {
    var levels = new TreeMap<String, Integer>();
    for (String sku : s.keys()) {          // "apple", "pear"
        levels.put(sku, s.intValue(sku));
    }
    return levels;
});
```

En nyckel som är lika med prefixet (`inventory.stock=x`) tas inte med.

### Hemligheter
`sensitive("db.password")` eller `sensitive(key -> key.endsWith(".password"))`. Värdet
maskeras i felmeddelanden (även omvandlarens eget felmeddelande utelämnas) och i
`describe()`. Ett värde som byggs av ett känsligt värde via en platshållare blir också
känsligt. `config.get(...)` och bindningen ger förstås det riktiga värdet.
