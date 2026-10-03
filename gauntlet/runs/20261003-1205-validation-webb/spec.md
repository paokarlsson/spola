# Spec: Validering del 2 — validering och formulärbindning i spola.web

## Uppgift
Koppla `spola.validation` till `spola.web`: `readJson(codec, validator)`/`readJson(codec, limits, validator)`,
`WebException.invalid(...)`, `Problem.FieldError.code`, `FormBinder`/`FormFields` med insamlade fel,
`readForm(binder[, validator])` och `bindQuery(binder[, validator])`, samt README, PROGRESS och ARCHITECTURE.
Hela uppdraget, inklusive alla designbeslut och krav, står i `mission.md` i den här katalogen
(källa: `uppdrag/09b-validation-webb.md`). Designbesluten och kraven i `mission.md` är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

## Acceptanskriterier (övertagna oförändrade från mission.md; alla måste vara uppfyllda)
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i
   `spola.web`s svit.
2. `modules/web/src/module-info.java` har `requires transitive spola.validation` och inga
   andra nya `requires`. `git diff` visar inga ändringar under `modules/validation/src/`.
   Huvudkoden i `spola.web` importerar fortfarande inget från `java.lang.reflect` eller
   `java.lang.invoke`.
3. Befintliga testfiler i `modules/web/test/` är oförändrade, med undantag för att nya
   sviter registreras i `WebTests`. Ett test visar följande:
   - `new Problem.FieldError(p, m)` ger tom `code`;
   - `toJson()` utelämnar `code` när den saknas och skriver den sist när den finns;
   - en ogiltig kod (mellanslag, styrtecken, 101 tecken) ger `IllegalArgumentException`;
   - ett mappningsfel från `readJson` ger exakt samma JSON som före uppdraget.
4. Ett test genom servern med `HttpClient` visar för `readJson(codec, validator)` att:
   - ett giltigt värde når handlern;
   - ett värde med fel i flera fält och i en nästlad lista ger 400 `application/problem+json`
     med exakt förväntad `errors` (path, message, code) i valideringens ordning och exakt
     detalj;
   - ett mappningsfel ger mappningssvaret, och validatorn anropas inte (en räknande validator
     står kvar på 0);
   - 415, 413 och tom kropp ger samma svar som utan validator, och validatorn anropas inte;
   - en lista med 250 ogiltiga element ger 100 `errors` och detaljen
     `The request has 250 validation errors. The first 100 are listed.`;
   - ett undantag från validatorn når en registrerad mapper oförändrat (samma instans) och
     blir annars 500 utan undantagets meddelande i svaret;
   - `readJson(codec, limits, validator)` använder de givna gränserna.
5. Ett tabelldrivet test av `FormBinder`/`FormFields`, med `FormBinder.bind(FormData)` utan
   server, har minst 35 fall. Varje fall kontrollerar exakt värde eller exakt `errors` (path,
   message, code) och täcker:
   - `required` som saknas, finns och har fel typ;
   - `optional` som saknas, är tomt och finns;
   - ett tomt värde för `INT`, `LONG`, `UUID`, `LOCAL_DATE` och `BIG_DECIMAL` (saknat) och för
     `STRING` (`""`);
   - två värden för `required`, `optional` och `flag` (`single`);
   - `all` med noll, ett och flera värden och med fel på index 0 och 2 (`$.namn[0]`,
     `$.namn[2]`);
   - `flag` med alla godkända värden, med saknat fält och med `yes` (`type`);
   - `enumOf`;
   - ett namn som inte är en identifierare (`$["first name"]`) och ett namn med ett
     styrtecken (escapat);
   - okända fält som ignoreras;
   - felordningen när bindern anropar fälten i en annan ordning än formulärets.
6. Ett test visar gränsen och undantagsregeln:
   - en binder med `all("n", Param.INT)` över 1 000 ogiltiga värden ger exakt 100 `errors`
     (de första i ordning) och detaljen `Request body has 1000 problems. The first 100 are
     listed.`;
   - en record-konstruktor som kastar `NullPointerException` efter ett saknat `required` ger
     400 med felet `required`;
   - en binder som kastar utan insamlade fel släpper igenom samma undantagsinstans;
   - en `FormFields` som sparats och används efter bindningen ger `IllegalStateException`.
7. Ett test genom servern med `HttpClient` visar att:
   - `readForm(binder, validator)` ger 200 för ett giltigt formulär och 400 med bindningsfel
     (validatorn anropas inte) respektive valideringsfel;
   - 415, 413 och läs-en-gång (`IllegalStateException`, så 500) gäller som för `readForm()`;
   - `bindQuery(binder, validator)` på en `GET` ger bindningsfel med detaljen
     `Query has N problems.` och valideringsfel, och läser aldrig kroppen.
8. Ett test visar att sökvägarna från `FormFields` är exakt desamma som `spola.validation`
   ger med `Errors.at`/`atIndex` för samma namn och index: en identifierare, ett namn med
   mellanslag, ett namn med `"` och `\`, ett namn med ett styrtecken, ett namn med ett tecken
   utanför ASCII, ett namn längre än 40 tecken (i sin helhet) och ett index.
9. Ett test visar att inga värden ekas. Ett lösenordsfält med en markörsträng som inte går
   att konvertera, och ett JSON-fält med markörsträngen som underkänns av en validator, ger
   400-svar där markörsträngen inte finns i kroppen, i huvudena eller i loggraderna på
   `spola.web`.
10. Ett test visar att en `ValidationException` från tjänstekod ger 500 utan mapper, och
    exakt `WebException.invalid(e)`s svar (400, `errors` med `code`) med mappern från README.
    Testet visar också att `WebException.invalid(Validated.Invalid)` och
    `WebException.invalid(ValidationException)` ger samma problem för samma överträdelser,
    med singular- och pluraldetalj.
11. Ett test med 200 samtidiga klienter (virtuella trådar och `HttpClient`) mot en router som
    delar `static final` binder, codec och validator visar att varje klient får exakt sitt eget
    svar, giltigt eller med sina egna `errors`.
12. `README.md` i `modules/web/` har de två exemplen bredvid Spring Boot (som kompilerade
    testkällor som ett test jämför med README), de nya raderna i jämförelsetabellen och
    avvikelseraderna enligt kraven.
13. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i
    `framework/REGLER.md` är bruten.
14. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md anger att rad 09 i
    `PLAN.md` är klar (09a och 09b) och listar kända begränsningar. ARCHITECTURE.md får ett
    avsnitt "Validering del 2" som beskriver:
    - beroendets riktning och varför det inte finns någon bryggmodul;
    - `FieldError.code`;
    - ordningen mappning/bindning före validering;
    - `FormBinder`/`FormFields` som insamlare och varför de inte kastar vid första felet;
    - regeln för tomma värden;
    - flera värden som fel;
    - att värden aldrig ekas;
    - undantagsregeln för bindern;
    - den kopierade sökvägsrenderingen;
    - varför `ValidationException` inte mappas automatiskt.

## Utanför scope
- Generering av kodekar, binders eller validatorer från records med `spola.codegen`.
- Metodvalidering (`@Validated` på tjänster), i18n, `MessageSource` och översättning av koder.
- Validering av sökvägs-, cookie- och huvudparametrar utöver det som `Param` redan gör.
- Multipart och filuppladdning.
- Validering i `spola.config` och integration i `spola.demo`.
- Nästlade formulärnamn (`address.city`, `items[0].qty`) och bindning till nästlade objekt.
  Formulär är platta. Nästlade strukturer skickas som JSON.
- Att ändra `spola.validation`.

## Antaganden
- Designbesluten i `mission.md` ("Designbeslut (fattade av planeraren …)") och avsnittet "Krav" gäller som
  en del av kontraktet. En avvikelse från dem räknas som ett ouppfyllt krav.
- Begränsningarna i `mission.md` gäller: inga annoteringar utöver tillåtelselistan, ingen reflektion,
  inga namnkonventioner, inga nya filtyper i `framework/`.
- Rundgräns: 5 (från `mission.md`).
