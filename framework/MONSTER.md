# Mönster för Spola

Det här dokumentet är skrivet för den som använder Spola i ett eget projekt, människa eller AI.
Det säger vilka moduler som finns, hur en Spola-modul ser ut och när något saknat ska byggas i
Spola i stället för i appen. Reglerna i [`REGLER.md`](REGLER.md) gäller alltid och går före allt
här.

## Hur Spola växer

Spola byggs **efter behov**. Ramverket ska inte bli en komplett kopia av Spring i förväg. Det
växer när ett riktigt projekt behöver något som Spring eller Lombok hade gett det:

1. Appen behöver något. Finns det i katalogen nedan använder appen modulen.
2. Saknas det skriver appen ett behov enligt [`uppdrag/BEHOV-MALL.md`](../uppdrag/BEHOV-MALL.md)
   och lägger det i `uppdrag/behov/`. Under tiden bygger appen en minimal lösning lokalt, i
   stilen nedan, så att den inte behöver vänta.
3. Gauntlet-loopen prövar behovet, bygger modulen och den släpps i en ny version.
4. Appen byter sin lokala lösning mot Spola-modulen.

## Hör det hemma i Spola?

**Finns motsvarigheten i Spring eller Lombok, då byggs den i Spola.** Med Spring menas hela
familjen: Spring Framework, Spring Boot, Spring Data, Spring Security, Spring Retry och de andra
projekten som en vanlig Spring-app drar in. Tre villkor till:

- **Det går inom `REGLER.md`.** Bara JDK:n, inga annoteringar, ingen reflektion där det går att
  undvika. Ett protokoll som annars kräver ett bibliotek (PostgreSQL, SMTP) byggs på JDK:ns
  primitiver och testas mot specifikationen.
- **Det täcks inte redan.** Går det att göra med en befintlig modul är svaret ett exempel, inte
  en ny modul. Det som Java 25 redan ger med rimlig ergonomi byggs inte heller, även om Spring
  eller Lombok har det: records ger `toString`, `equals` och `hashCode`, wiring görs med
  konstruktorer i `main`, try-with-resources stänger i omvänd ordning och `ScopedValue` ger
  värden per anrop.
- **Ett annat projekt skulle ha nytta av det.** Affärslogik, ett visst företags API och
  appens egna regler hör hemma i appen, även om de liknar något i Spring.

| Behov | Motsvarighet | Hör hemma i Spola? |
|---|---|---|
| Skicka mejl | Spring Mail (`JavaMailSender`) | Ja, SMTP på JDK:ns sockets och TLS |
| Schemalagda jobb | `@Scheduled`, `TaskScheduler` | Ja |
| JWT, sessioner, CSRF | Spring Security | Ja, säkerhetskritiskt |
| PostgreSQL, transaktioner | Spring Data JDBC, `@Transactional` | Ja, wire-protokollet på JDK:ns sockets |
| Cache | `@Cacheable`, Spring Cache | Ja |
| Försök igen vid fel | Spring Retry | Ja |
| Anropa ett annat HTTP-API | `RestClient` | Ja, ovanpå `java.net.http` med `spola.json` |
| Räkna moms enligt appens regler | – | Nej, affärslogik |
| Klient för ett visst SaaS-API | – | Nej, appspecifikt |
| Ett komplett React-gränssnitt | – | Nej, ingen motsvarighet i Spring |
| DI-container | Spring Core, `ApplicationContext` | Nej, konstruktorer i `main` räcker |
| `toString` som döljer ett fält | Lomboks `@ToString.Exclude` | Nej, skriv över recordets `toString()` |

## Modulkatalog

| Modul | Motsvarar | Ingångar | Kräver |
|---|---|---|---|
| `spola.config` | `application.properties`, `@Value`, `@ConfigurationProperties`, profiler | `Config`, `Source`, `Section` | – |
| `spola.util` | Lombok (`@Slf4j`, `@Getter(lazy)`), Boots loggformat | `Log`, `Logging`, `Lazy` | – |
| `spola.codegen` | Lombok `@Builder`, `@With` för records | `java Build.java codegen`, `<Record>With` | – |
| `spola.json` | Jackson | `Json`, `JsonValue`, `JsonCodec` | – |
| `spola.http` | Inbäddad Tomcat: HTTP/1.1, TLS | `HttpServer`, `Handler`, `Request`, `Response` | – |
| `spola.web` | Spring MVC, `ProblemDetail`, `HttpSession`, `server.forward-headers-strategy` (`RemoteIpValve`: schema och klientadress bakom en omvänd proxy) | `Router`, `Filter`, `Responses`, `Param`, `WebException`, `Sessions`, `Session`, `ForwardedHeaders`, `TrustedProxies` | `http`, `json`, `validation` |
| `spola.validation` | Bean Validation | `Validator<T>`, `Constraint` | – |
| `spola.security` | Spring Security utan HTTP: `PasswordEncoder`, `BCryptPasswordEncoder`, `DelegatingPasswordEncoder` (`{id}`-prefix), `UserDetailsService` | `Pbkdf2PasswordHasher`, `BCryptPasswordHasher`, `PasswordHasher.delegating`, `Authenticator`, `UserStore` | – |
| `spola.security.web` | Spring Security: filterkedja, HTTP Basic, åtkomstregler, sessionsinloggning (`HttpSessionSecurityContextRepository`, `changeSessionId`, `invalidate`), CSRF för en SPA (`CookieCsrfTokenRepository.withHttpOnlyFalse()`, `XSRF-TOKEN`) | `WebSecurity` (`sessions(...)`, `csrf(...)`, `login`/`logout`), `Csrf`, `HttpBasic`, `Rules`, `SecurityContext` | `web`, `security` |
| `spola.client` | Springs `RestClient` (`RestClientResponseException`, `ResourceAccessException`), `@SpringBootTest(webEnvironment = RANDOM_PORT)` med en `RestClient` | `RestClient`, `RestRequest`, `RestClientException` | `json` (och `java.net.http`) |
| `spola.test` | JUnit | `Suite`, `Assertions` | – |
| `spola.guardrails` | – | kontrollerar reglerna i bygget | – |

Varje modul har en `README.md` i `framework/modules/<namn>/` med Spring-koden och Spola-koden
sida vid sida. Läget och kända problem står i [`PROGRESS.md`](PROGRESS.md).

## Så ser ett Spola-API ut

- **Wiring i vanlig Java.** Allt kopplas ihop med metodanrop som går att följa med "Go to
  definition". Ingen scanning, inga namnkonventioner, inga annoteringar.
- **Värden är records.** Inställningar är records med en `DEFAULT` och `withX`-metoder
  (`HttpServerOptions.DEFAULT.withPort(8080)`). Många valfria delar ger en byggare
  (`Router.builder()...build()`).
- **Statiska fabriker med beskrivande namn** i stället för publika konstruktorer
  (`Response.text(200, "Hej")`, `Source.file(...)`).
- **Beteende är funktionella gränssnitt.** `Handler`, `Filter` och `Validator<T>` skrivs som
  lambdas.
- **Sealed hierarkier för utfall och data**, så att `switch` blir uttömmande
  (`Validated.Valid`/`Validated.Invalid`, JSON-trädet).
- **Typade nycklar** i stället för strängar och casts (`Key<T>`, `AttributeKey<T>`).
- **Explicit mappning.** JSON och formulär mappas med codecs och binders som du skriver, inte
  med reflektion.
- **Fel tidigt och samlat.** Det som går att kontrollera vid uppstart kontrolleras där, och alla
  fel rapporteras på en gång med var felet finns. `Optional` i stället för `null`.
- **Livscykel med `AutoCloseable`.** Det som startas stängs med try-with-resources.
- **Virtuella trådar och blockerande I/O.** Ingen reaktiv stil.
- **Gränser som värden.** Allt som tar indata utifrån har storleks- och tidsgränser med säkra
  standardvärden.
- **Fristående moduler.** En modul kräver så få andra moduler som möjligt. Bryggor mellan två
  moduler blir en egen modul (`spola.security.web`).

## Så ser en modul ut

```
framework/modules/<namn>/
  README.md                    Spring-koden och Spola-koden sida vid sida
  src/module-info.java         module spola.<namn> { exports spola.<namn>; }
  src/spola/<namn>/...         publikt API med Javadoc
  test/spola/<namn>/<Namn>Tests.java   public static Suite suite()
```

En ny modul läggs till i `MODULES` i `framework/Build.java`, i katalogen ovan och i
modultabellen i `README.md`.

## Använda Spola i ett projekt

Varje release på GitHub har en jar och en källkods-jar per modul, plus `SHA256SUMS`:

```bash
v=0.1.0; base=https://github.com/paokarlsson/spola/releases/download/v$v
mkdir -p lib && cd lib
for m in spola.http spola.json spola.validation spola.web; do curl -fsSLO "$base/$m-$v.jar"; done
curl -fsSL "$base/SHA256SUMS" | sha256sum -c --ignore-missing
```

Modulerna används på modulvägen (`javac -p lib`, `java -p lib:out -m app/app.Main`). Se
`README.md` för ett komplett exempel och mallen [`docs/app-claude.md`](../docs/app-claude.md)
för `CLAUDE.md` i ditt projekt. Så länge versionen är `0.x` kan API:er ändras mellan
MINOR-versioner.
