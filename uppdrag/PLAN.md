# Plan

Spola byggs **efter behov** (se `framework/MONSTER.md`). Planen styr inte längre vad loopen
bygger. Loopen tar handskrivna uppdrag i `uppdrag/` och behov från appprojekt i
`uppdrag/behov/`, och står still när inget väntar.

## Levererat

| # | Uppdrag | Innehåll |
|---|---|---|
| 01 | bygge-och-guardrails | `java Build.java`, regelkontroller för annoteringar och beroenden |
| 02 | testkorare | Ersätter JUnit, tester som lambdas |
| 03 | di-karna | Explicit DI, scopes, livscykel, validering vid uppstart |
| 04 | config | Typsäker konfiguration från filer, miljövariabler, argument, profiler |
| 05 | util | Lombok-ersättare: builders, with-metoder, loggning på `System.Logger` |
| 06 | json | Parser och serialisering med explicit mappning |
| 07 | http-server | HTTP/1.1 på virtual threads, TLS, keep-alive, gränser mot missbruk |
| 08 | web-routing | Routing som kod, request/response-modell, felhantering, JSON-integration |
| 09 | validation | Komponerbara validatorer, felrapporter som går att returnera via web |
| 10 | security-grund | PBKDF2-hashning, filterkedja, autentisering, auktoriseringsregler |

Nya uppdrag får nästa lediga nummer efter det högsta i `uppdrag/` och `uppdrag/klara/`.

## Idélista

Det här är skisser, inte beställningar. Planeraren bygger aldrig något härifrån på eget
initiativ. När ett behov motsvarar en idé får skissen fungera som utgångspunkt för uppdraget.

| Idé | Motsvarar | Skiss |
|---|---|---|
| security-tokens | Spring Security | JWT (HS256/RS256/ES256), sessioner, CSRF, CORS |
| postgres-protokoll | PostgreSQL-drivrutinen | Wire-protokoll v3: uppstart, SCRAM-SHA-256, simple query, fel |
| postgres-utokat | PostgreSQL-drivrutinen | Extended query, prepared statements, typer, TLS |
| data-pool-tx | HikariCP, `@Transactional` | Anslutningspool, transaktioner, isolationsnivåer |
| data-dsl | Spring Data JDBC, jOOQ | Typsäker query-DSL och mappning till records |
| migreringar | Flyway | Versionerade SQL-migreringar med kontrollsummor |
| scheduling-events | `@Scheduled`, `ApplicationEventPublisher` | Schemalagda jobb, händelsebuss |
| observability | Actuator, Micrometer | Metrics, health checks, strukturerad loggning |
| starter | Spring Boot | En main-metod som startar allt |
| build | Maven/Gradle för appar | `spola.build`: läsa `deps.txt`, hämta och kontrollera jar-filer, kompilera, testa, jlink |

Säkerhetskritiska moduler (security, tokens, postgres) ska granskas av en människa innan
ramverket används på riktigt.
