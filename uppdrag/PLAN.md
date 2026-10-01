# Färdplan

Varje rad blir en egen uppdragsfil. Skriv nästa fil först när föregående uppdrag är klart,
så att "Bakgrund / kontext" kan bygga på det som faktiskt levererades (se `PROGRESS.md`).

| # | Uppdrag | Mål i korthet |
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
| 11 | security-tokens | JWT (HS256/RS256/ES256), sessioner, CSRF, CORS |
| 12 | postgres-protokoll | Wire-protokoll v3: uppstart, SCRAM-SHA-256, simple query, fel |
| 13 | postgres-utokat | Extended query, prepared statements, typer, TLS |
| 14 | data-pool-tx | Anslutningspool, transaktioner, isolationsnivåer |
| 15 | data-dsl | Typsäker query-DSL och mappning till records |
| 16 | migreringar | Versionerade SQL-migreringar med kontrollsummor |
| 17 | scheduling-events | Schemalagda jobb, händelsebuss |
| 18 | observability | Metrics, health checks, strukturerad loggning |
| 19 | starter | En main-metod som startar allt, motsvarighet till Spring Boot |
| 20 | exempelapp | CRUD-app med inloggning som använder alla moduler end-to-end |

Uppdrag 10–13 rör säkerhetskritisk kod. Låt en människa granska dem innan ramverket
används på riktigt.
