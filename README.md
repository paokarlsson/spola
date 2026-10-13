# Spola

Ett ramverk för Java 25 som gör det Spring Boot och Lombok gör, utan magi och utan ett enda
beroende utanför JDK:n.

*A Java 25 framework that replaces Spring Boot and Lombok with explicit code and zero
third-party dependencies. Documentation is in Swedish.*

> **Status: version 0.x, inte redo för produktion.** Spola växer efter behov: en modul byggs när
> ett riktigt projekt behöver något som Spring eller Lombok hade gett det.
> Säkerhetsmodulerna (`spola.security`, `spola.security.web`) är inte granskade av en människa
> än. Använd dem inte för att skydda något på riktigt.

## Varför

- **Inget dolt.** Inga annoteringar som gör saker i bakgrunden, ingen classpath-scanning och
  ingen reflektion vid start. All wiring är vanlig Java som går att följa med "Go to definition".
- **Fel tidigt.** Saknade beroenden, cykler och fel i konfigurationen stoppar starten, alla på
  en gång och med raden där de finns.
- **Noll beroenden.** Bara JDK 25. Inga transitiva bibliotek att granska eller patcha.

## Snabbstart

Du behöver JDK 25 och inget annat.

```bash
cd framework
java Build.java          # kompilerar, kör guardrails och alla tester, ca 90 s
```

Jar-filerna hamnar i `framework/out/`, en per modul. Du kan också hämta dem från en
[release](https://github.com/paokarlsson/spola/releases), där varje modul finns som
`<modul>-<version>.jar` med en källkods-jar och `SHA256SUMS` (se
[`framework/MONSTER.md`](framework/MONSTER.md#använda-spola-i-ett-projekt)). En minimal app
utanför ramverket:

```java
// src/hello/module-info.java
module hello { requires spola.web; }

// src/hello/hello/Main.java
package hello;

import spola.http.*;
import spola.web.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        var router = Router.builder()
                .get("/hello", request -> Response.text(200, "Hej"))
                .build();
        try (var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), router)) {
            Thread.currentThread().join();
        }
    }
}
```

```bash
mkdir lib && cp framework/out/spola.{http,web,json,validation}.jar lib/
javac -p lib -d out $(find src -name '*.java')
java -p lib:out -m hello/hello.Main

# En egen runtime med bara det appen använder (ca 56 MB mot 252 MB för hela JDK:n):
jlink -p lib:out --add-modules hello --launcher hello=hello/hello.Main \
      --strip-debug --no-header-files --no-man-pages --output build/hello
```

## Moduler

| Modul | Ersätter |
| --- | --- |
| `spola.http` | Tomcat/Netty: HTTP/1.1 på virtuella trådar, TLS, gränser mot missbruk |
| `spola.web` | Spring MVC: routing som kod, filter, felhantering (RFC 9457), JSON, cookies, formulär, sessioner |
| `spola.json` | Jackson: strikt RFC 8259-parser och explicit mappning till records |
| `spola.validation` | Bean Validation: komponerbara validatorer |
| `spola.security` | Spring Security: PBKDF2-hashning, autentisering |
| `spola.security.web` | Spring Security: säkerhetsfilter, HTTP Basic, åtkomstregler, inloggning med session (`WebSecurity.login`/`logout`) |
| `spola.config` | `application.properties`, `@Value`, profiler |
| `spola.di` | Spring Core: explicit DI, scopes, livscykel |
| `spola.util` | Lombok och loggning: `Log`, `Lazy`, `ToString` |
| `spola.codegen` | Lombok `@Builder` och `@With` som genererad, incheckad källkod |
| `spola.test` | JUnit: tester som lambdas |
| `spola.guardrails` | Kontroller i bygget som upprätthåller reglerna |
| `spola.demo` | Exempelprogram som använder modulerna tillsammans |

Varje modul har en `README.md` med Spring-koden och Spola-koden sida vid sida. Reglerna som
gäller all kod står i [`framework/REGLER.md`](framework/REGLER.md), läget i
[`framework/PROGRESS.md`](framework/PROGRESS.md) och designbesluten i
[`framework/ARCHITECTURE.md`](framework/ARCHITECTURE.md).

## Hur Spola växer

Spola byggs efter behov, inte mot en komplett kopia av Spring. När ett projekt som använder
Spola behöver något som Spring eller Lombok har, skrivs ett behov till `uppdrag/behov/` med
koden projektet vill kunna skriva. Loopen bygger modulen och den släpps i en ny version. Regeln
för vad som hör hemma i Spola, modulkatalogen och API-mönstren står i
[`framework/MONSTER.md`](framework/MONSTER.md). Mallen för `CLAUDE.md` i ditt eget projekt finns
i [`docs/app-claude.md`](docs/app-claude.md).

Klart: bygge, testkörare, DI, konfiguration, util, JSON, HTTP med TLS, web, validering och
grundläggande säkerhet. Idéer som byggs när de behövs, till exempel JWT, PostgreSQL och
schemaläggning, står i [`uppdrag/PLAN.md`](uppdrag/PLAN.md).

## Versioner och releaser

Versionen står i konstanten `VERSION` i `framework/Build.java`. `scripts/release.sh <version>` på `main` bygger, testar,
committar och taggar `v<version>`. När taggen pushas publicerar
`.github/workflows/release.yml` en GitHub-release med jar-filerna. Så länge versionen är `0.x`
kan API:er ändras mellan MINOR-versioner.

## Hur ramverket byggs

Spola byggs av en loop med AI-agenter (Claude Code), kallad gauntlet-loopen. En planerare
väljer nästa uppdrag, en byggare skriver koden, tre oberoende kritiker försöker sänka den och en
domare väger fynden. Bara rundor där bygget och alla tester går grönt committas. Varje beslut
finns i git och i körningsloggarna.

| Katalog | Innehåll |
| --- | --- |
| `framework/` | Ramverket |
| `docs/` | [Säljmaterial](docs/saljmaterial.md) och [mallen för appars `CLAUDE.md`](docs/app-claude.md) |
| `uppdrag/` | Uppdragen, behoven i `uppdrag/behov/` och idélistan i `PLAN.md` |
| `gauntlet/` | `LOG.md` med en rad per iteration och körningarna i `runs/` |
| `scripts/gauntlet.sh` | Den yttre loopen |
| `scripts/release.sh` | Sätter versionen, bygger och taggar en release |
| `.claude/` | Agenterna och orkestreraren |
| `.devcontainer/` | Sandlådan som loopen körs i, med brandvägg |

Hur loopen körs står i [`CLAUDE.md`](CLAUDE.md).

> **Kör bara loopen i devcontainern.** Den startar Claude Code utan behörighetsfrågor
> (`--dangerously-skip-permissions`). Det är rimligt bakom containerns brandvägg men inte direkt
> på din egen dator.

## Licens

Ingen licens är vald ännu. Tills det finns en `LICENSE`-fil förbehålls alla rättigheter.
