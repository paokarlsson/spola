# Validering del 2: validering och formulärbindning i webblagret (`spola.web` + `spola.validation`)

## Mål
`spola.web` ska kunna validera det som kommer in i en förfrågan och svara med 400 och en
`errors`-lista per fält, på samma sätt som Spring Boot gör med `@Valid @RequestBody`,
`@Valid @ModelAttribute`, `BindingResult` och `MethodArgumentNotValidException`. Allt sker utan
annoteringar och utan reflektion. En handler ska kunna skriva
`request.readJson(ORDER, ORDER_RULES)` och få ett giltigt värde tillbaka, eller låta ramverket
svara 400. Formulär och query-parametrar ska kunna bindas till records med en uttrycklig
`FormBinder` som samlar *alla* fel i stället för att avbryta vid det första, och sedan
valideras. Det här är **09b**, sista delen av rad 09 i `PLAN.md` (efter 09a).

## Bakgrund / kontext
Uppdrag 01–08 och 09a är klara. Läs `framework/REGLER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "Web del 2" (`readJson`,
`errors`-formatet), "Web del 3" (`readForm`, `FormData`) och "Validering del 1" (särskilt
punkten "Vad 09b bygger vidare på"). Alla tester skrivs med `spola.test`.

Det som finns och som 09b bygger på (se `PROGRESS.md`):
- `spola.validation` (09a) är fristående och har inga `requires` på andra Spola-moduler.
  Följande finns där:
  - `Validator<T>` med `check(value)`, som ger `Validated` (`Valid(value)` eller
    `Invalid(violations, count)`), och `require(value)`;
  - `Violation(path, code, message)`, som aldrig bär värdet, där `path` är i kodekens
    notation (`$`, `.namn`, `["annat namn"]`, `[i]`) och `message` redan är escapat och kortat
    så att det kan användas som det är;
  - `ValidationException` med `violations()` och `count()`;
  - gränsen `Validated.MAX_VIOLATIONS = 100`.
- `spola.web` (08a–08c) kräver `spola.http` och `spola.json` transitivt och har följande:
  - `WebRequest.readJson(codec)` och `readJson(codec, limits)`. Kontrollerna sker i ordningen
    415 → 400 (tom kropp) → 413/400 (parsning) → 400 (mappning). Mappningsfelet har detaljen
    `Request body has N problems.` och `errors`.
  - `Problem.FieldError(path, message)`, `Problem.withErrors` (högst 100, `MAX_ERRORS`) och
    `WebException.errors()`. Den interna `JsonBody` bygger 400-svaret med `errors` från
    `JsonMappingException`.
  - `WebRequest.readForm()`/`readForm(maxBytes)`, som ger `FormData` (`first`, `all`,
    `get(name, Param)`, `required(name, Param)`, `asMap`, `isEmpty`), och `queryParams()`.
  - `Param` (`STRING`, `INT`, `LONG`, `BOOLEAN`, `UUID`, `LOCAL_DATE`, `BIG_DECIMAL`, `enumOf`,
    `of`) med `typeName()`. Dess felmeddelanden innehåller i dag värdet (escapat och kortat).
  - `ExceptionMapper<E>` (`map(exception, request)`) och `RouterBuilder.onException`, samt
    `WebException.toResponse(request)`.
- `Build.java` ordnar modulerna efter deras `requires`, så att lägga till ett `requires` i
  `spola.web` räcker. `MODULES` behöver inte ändras.

Erfarenhet från tidigare iterationer: text som kan hamna i en loggrad eller ett problem-svar
ska vara kort och fri från råa styrtecken. Värden från klienten (särskilt lösenord) ska inte
eka tillbaka i felsvar. Gränser ska kontrolleras innan minnet hinner växa. Beteendet för
`null`, tomma värden, ordning och fel i användarkod ska vara bestämt och testat, inte
underförstått.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Beroendets riktning:** `spola.web` får `requires transitive spola.validation`
  (transitivt eftersom `Validator`, `Validated` och `ValidationException` finns i webbens
  publika API). `spola.validation` ändras inte och förblir fristående. Det får inte finnas
  några ändringar i dess huvudkod och inga nya `requires`. Ingen ny modul skapas. Motivering:
  `spola.validation` är liten och saknar beroenden, och en separat bryggmodul skulle bara ge
  en extra modul att lägga till utan att någon vinner på det. Inga andra `requires` läggs till
  i `spola.web`.
- **`Problem.FieldError` får en valfri kod:** recordet blir
  `FieldError(String path, String message, Optional<String> code)`.
  - Den befintliga konstruktorn `FieldError(path, message)` finns kvar och ger en tom `code`.
  - `toJson()` skriver medlemmarna i ordningen `path`, `message` och sedan `code`. `code`
    skrivs bara när den finns.
  - `code` måste vara utskrivbar ASCII utan mellanslag och högst 100 tecken. Annars blir det
    `IllegalArgumentException`.
  - Mappningsfel från `readJson` har ingen kod, som i dag, så JSON-utdata för befintliga
    svar är oförändrad. Valideringsfel får `Violation.code()`. Bindningsfel får koderna nedan.
  - Befintliga tester i `spola.web` får inte ändras. Nya sviter registreras i `WebTests`.
- **Valideringsfel blir 400 på ett enda sätt:** det finns två publika fabriker i
  `WebException`:
  - `WebException.invalid(Validated.Invalid<?> invalid)`;
  - `WebException.invalid(ValidationException exception)`.

  Båda ger status 400, standardtiteln och detaljen `The request has N validation errors.`
  (`1 validation error.` i singular), följd av ` The first 100 are listed.` när `count > 100`.
  `errors` innehåller `violations` i ordning med `path`, `message` och `code`, oförändrade.
  Svaret innehåller inget avvisat värde.
- **`readJson` med validator:**
  - `WebRequest.readJson(JsonCodec<T> codec, Validator<? super T> validator)`;
  - `WebRequest.readJson(JsonCodec<T> codec, JsonLimits limits, Validator<? super T> validator)`.

  Alla kontroller och all ordning från `readJson(codec, limits)` gäller oförändrade. Först
  därefter körs `validator.check(value)`. `Valid` ger värdet, och `Invalid` ger
  `WebException.invalid(...)`. Validatorn anropas **aldrig** om läsning, parsning eller
  mappning misslyckades: mappningsfel går före valideringsfel, precis som i Spring där en
  `HttpMessageNotReadableException` kommer före valideringen. Ett undantag från validatorn
  släpps igenom oförändrat, så att routerns mappers eller 500 tar det.
- **Formulär och query binds med en uttrycklig `FormBinder<T>`:**
  ```java
  @FunctionalInterface
  public interface FormBinder<T> {
      T bind(FormFields fields);
  }

  static final FormBinder<Signup> SIGNUP = f -> new Signup(
          f.required("email", Param.STRING),
          f.optional("age", Param.INT),
          f.all("interests", Param.STRING),
          f.flag("terms"));
  ```
  `FormFields` är en insamlare som lever under ett enda bindningsanrop, som `Errors` i 09a.
  Den läser ur en `FormData` (formulär) eller ur queryn och **samlar alla fel i stället för
  att kasta vid det första**. Den har följande metoder:
  - `<V> V required(String name, Param<V> param)`. Saknas fältet blir det felet `required` med
    meddelandet `is required`, och metoden ger `null`.
  - `<V> Optional<V> optional(String name, Param<V> param)`. Saknas fältet blir det
    `Optional.empty()`.
  - `<V> List<V> all(String name, Param<V> param)` ger alla värden i ordning. En tom lista är
    giltig. Fel rapporteras per element på sökvägen `$.namn[i]`.
  - `boolean flag(String name)` för kryssrutor. Ett saknat fält ger `false`. Värdena `on`,
    `true` och `1` ger `true`, och `false` och `0` ger `false`. Allt annat blir felet `type`.

  Följande regler gäller för `FormFields`:
  - **Ett tomt värde räknas som saknat** för alla `Param` utom `Param.STRING`. HTML-formulär
    skickar `age=` för ett tomt fält, och ett tomt valfritt tal ska inte ge fel (Spring gör
    likadant med `StringTrimmerEditor`/`null`-konvertering). För `Param.STRING` är `""` ett
    värde, så att `notBlank()` i valideringen fångar det. Regeln jämför med
    `param == Param.STRING` och står i Javadoc.
  - **Fler än ett värde för `required`/`optional`/`flag`** blir felet `single` med
    meddelandet `must have a single value`. Det tar inte det första värdet tyst, som skydd mot
    parameter pollution.
  - **Ett konverteringsfel** blir felet `type` med meddelandet `expected <typeName>`
    (till exempel `expected int`). Meddelandet **innehåller aldrig värdet**, till skillnad från
    `Param`s egna meddelanden, eftersom fältet kan vara ett lösenord. När `required` eller
    `optional` får ett konverteringsfel ger de `null` respektive `Optional.empty()`.
  - **Okända fält ignoreras**, till exempel CSRF-token och submit-knappar.
  - **Sökvägar** skrivs i kodekens och valideringens notation: `$.email`,
    `$["first name"]` och `$.interests[2]`. Namnen kommer från koden och skrivs därför i sin
    helhet. Renderingen är en privat kopia i `spola.web` av algoritmen för medlemsnamn och
    index i `spola.validation.Path`. Inget nytt publikt API läggs till i andra moduler.
  - **Felens ordning** följer anropsordningen i bindern. Högst 100 fel behålls och alla
    räknas, med samma gräns som valideringen. Gränsen kontrolleras innan sökvägen renderas.
  - **Ett undantag från bindern:** om bindern kastar (oftast en record-konstruktor som får
    `null` efter ett fel) och fel redan har samlats, blir svaret 400 med de insamlade felen,
    och undantaget kastas bort. Om inga fel har samlats släpps undantaget igenom oförändrat.
  - **Användning efteråt:** en `FormFields` som används efter sitt bindningsanrop ger
    `IllegalStateException`.
- **Nya metoder i `WebRequest`:**
  - `<T> T readForm(FormBinder<T> binder)`;
  - `<T> T readForm(FormBinder<T> binder, Validator<? super T> validator)`;
  - `<T> T bindQuery(FormBinder<T> binder)`;
  - `<T> T bindQuery(FormBinder<T> binder, Validator<? super T> validator)`.

  `readForm(binder...)` läser kroppen exakt som `readForm()`, med samma 415/413/400, samma
  gräns och samma läs-en-gång-regel. `bindQuery` läser `queryParams()` och berör inte
  kroppen.

  Bindningsfel ger 400 med `errors`. Detaljen är `Request body has N problems.` för formulär
  och `Query has N problems.` för query, följd av ` The first 100 are listed.` när fler än 100
  fel har räknats. Validatorn körs bara om bindningen lyckades. Valideringsfel ger
  `WebException.invalid(...)`.

  Det finns också en publik standardmetod `FormBinder.bind(FormData data)`, som ger värdet
  eller kastar samma `WebException`. Med den går bindningen att använda och testa utan en
  förfrågan.
- **`ValidationException` från tjänstekod mappas inte automatiskt.** Utan en mapper blir det
  500, som i dag. Ett fel i validering av data som inte kommer från förfrågan är en bugg och
  inte klientens fel. Spring gör likadant för `ConstraintViolationException`. Den som vill ha
  400 registrerar mappern uttryckligen i en rad, som README visar:
  `onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))`.

## Krav
- `module-info.java` i `spola.web` har `requires transitive spola.validation` och en
  uppdaterad Javadoc. `spola.validation` är oförändrad.
- `Problem.FieldError` med `code`, `WebException.invalid(...)` (båda),
  `readJson(codec, validator)`, `readJson(codec, limits, validator)`, `FormBinder`,
  `FormFields`, `readForm(binder)`, `readForm(binder, validator)`, `bindQuery(binder)` och
  `bindQuery(binder, validator)` enligt besluten ovan.
- Publikt API dokumenteras med Javadoc. Det gäller ordningen mellan mappning, bindning och
  validering, koderna (`required`, `type`, `single`), regeln för tomma värden, gränsen på 100,
  undantag från bindern och validatorn, och att inga värden ekas.
- `README.md` i `modules/web/` får två exempel bredvid motsvarande Spring Boot-kod:
  - ett JSON-API med `@Valid @RequestBody` och Jakarta-annoteringar, som i Spola blir
    `readJson(codec, validator)`;
  - ett registreringsformulär med `@Valid @ModelAttribute` och `BindingResult`, som i Spola
    blir `readForm(binder, validator)`.

  Spola-delen är en testkälla som kompileras, och ett test kontrollerar att README visar den
  oförändrad (som `ItemApi` och `ProfileApp`). README får också nya rader i
  jämförelsetabellen och avvikelserader för följande:
  - inget `BindingResult`, så handlern får aldrig ett ogiltigt värde;
  - mappningsfel före valideringsfel;
  - värden ekas aldrig;
  - tomt värde räknas som saknat utom för `STRING`;
  - flera värden är ett fel;
  - ingen automatisk 400 för `ValidationException`.

## Acceptanskriterier
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

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan (`@FunctionalInterface`
och `@SafeVarargs` är tillåtna), ingen reflektion och inga namnkonventioner. Formulärbindningen
läser inga record-komponenter via reflektion: allt står i bindern. Inga nya filtyper i
`framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
