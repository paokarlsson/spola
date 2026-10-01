Source: uppdrag/04-config.md

# Config

## Mål
Typsäker konfiguration som ersätter Spring Boots `@ConfigurationProperties`, `@Value`,
`application.properties` och profiler. Värden läses från filer, miljövariabler,
systemegenskaper och kommandoradsargument i en uttalad prioritetsordning, och binds till
records med explicit mappning i vanlig Java-kod. Alla fel (saknade nycklar, värden som inte
går att tolka) ska rapporteras samtidigt vid uppstart, inte första gången ett värde används.

## Bakgrund / kontext
Uppdrag 01 (bygge, guardrails), 02 (testkörare, `spola.test`) och 03 (DI-container,
`spola.di`) är klara. Läs `framework/REGLER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar. Alla tester skrivs med `spola.test`. Varje
modul och dess testingång läggs till explicit i `MODULES` i `Build.java`.

Observera från `PROGRESS.md`: guardrails tillåter bara `.java` och `.md` i `framework/`
(`Allowlist.DOCUMENT_EXTENSIONS`). Testerna ska därför skriva sina konfigurationsfiler till
temporära kataloger (`Files.createTempDirectory`) i stället för att checka in
`.properties`-filer. Utöka inte tillåtelselistan i detta uppdrag.

`spola.di` lämnar konfiguration utanför scope. Config-modulen ska stå fristående (REGLER §4)
och inte bero på `spola.di`; kopplingen sker genom att applikationen binder det färdiga
config-recordet med `toInstance`, vilket README visar.

## Krav
- Ny modul `spola.config` under `framework/modules/config/`, utan beroenden till andra
  ramverksmoduler än `spola.test` (endast för tester).
- **Källor**, var och en ett värde som skapas explicit i koden:
  - properties-fil (format enligt `java.util.Properties`, läst som UTF-8), både obligatorisk
    och valfri (valfri fil som saknas är inte ett fel; obligatorisk ger fel med sökvägen),
  - miljövariabler, systemegenskaper, kommandoradsargument (`--nyckel=värde`),
  - en `Map<String,String>` (för standardvärden och tester).
  Källorna läggs i en uttalad ordning där senare källa vinner. Miljön ska kunna injiceras som
  en `Map` så att tester inte beror på processens riktiga miljö.
- **Beslut om miljövariabler (fattas här, dokumenteras i `ARCHITECTURE.md`):** ingen implicit
  "relaxed binding". Miljövariabler blir nycklar på ett av två explicita sätt: (a) en
  uttrycklig tabell `Map.of("DATABASE_URL", "db.url")`, eller (b) ett prefix som anges i
  koden, t.ex. `environment("APP_")`, där regeln är exakt: prefixet tas bort, resten görs till
  gemener och `_` blir `.` (`APP_DB_POOL_SIZE` → `db.pool.size`). Variabler utan prefixet
  läses inte. Regeln står i Javadoc och README.
- **Profiler:** aktiva profiler anges explicit (från argument, en nyckel i redan lästa källor,
  eller i koden). Profilspecifika källor väljs med en funktion som användaren skriver, t.ex.
  `profile -> Path.of("config/app-" + profile + ".properties")`; inga filnamn är inbyggda.
  Flera aktiva profiler läggs på i angiven ordning. Var i prioritetsordningen profilkällor
  hamnar beslutas och dokumenteras.
- **Platshållare:** `${annan.nyckel}` och `${annan.nyckel:standard}` i värden ersätts efter
  att alla källor slagits ihop. Cykler (`a=${b}`, `b=${a}`) ger fel med hela kedjan. Ett sätt
  att skriva ett bokstavligt `${` finns och är dokumenterat.
- **Typsäker bindning utan reflektion:** konfiguration binds till records via en funktion som
  användaren skriver, t.ex.
  `config.bind("db", c -> new DbConfig(c.string("url"), c.intValue("pool.size", 10),
  c.duration("timeout")))`. Inbyggda omvandlingar för minst `String`, `int`, `long`,
  `boolean`, `double`, `Duration`, `URI`, `Path`, enum (via `Class<E>` och `Enum.valueOf`,
  ingen annan reflektion), listor (kommaseparerat), `Optional` och egna omvandlare som
  `Function<String,T>`. Nästlade records binds med samma mekanism under ett delprefix.
- **Alla fel samtidigt:** bindning samlar alla fel (saknad nyckel, ogiltigt värde med
  förväntad typ, källan värdet kom ifrån) och kastar ett enda undantag med en lista, i stället
  för att stanna vid det första. Beslut om hur detta görs i en funktion som returnerar ett
  record (t.ex. att läsfunktioner returnerar platshållarvärden och felen samlas i bakgrunden,
  eller en annan lösning) dokumenteras i `ARCHITECTURE.md`.
- **Hemligheter:** nycklar kan markeras som känsliga (explicit lista eller predikat), och deras
  värden maskeras i felmeddelanden och i en diagnostisk utskrift av den sammanslagna
  konfigurationen som visar varje nyckel med värde och vilken källa som vann.
- Konfigurationen är oföränderlig efter att den byggts och trådsäker att läsa.
- Ingen användning av `java.lang.reflect` eller `java.lang.invoke` i modulens huvudkod.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.config` med sin
   testingång står i `MODULES` i `Build.java`.
2. Modulens huvudkod importerar inget från `java.lang.reflect` eller `java.lang.invoke` och
   har inga `requires` på andra ramverksmoduler (kontrollerbart med sökning i källkoden och
   `module-info.java`).
3. Ett test visar prioritetsordningen med samma nyckel i fyra källor (map-standardvärde, fil,
   miljö, argument) och att argumentet vinner; ett test per par visar att ordningen följer
   den ordning källorna lades i.
4. Ett test visar prefixregeln för miljövariabler (`APP_DB_POOL_SIZE` → `db.pool.size`),
   att variabler utan prefix inte läses, och den explicita tabellformen. Miljön injiceras som
   `Map` i testerna.
5. Ett test visar profiler: två aktiva profiler, profilfiler valda via användarens funktion,
   och att den senare profilen vinner över den tidigare och båda över basfilen.
6. Ett test visar att en valfri fil som saknas ignoreras och att en obligatorisk fil som
   saknas ger fel med sökvägen i meddelandet.
7. Ett test visar platshållare med och utan standardvärde, ett bokstavligt `${`, och att en
   cykel `a -> b -> a` ger fel med hela kedjan i meddelandet.
8. Ett test binder ett record med ett nästlat record och minst sex av de inbyggda typerna
   (inklusive `Duration`, enum, lista och `Optional`) samt en egen omvandlare.
9. Ett test visar att en bindning med två saknade nycklar och ett ogiltigt värde ger **ett**
   undantag som listar alla tre felen, med nyckel, förväntad typ och källa för det ogiltiga
   värdet.
10. Ett test visar att ett känsligt värde inte förekommer i felmeddelandet för ett ogiltigt
    värde eller i den diagnostiska utskriften, och att utskriften visar vilken källa som vann
    för varje nyckel.
11. `README.md` i modulen visar ett exempel bredvid motsvarande Spring Boot-kod
    (`application.properties`, `@ConfigurationProperties`, `@Value`, `@Profile`/
    `spring.profiles.active`) och hur config-recordet kopplas in i `spola.di` med
    `toInstance`.
12. Inga konfigurationsfiler är incheckade under `framework/`, och
    `Allowlist.DOCUMENT_EXTENSIONS` är oförändrad.
13. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails grön).
14. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive besluten om
    miljövariabler, profilernas plats i prioritetsordningen och hur fel samlas vid bindning.

## Utanför scope
- YAML, TOML, JSON och andra format än properties (JSON kommer i uppdrag 06).
- Omladdning vid ändring (hot reload) och fjärrkällor (config server, Vault).
- Automatisk bindning via reflektion eller kodgenerering.
- Validering av värden utöver typomvandling (uppdrag 09); ett enkelt predikat per värde är
  tillåtet men inte krav.
- Integration med `spola.di` utöver exemplet i README.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod. Inga nya filtyper i
`framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
