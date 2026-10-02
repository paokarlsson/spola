# Integration: kärnmodulerna i ett riktigt program

## Mål
Ett litet men riktigt program, modulen `spola.demo`, som använder `spola.di`,
`spola.config` och `spola.util` tillsammans, på samma sätt som en applikationsutvecklare
skulle göra. Syftet är att pröva ergonomin innan fler moduler bygger vidare på API:erna.
Fel som bara syns när modulerna används ihop ska hittas nu, när det fortfarande är billigt
att ändra API:erna, och inte först i uppdrag 19–20 (starter, exempelapp). Utfallet är både
programmet och en ärlig friktionsrapport.

## Bakgrund / kontext
Uppdrag 01–04 och 05a är klara: bygge och guardrails, testkörare (`spola.test`),
DI-container (`spola.di`), konfiguration (`spola.config`) och loggning med hjälpare
(`spola.util`). Varje modul är testad för sig, men ingen kod använder dem tillsammans än.
Läs `framework/REGLER.md`, `framework/PROGRESS.md`, `framework/ARCHITECTURE.md` och
modulernas `README.md` innan du börjar.

Det här uppdraget är handskrivet och inskjutet före 05b (util-builders), som planeraren
redan har aviserat. 05b påverkas inte och körs efter detta.

Observera från `PROGRESS.md`: guardrails tillåter bara `.java` och `.md` i `framework/`. En
konfigurationsfil för programmet får därför inte checkas in. Testerna skapar den i en
temporär katalog.

## Krav
- Ny modul `spola.demo` under `framework/modules/demo/`, med testingång i `MODULES` i
  `Build.java`. Modulen får bero på `spola.di`, `spola.config` och `spola.util` (och
  `spola.test` för tester), men ingen annan modul får bero på `spola.demo`.
- **Programmet:** en lagertjänst för en liten webbutik, utan nätverk och utan databas.
  - En `main(String[] args)` som läser en orderfil (en order per rad, t.ex.
    `sku;antal`), drar av ordrarna mot ett lagersaldo och skriver en sammanfattning
    (behandlade ordrar, avvisade ordrar med orsak, slutsaldo per artikel).
  - All kod som går att testa går också att anropa utan `main`: en ingång som tar `args`,
    miljövariabler som `Map`, och `PrintStream`/`OutputStream` för utdata och logg, och som
    returnerar en exitkod.
- **Konfiguration via `spola.config`:** minst standardvärden i koden, en fil, en profil
  (t.ex. `dev` som byter startsaldo eller loggnivå), miljövariabler med prefix och
  kommandoradsargument, i den ordning README för `spola.config` rekommenderar. Minst en
  nyckel är känslig och maskeras. Konfigurationen binds till records.
- **Wiring via `spola.di`:** minst fyra komponenter, t.ex. lagerrepository, prissättning
  eller regelkontroll, orderbehandlare och rapportskrivare. Minst en komponent har en
  kvalificerare, minst en är `AutoCloseable` och stängs av containern, och config-recorden
  binds in som README för `spola.config` visar. Ingen komponent känner till containern.
- **Loggning via `spola.util`:** `Log` i komponenterna och `Logging.configure` med nivåer
  från konfigurationen (`logging.level.*`), som README för `spola.util` visar.
- **Felvägar som en användare möter:** saknad eller ogiltig konfiguration, ett
  wiring-fel och en trasig rad i orderfilen ger var och en ett begripligt felmeddelande och
  en exitkod skild från 0, inte en stackdump.
- **Friktionsrapport:** ett avsnitt `## Friktion` i `modules/demo/README.md`. Varje punkt
  har ett kort kodutdrag från programmet, vad som var krångligt, ordrikt eller
  överraskande, och vad som gjordes:
  - **Åtgärdat:** en liten ändring i `spola.di`, `spola.config` eller `spola.util`, med
    test i den modulen och uppdaterad README och Javadoc. Ändringar ska vara små och
    tillägg i första hand. Inga omskrivningar.
  - **Kvar:** något för större för det här uppdraget. Det läggs då också under "Återstår"
    i `PROGRESS.md`.
  Rapporten ska vara ärlig. Om något är bra räcker det med att säga det. Hitta inte på
  friktion, och dölj den inte heller.
- `README.md` i modulen beskriver också hur programmet körs och visar motsvarande
  uppstart i Spring Boot bredvid, kort.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.demo` med sin
   testingång står i `MODULES` i `Build.java`.
2. `spola.demo` har `requires` på `spola.di`, `spola.config` och `spola.util`, och ingen
   annan moduls `module-info.java` har `requires spola.demo`.
3. Ett test kör hela programmet genom ingången, utan att anropa komponenterna direkt. Det
   använder en orderfil och en konfigurationsfil i en temporär katalog och kontrollerar
   sammanfattningen (behandlade, avvisade med orsak, slutsaldo) och exitkod 0.
4. Ett test visar prioritetsordningen från början till slut: samma nyckel satt i fil,
   profil, miljö och argument, där argumentet vinner och sammanfattningen påverkas på ett
   synligt sätt.
5. Ett test visar att den känsliga nyckeln inte förekommer i klartext någonstans i
   programmets utdata eller logg, inte heller i felmeddelanden.
6. Ett test visar att `AutoCloseable`-komponenten stängs när programmet avslutas, både när
   det lyckas och när behandlingen avbryts av ett fel.
7. Ett test visar att loggnivåer från konfigurationen styr loggutdata, t.ex. att
   `logging.level.<paket>=DEBUG` ger en debugrad som annars inte syns.
8. Tester visar de fyra felvägarna (saknad konfiguration, ogiltigt värde, wiring-fel,
   trasig orderrad). Varje fall ger exitkod skild från 0 och ett meddelande som innehåller
   den felande nyckeln, komponenten eller radnumret, och utdata innehåller ingen stackdump
   (`\tat ` förekommer inte).
9. Ingen klass i `spola.demo` utom ingången och dess wiring-modul nämner `Container`,
   `Binder` eller `Config` (kontrollerbart med sökning).
10. `modules/demo/README.md` har ett avsnitt `## Friktion` med minst tre punkter, var och
    en med kodutdrag och markerad **Åtgärdat** eller **Kvar**. Varje **Åtgärdat** har en
    motsvarande ändring och ett test i den berörda modulen. Varje **Kvar** står också under
    "Återstår" i `PROGRESS.md`.
11. Alla befintliga tester i `spola.di`, `spola.config` och `spola.util` är gröna. Ändrade
    publika API:er är dokumenterade i respektive README och i `ARCHITECTURE.md`.
12. Inga resursfiler är incheckade under `framework/`, och `Allowlist.DOCUMENT_EXTENSIONS`
    är oförändrad. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n
    (guardrails grön), och ingen regel i `framework/REGLER.md` är bruten.
13. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.

## Utanför scope
- HTTP, JSON och databas (uppdrag 06 och framåt). Programmet läser och skriver bara filer
  och strömmar.
- Builders och with-metoder (05b).
- En generell uppstartsmodul som motsvarar Spring Boot (uppdrag 19). Uppstarten skrivs för
  hand i `spola.demo`. Om den visar sig ordrik ska det stå i friktionsrapporten, men en
  starter ska inte byggas här.
- Större omdesign av befintliga API:er. Sådant hamnar under **Kvar**.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga nya filtyper i `framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
