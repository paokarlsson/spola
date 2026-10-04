# Spola – säljmaterial

*Ola Karlsson · läget 2026-10-03*

## Pitch

**Spola är ett Java 25-ramverk som gör det Spring Boot och Lombok gör, men utan magi och utan ett enda beroende utanför JDK:n.** Allt som kopplar ihop din applikation står i vanlig Java-kod som du kan följa med "Go to definition".

Tre budskap bär allt annat:

1. **Inget dolt.** Inga annoteringar som gör saker i bakgrunden, ingen classpath-scanning, ingen reflektion i uppstarten. Det du läser är det som körs.
2. **Fel tidigt, inte i produktion.** Saknade beroenden, cykler och felaktig konfiguration upptäcks av kompilatorn eller vid start, alla på en gång och med rad angiven.
3. **Noll tredjepartsberoenden.** Ingen Maven-katalog att granska, inga CVE:er i transitiva bibliotek, inga versionskonflikter. Bara JDK 25.

## Problemet

Spring Boot och Lombok sparar tid de första veckorna och tar tillbaka den resten av systemets livstid. Det märks på fyra ställen:

- **Magi som måste läras ut.** En klass blir en böna för att den har rätt annotering och ligger i rätt paket. Nya utvecklare lär sig konventioner i stället för kod, och "Go to definition" leder till ramverket, inte till svaret.
- **Fel som syns sent.** Ett saknat beroende eller en cykel märks först när kontexten startar, och felet pekar på en klass, inte på var kopplingen gjordes.
- **En lång beroendekedja.** Varje startprojekt drar in hundratals transitiva bibliotek. Varje sårbarhet i något av dem blir ditt problem, och varje uppgradering kan ge versionskonflikter.
- **Kod som inte finns.** Lombok genererar metoder som inte syns i källkoden och kräver stöd i både kompilator och IDE.

## Lösningen

Spola behåller det Spring gör bra, alltså en helhet från konfiguration till HTTP och säkerhet, men byter magin mot explicit Java 25. Fem principer styr varje modul och kontrolleras automatiskt i bygget:

| Princip | I praktiken |
| --- | --- |
| Bara JDK 25 | Inga tredjepartsbibliotek, ingen Maven eller Gradle. Bygget körs med `java Build.java`. |
| Explicit framför magiskt | All wiring är vanlig kod. Ingen scanning, inga namnkonventioner. |
| Sex tillåtna annoteringar, resten förbjudna | Bara JDK:ns egna, som `@Override`. Inga egna annoteringstyper. |
| Kompileringsfel framför runtime-fel | Records, sealed interfaces och lambdas i stället för reflektion. |
| Varje modul fristående | Ta med DI utan HTTP, eller JSON utan resten. |

Reglerna är inte bara ord: en guardrails-modul i bygget stoppar en otillåten annotering, en främmande import eller en inlagd jar-fil med fil och rad.

## Så ser det ut

Samma orderservice, med ett repository och en av två betalningstjänster. I Spring hittas klasserna genom scanning och kopplas med annoteringar:

```java
@Component
class OrderService {
    @Autowired
    OrderService(OrderRepo repo, @Qualifier("eu") PaymentGateway gateway) { ... }
}
var context = new AnnotationConfigApplicationContext("shop");
```

I Spola står kopplingen i koden. Klasserna har inga annoteringar och vet inget om containern:

```java
record OrderService(OrderRepo repo, PaymentGateway gateway) { }

ContainerModule orders = binder -> {
    binder.bind(Key.of(PaymentGateway.class, Region.EU)).to(EuGateway::new);
    binder.bind(OrderRepo.class).to(DataSource.class, JdbcOrderRepo::new);
    binder.bind(OrderService.class).to(
            Key.of(OrderRepo.class), Key.of(PaymentGateway.class, Region.EU), OrderService::new);
};
try (var container = Container.create(orders, configModule)) { ... }
```

Saknas en koppling stoppar `Container.create` innan något har skapats och pekar på raden:

```text
spola.di.WiringException: the container has 1 wiring problem:
  - shop.OrderService (bound at shop.Modules.lambda$orders$1(Modules.java:14)) depends on shop.OrderRepo, which is not bound
```

Konfigurationen fungerar likadant. Du anger källorna och deras ordning i koden, binder till records och får alla fel i ett enda undantag vid start. Lösenord maskeras i felmeddelanden.

## Vad som finns i dag

Tretton moduler med 2 204 tester, cirka 32 000 rader källkod och 24 000 rader test. Allt bygger grönt med `java Build.java`. Det räcker för ett webb-API med HTTPS, JSON, validering och inloggning.

| Modul | Ersätter | Tester |
| --- | --- | --- |
| `spola.web` | Spring MVC: routing som kod, filter, felhantering, JSON, cookies, formulär | 730 |
| `spola.json` | Jackson: strikt RFC 8259-parser och explicit mappning till records | 417 |
| `spola.http` | Tomcat/Netty: HTTP/1.1 på virtuella trådar, TLS, gränser mot missbruk | 283 |
| `spola.validation` | Bean Validation: komponerbara validatorer och felrapporter | 251 |
| `spola.security` | Spring Security (kärna): PBKDF2-hashning, autentisering | 103 |
| `spola.util` | Lombok och Spring-loggning: `Log`, `Lazy`, `ToString` | 80 |
| `spola.test` | JUnit: tester som lambdas | 77 |
| `spola.config` | `application.properties`, `@Value`, profiler | 68 |
| `spola.di` | Spring Core: explicit DI, scopes, livscykel | 48 |
| `spola.security.web` | Spring Security (webb): filter, HTTP Basic, åtkomstregler | 46 |
| `spola.demo` | Exempelprogram som använder modulerna tillsammans | 43 |
| `spola.codegen` | Lombok `@Builder`, `@With`: genererad källkod | 41 |
| `spola.guardrails` | Regelkontroller i bygget | 17 |

## Hur det byggs

Spola byggs av en loop med AI-agenter där ingen godkänner sitt eget arbete. En byggare skriver koden, tre kritiker med färsk kontext försöker sänka den, och en domare väger fynden.

```mermaid
flowchart LR
    P["Planerare<br/><small>väljer uppdraget</small>"] --> B["Byggare<br/><small>kod, tester, docs</small>"]
    subgraph K["parallellt, utan att se varandra"]
        BR["Breaker<br/><small>försöker knäcka det</small>"]
        AU["Auditor<br/><small>bockar av kriterier</small>"]
        MA["Maintainer<br/><small>tydlighet, enkelhet</small>"]
    end
    B --> BR & AU & MA
    BR & AU & MA --> D["Domare<br/><small>väger fynden</small>"]
    D --> C["Grönt bygge committas<br/><small>rött bygge committas aldrig</small>"]
    C -- "PASS" --> U(["Uppdraget klart<br/><small>nästa uppdrag startar</small>"])
    C -- "FAIL: fynden blir nästa runda, högst 5" --> B
```

Bara rundor där bygget och alla tester går grönt committas, så varje version i historiken fungerar. Hittills har 18 uppdrag och drygt 60 commits passerat loopen på två dygn. Varje beslut finns spårbart i git och i körningsloggarna.

## För vem och värdet

Spola passar team som vill äga sin kod helt och hållet och som hellre skriver tre rader till än lär sig en konvention.

| Målgrupp | Vad de vinner |
| --- | --- |
| Team med höga krav på leveranskedjan (offentlig sektor, finans, hälsa) | Inga tredjepartsberoenden att granska, licensera eller patcha. Allt kommer från JDK:n. |
| Drift och plattform | Moduler utan beroenden kan packas med `jlink` till en egen liten Java-runtime, för små containrar och snabb start. Ingen scanning eller reflektion vid uppstart. |
| Team med många nya utvecklare | Ingen dold magi att lära ut. "Go to definition" leder hela vägen, och felmeddelanden pekar på rätt rad. |
| Arkitekter som vill ha modern Java | Records, sealed interfaces, virtuella trådar och scoped values används fullt ut, inte som tillägg till ett äldre ramverk. |

Priset är lite mer kod på de ställen där Spring gissar, till exempel i kopplingen mellan komponenter och i JSON-mappningen. Integrationstestet visade var det blir ordrikt, och de punkterna står på färdplanen.

## Ärligt läge och färdplan

Spola är halvvägs: 10 av 20 rader i färdplanen är klara, och ramverket är inte redo för produktion än.

| Område | Färdplan | Läge |
| --- | --- | --- |
| Bygge, test, DI, konfiguration, util, JSON | 01–06 | Klart |
| HTTP-server med TLS, web, validering | 07–09 | Klart |
| Säkerhet: hashning, filter, Basic, åtkomstregler | 10 | Klart, väntar på mänsklig granskning |
| JWT, sessioner, CSRF, CORS | 11 | Nästa |
| PostgreSQL-protokoll, pool, transaktioner, query-DSL, migreringar | 12–16 | Planerat |
| Schemaläggning, händelser, observability | 17–18 | Planerat |
| Starter (motsvarar Spring Boot) och exempelapp | 19–20 | Planerat |

Kända begränsningar:

- **Säkerhetskoden (10–13) ska granskas av en människa** innan den används på riktigt. Loopen har redan hittat och rättat ett sådant fel: en metodregel med gemener, som `method("get")`, matchade aldrig och släppte igenom anropet till nästa regel.
- **Ingen leverans till externa projekt än.** Modulerna byggs som vanliga modulära jar-filer, men versionering, publicering och ett `jlink`-exempel saknas.
- **DI blir ordrik** när ett beroende har en kvalificerare, eftersom alla beroenden då måste skrivas som `Key`. En API-förbättring är planerad.
- **Bygget tar ungefär 90 sekunder** och växer med testerna.

## Nästa steg

Det snabbaste sättet att bedöma Spola är att läsa en modul bredvid sin Spring-motsvarighet. Varje modul har en README med båda sidor.

1. **Läs:** börja med [`modules/di/README.md`](../framework/modules/di/README.md) och [`modules/demo/README.md`](../framework/modules/demo/README.md), som har en ärlig friktionsrapport från integrationen.
2. **Kör:** med JDK 25 installerat bygger och testar `java Build.java` i `framework/` hela ramverket utan något annat verktyg.
3. **Prova:** kör demoprogrammet med `java -p framework/out/classes -m spola.demo/spola.demo.InventoryApp`.
4. **Bidra:** säkerhetsgranskning av modulerna 10–13 och synpunkter på API:ernas ergonomi gör störst skillnad just nu.
