# Spec: Integration: kärnmodulerna i ett riktigt program

## Uppgift
Bygg modulen `spola.demo`: en lagertjänst för en liten webbutik (orderfil in, sammanfattning ut) som använder `spola.di`, `spola.config` och `spola.util` tillsammans som en applikationsutvecklare skulle, plus en ärlig friktionsrapport med små åtgärder i kärnmodulerna. Fullständiga krav och bakgrund: `mission.md` i denna katalog.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.demo` med sin testingång står i `MODULES` i `Build.java`.
2. `spola.demo` har `requires` på `spola.di`, `spola.config` och `spola.util`, och ingen annan moduls `module-info.java` har `requires spola.demo`.
3. Ett test kör hela programmet genom ingången (args, miljö som `Map`, `PrintStream`/`OutputStream` för utdata och logg, returnerar exitkod), utan att anropa komponenterna direkt. Det använder en orderfil och en konfigurationsfil i en temporär katalog och kontrollerar sammanfattningen (behandlade, avvisade med orsak, slutsaldo per artikel) och exitkod 0.
4. Ett test visar prioritetsordningen från början till slut: samma nyckel satt i standardvärde/fil, profil, miljö (med prefix) och argument, där argumentet vinner och sammanfattningen påverkas på ett synligt sätt. Ordningen är den som README för `spola.config` rekommenderar.
5. Ett test visar att den känsliga nyckeln inte förekommer i klartext någonstans i programmets utdata eller logg, inte heller i felmeddelanden.
6. Ett test visar att `AutoCloseable`-komponenten stängs av containern när programmet avslutas, både när det lyckas och när behandlingen avbryts av ett fel.
7. Ett test visar att loggnivåer från konfigurationen (`logging.level.*`, via `Logging.configure`) styr loggutdata, t.ex. att `logging.level.<paket>=DEBUG` ger en debugrad som annars inte syns.
8. Tester visar de fyra felvägarna (saknad konfiguration, ogiltigt värde, wiring-fel, trasig orderrad). Varje fall ger exitkod skild från 0 och ett meddelande som innehåller den felande nyckeln, komponenten eller radnumret, och utdata innehåller ingen stackdump (`\tat ` förekommer inte).
9. Ingen klass i `spola.demo` utom ingången och dess wiring-modul nämner `Container`, `Binder` eller `Config` (kontrollerbart med sökning). Minst fyra komponenter, minst en med kvalificerare, minst en `AutoCloseable`, config-records bundna som README för `spola.config` visar, och ingen komponent känner till containern.
10. `modules/demo/README.md` har ett avsnitt `## Friktion` med minst tre punkter, var och en med kodutdrag och markerad **Åtgärdat** eller **Kvar**. Varje **Åtgärdat** har en motsvarande (liten, i första hand tilläggs-) ändring och ett test i den berörda modulen. Varje **Kvar** står också under "Återstår" i `PROGRESS.md`. Rapporten är ärlig.
11. Alla befintliga tester i `spola.di`, `spola.config` och `spola.util` är gröna. Ändrade publika API:er är dokumenterade i respektive README, Javadoc och i `ARCHITECTURE.md`.
12. Inga resursfiler är incheckade under `framework/`, och `Allowlist.DOCUMENT_EXTENSIONS` är oförändrad. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails grön), och ingen regel i `framework/REGLER.md` är bruten.
13. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. `modules/demo/README.md` beskriver hur programmet körs och visar kort motsvarande uppstart i Spring Boot.

## Utanför scope
HTTP, JSON, databas; builders/with-metoder (05b); en generell starter (uppdrag 19); större omdesign av befintliga API:er (hamnar under **Kvar**).

## Antaganden
- Konfigurationsfiler för programmet skapas av testerna i temporära kataloger (inga resursfiler i `framework/`).
- Orderfilformatet är `sku;antal`, en order per rad.
