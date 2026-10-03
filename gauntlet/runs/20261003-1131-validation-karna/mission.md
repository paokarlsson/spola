Source: uppdrag/09a-validation-karna.md

# Validering del 1: komponerbara validatorer och felrapporter (`spola.validation`)

## Mål
En ny, fristående modul `spola.validation` ska ersätta det applikationer använder Jakarta Bean
Validation (Hibernate Validator) till i Spring Boot: `@NotNull`, `@NotBlank`, `@Size`, `@Min`,
`@Email`, `@Pattern`, `@Past`, `@Valid` på nästlade objekt och listor, klassnivåregler och
`ConstraintViolationException`. Allt ska ske utan annoteringar och utan reflektion. En
validator byggs en gång som vanlig Java-kod av getters (method references) och
`Constraint`-värden. Den ger en ordnad lista av `Violation(path, code, message)` där `path`
använder samma notation som `spola.json.codec` (`$.lines[3].qty`). Typfel, till exempel
`notBlank()` på ett `int`-fält, ska ge kompileringsfel. Det här är **09a**. Kopplingen till
`spola.web` (400-svar med `errors`, `readJson` med validering, formulär till records) kommer i
**09b**.

## Bakgrund / kontext
Uppdrag 01–08 är klara (rad 08 med 08a, 08b och 08c). Läs `framework/REGLER.md`,
`framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten
"JSON del 2" (sökvägsnotationen i `spola.json.codec`) och "Web del 2" (`errors`-formatet).
Alla tester skrivs med `spola.test`.

Det som finns och som 09a ska passa ihop med (se `PROGRESS.md`):
- `spola.json.codec` rapporterar mappningsfel med en sökväg i notationen från
  `spola.json.codec.Path`:
  - `$` är roten;
  - `.namn` är en medlem vars namn är en identifierare (`[A-Za-z_$][A-Za-z0-9_$]*`);
  - `["annat namn"]` är andra namn, escapade som en JSON-sträng, med tecken utanför
    utskrivbar ASCII som `\uXXXX`;
  - `[2]` är ett element.

  Namn som koden deklarerar skrivs alltid i sin helhet. Namn som kommer från data (map-nycklar)
  kortas efter 40 tecken och följs av längden. Högst 100 problem behålls, och resten räknas.
- `spola.web` har `Problem.FieldError(path, message)`, `Problem.withErrors` (högst 100) och
  `WebException.errors()`. 09b ska kunna göra om en valideringsrapport till exakt det formatet
  utan att tolka text, så `path` och `message` i en `Violation` ska kunna användas som de är.
- `Build.java` har en uttrycklig modullista (`MODULES`) med en testingång per modul.
  `spola.web` visar mönstret med `requires static` för en JDK-modul som bara testerna använder.

Erfarenhet från tidigare iterationer: text som kan hamna i en loggrad eller ett problem-svar
ska vara kort och fri från råa styrtecken. Gränser ska kontrolleras innan minnet hinner växa.
Beteendet för `null`, ordning och fel i användarkod ska vara bestämt och testat, inte
underförstått.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Modul:** `spola.validation` i `modules/validation/`, paketet `spola.validation`. Den har
  inga `requires` på andra Spola-moduler, så att den går att använda fristående (REGLER §4).
  Den får ha `requires static java.compiler` om testerna behöver det för kompileringsfelstestet
  (kriterium 9), men ingen huvudkod får använda den modulen. Modulen läggs till i `MODULES` i
  `Build.java` med testingången `spola.validation.ValidationTests`. Inga andra moduler ändras.
- **Kärnan är ett funktionellt gränssnitt, som Springs `org.springframework.validation.Validator`:**
  ```java
  @FunctionalInterface
  public interface Validator<T> {
      void validate(T value, Errors errors);
      default Validated<T> check(T value) { ... }   // hela rapporten
      default T require(T value) { ... }            // värdet, eller ValidationException
  }
  ```
  - `Errors` är den föränderliga insamlaren för en enda validering. Den är inte trådsäker och
    lever bara under anropet. Den har:
    - `reject(String code, String message)` på den aktuella sökvägen;
    - `at(String name)` för en medlem vars namn koden deklarerar (skrivs i sin helhet);
    - `atIndex(int)` för ett element;
    - `atKey(String)` för en nyckel som kommer från data (kortas efter 40 tecken som i kodeken);
    - `path()` som ger den aktuella sökvägen som text.

    `at`, `atIndex` och `atKey` ger en `Errors` för undersökvägen som skriver till samma
    rapport.
  - `Violation(String path, String code, String message)` är ett record. **En överträdelse bär
    aldrig det avvisade värdet.** Jakarta gör det (`getInvalidValue`), men värden kan vara
    lösenord eller personuppgifter och ska inte kunna hamna i loggar eller svar av misstag.
  - `Validated<T>` är ett sealed interface med `Valid<T>(T value)` och
    `Invalid<T>(List<Violation> violations, int count)`, så att resultatet kan hanteras med
    `switch` och mönster. `violations` behåller **högst 100** (samma gräns som kodeken och
    `Problem.MAX_ERRORS`), och `count` räknar alla. Valideringen fortsätter efter 100 men
    lagrar inget mer.
  - `require` ger `ValidationException` (en `RuntimeException`) med samma `violations` och
    `count`. Dess `getMessage()` är kort: antalet följt av högst de tre första som
    `path: message`, escapat och kortat till högst 2 000 tecken. Den innehåller inga råa
    styrtecken.
  - Överträdelserna kommer i **deklarationsordning, djupet först**: varje fält i den ordning
    det registrerats, med de nästlade felen direkt efter fältets egna.
- **Builder för records och andra objekt:** `Validator.<T>builder()` ger en oföränderlig
  validator via `build()`. Byggaren har följande metoder:
  - `field(String name, Function<? super T, ? extends V> getter, Constraint<? super V>... constraints)`;
  - `optional(String name, Function<? super T, Optional<V>> getter, Constraint<? super V>...)`,
    där innehållet valideras om det finns och ett tomt `Optional` är giltigt;
  - `nested(String name, Function<? super T, ? extends V> getter, Validator<? super V> validator)`,
    som motsvarar `@Valid`;
  - `each(String name, Function<? super T, ? extends List<? extends V>> getter, Constraint<? super V>...)`
    för varje element, med sökvägen `$.namn[i]`;
  - `eachNested(String name, ..., Validator<? super V>)`, som motsvarar `List<@Valid X>`;
  - `rule(Predicate<? super T> test, String code, String message)` för en regel på objektets
    egen sökväg (klassnivå, `@AssertTrue`);
  - `rule(String name, Predicate<? super T> test, String code, String message)` för en
    korsfältsregel som rapporteras på fältet `name`;
  - `include(Validator<? super T>)` för att återanvända en annan validator för samma typ;
  - `when(Predicate<? super T>, Validator<? super T>)` för villkorlig validering. Den ersätter
    Jakartas grupper: i Spola är en grupp en egen validator, eller `include`/`when`.

  `Validator.lazy(Supplier<Validator<T>>)` gör rekursiva strukturer möjliga, som
  `LazyCodec` i JSON. `Validator.of(Constraint<? super T>...)` validerar ett enkelt värde på
  roten. Ett `@SafeVarargs` på varargs-metoderna är tillåtet enligt REGLER §2.
  `build()` kontrollerar namnen (inte `null`, inte tomma) och att ingen getter, constraint
  eller validator är `null`. Fel ger `IllegalArgumentException` eller `NullPointerException`
  direkt.
- **`Constraint<T>`** är en oföränderlig **klass, inte ett funktionellt gränssnitt**, så att
  överlagringarna mot `Validator` aldrig blir tvetydiga. Den har:
  - `Constraint.of(String code, Predicate<? super T> test, String message)`;
  - `withMessage(String)`;
  - `withCode(String)`.

  `code` är en stabil, maskinläsbar nyckel (`notBlank`, `length`, …) som 09b och senare
  översättning kan använda. Meddelandena är engelska standardtexter. Ingen interpolering och
  ingen `MessageSource`/i18n ingår.
- **`null`-semantik som i Jakarta:** varje constraint utom `notNull`, `notBlank` och `notEmpty`
  godtar `null`. Predikatet i en egen `Constraint.of` anropas aldrig med `null`. `nested`,
  `eachNested`, `each` och `optional` hoppar över `null` (eller ett tomt `Optional`). Ett
  `null`-element i en lista valideras av elementets constraints (så `each(..., notNull())`
  fångar det). Alla constraints för ett fält körs i ordning, och ingen avbryter de andra.
- **Inbyggda constraints i `Constraints`** (exakta koder och standardtexter står i Javadoc och
  testas ordagrant):

  | Spola | Jakarta | code | meddelande |
  |---|---|---|---|
  | `notNull()` | `@NotNull` | `notNull` | `must not be null` |
  | `notBlank()` (`CharSequence`, `String.isBlank`-semantik) | `@NotBlank` | `notBlank` | `must not be blank` |
  | `notEmpty()` (`CharSequence`, `Collection`, `Map` som separata överlagringar eller metoder med olika namn) | `@NotEmpty` | `notEmpty` | `must not be empty` |
  | `length(min, max)`, `minLength(n)`, `maxLength(n)`, räknat i **kodpunkter** | `@Size` på text | `length` | `length must be between 1 and 100` / `at least 1` / `at most 100` |
  | `size(min, max)` för `Collection`/`Map` | `@Size` | `size` | `size must be between 1 and 50` |
  | `min(long)`, `max(long)`, `range(long, long)` på `Number` | `@Min`, `@Max` | `min`, `max`, `range` | `must be at least 1`, `must be at most 10`, `must be between 1 and 10` |
  | `decimalMin(BigDecimal, boolean inclusive)`, `decimalMax(...)` | `@DecimalMin/Max` | `decimalMin`, `decimalMax` | dokumenterade |
  | `positive()`, `positiveOrZero()`, `negative()`, `negativeOrZero()` | samma | samma | dokumenterade |
  | `digits(int integer, int fraction)` | `@Digits` | `digits` | dokumenterat |
  | `matches(Pattern)` (hela värdet) | `@Pattern` | `pattern` | `must match the required format` (mönstret skrivs inte ut) |
  | `email()` | `@Email` | `email` | `must be a well-formed email address` |
  | `oneOf(Collection<? extends T>)` | (enum/egen) | `oneOf` | dokumenterat |
  | `past(Supplier<C> now)`, `pastOrPresent`, `future`, `futureOrPresent` för `C extends Comparable<? super C>` | `@Past` m.fl. | samma | `must be in the past` m.fl. |

  Ytterligare regler för `Constraints`:
  - **Tal** jämförs exakt för `Byte`, `Short`, `Integer`, `Long`, `BigInteger` och
    `BigDecimal`. `Double` och `Float` jämförs via `BigDecimal` och är ogiltiga om de är NaN
    eller oändliga. Andra `Number`-typer jämförs via `BigDecimal(toString())`; går det inte är
    värdet ogiltigt (aldrig ett undantag).
  - **`email()` är avsiktligt enkel och bestämd:**
    - exakt ett `@`;
    - en lokal del av 1–64 tecken med RFC 5322 `dot-atom` (`atext` och punkter, ingen punkt
      först eller sist och inga två i rad);
    - en domän av 1–253 tecken med LDH-etiketter på 1–63 tecken, separerade av punkt, utan
      bindestreck först eller sist i en etikett;
    - inga citerade lokala delar, inga IP-literaler och inget annat än ASCII.

    `a@b` godtas, som i Hibernate Validator.
  - **Tid** jämförs mot en uttrycklig `Supplier` (`() -> LocalDate.now(clock)`). Det finns
    ingen dold systemklocka, så testerna blir deterministiska och typen kontrolleras vid
    kompilering.
  - Parametrarna kontrolleras när constrainten skapas: `min <= max`, inga negativa längder och
    inga `null`. Fel ger `IllegalArgumentException`.
- **Fel i användarkod:** ett undantag från en getter, ett predikat eller en egen `Validator`
  släpps igenom oförändrat. Ingen halv rapport returneras. Det står i Javadoc.
- **Trådsäkerhet:** byggda validatorer och constraints är oföränderliga och kan delas
  (`static final`) av godtyckligt många trådar. Varje `check`/`require` har sin egen `Errors`.
- **Ingen cykeldetektering i objektgrafen.** Records med listor som innehåller sig själva
  ligger utanför. Det står bland kända begränsningar.

## Krav
- Modulen `spola.validation` med `Validator`, `Errors`, `Violation`, `Validated` (`Valid`,
  `Invalid`), `ValidationException`, `Constraint` och `Constraints` enligt besluten ovan.
- Publikt API dokumenteras med Javadoc, inklusive `null`-semantiken, ordningen, gränsen på 100
  och varje inbyggd constraints kod och standardtext.
- `module-info.java` med en Javadoc som säger vad modulen ersätter.
- `README.md` i `modules/validation/` med följande:
  - Ett komplett exempel: ett `CreateOrder`-record med kund, e-post, valfri anteckning, en
    lista av orderrader (nästlad validator) och en korsfältsregel för datum. Exemplet står
    bredvid motsvarande Spring Boot-kod med Jakarta-annoteringar, `@Valid` och
    `@AssertTrue`. Spola-delen är en testkälla som kompileras, och ett test kontrollerar att
    README visar den oförändrad (som `ItemApi` i 08b).
  - En tabell från Jakarta-annotering till Spola-constraint.
  - En avvikelsetabell som minst tar upp:
    - inget avvisat värde i överträdelsen;
    - längd i kodpunkter i stället för `char`;
    - grupper ersätts av `include`/`when`;
    - ingen i18n;
    - tid mot en uttrycklig klocka;
    - `email()`:s definition;
    - gränsen på 100;
    - att undantag från användarkod släpps igenom.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och testsviten för
   `spola.validation` körs i bygget.
2. `modules/validation/src/module-info.java` har inga `requires` på andra Spola-moduler och
   ingen annan `requires` än eventuellt `requires static java.compiler` för testerna.
   Huvudkoden importerar inget från `java.lang.reflect`, `java.lang.invoke` eller
   `javax.tools`. `Build.java` har modulen i `MODULES`.
3. Ett tabelldrivet test av de inbyggda constraints har minst 80 fall, med både giltiga och
   ogiltiga värden för varje constraint i tabellen ovan. Varje ogiltigt fall kontrollerar exakt
   `code` och `message`. Fallen ska täcka:
   - gränsvärden (exakt `min`, `max`, `min - 1` och `max + 1`);
   - `null` för varje constraint (giltigt utom för `notNull`/`notBlank`/`notEmpty`);
   - längd i kodpunkter (en emoji räknas som 1);
   - `Integer`, `Long`, `BigDecimal`, `BigInteger` och `Double` (inklusive NaN och oändlig);
   - `decimalMin` med och utan `inclusive`;
   - `digits`;
   - `matches` som kräver träff på hela värdet;
   - `oneOf`;
   - `past`/`future` och varianterna med `OrPresent` mot en fast klocka, där exakt "nu" skiljer
     dem åt.
4. Ett tabelldrivet test av `email()` har minst 15 giltiga och 15 ogiltiga adresser. Bland dem
   ska finnas `a@b`, `first.last+tag@example.co.uk`, två `@`, en punkt först/sist/dubbel i den
   lokala delen, en lokal del på 64 och 65 tecken, en etikett på 63 och 64 tecken, ett
   bindestreck först i en etikett, ett mellanslag, en citerad lokal del, en IP-literal och
   icke-ASCII.
5. Ett test visar sökvägarna i `Violation.path` för:
   - roten (`$`);
   - ett fält (`$.name`);
   - ett nästlat fält (`$.address.city`);
   - ett listelement (`$.lines[3].qty`);
   - ett namn som inte är en identifierare (`$["first name"]`);
   - en `atKey` med ett styrtecken (escapat som `\u001B`);
   - en `atKey` längre än 40 tecken (kortad, följd av längden);
   - ett deklarerat namn längre än 40 tecken (i sin helhet).

   Den förväntade texten ska vara exakt densamma som `spola.json.codec` ger för motsvarande
   sökväg. Ange i testet vilket kodekfall varje sökväg motsvarar.
6. Ett test med `CreateOrder` från README visar:
   - ett giltigt värde ger `Valid` med samma instans;
   - ett värde med fel i flera fält, i två orderrader och i korsfältsregeln ger `Invalid` med
     exakt förväntad lista (sökväg, kod, meddelande) i deklarationsordning, djupet först;
   - `require` kastar `ValidationException` med samma lista;
   - `optional` med tomt `Optional` och `nested`/`eachNested` med `null` ger inga
     överträdelser;
   - `each(..., notNull())` fångar ett `null`-element med sökvägen `[i]`;
   - `include` och `when` (sann och falsk);
   - `Validator.lazy` på en rekursiv trädstruktur med fel på djup 50 ger rätt sökväg.
7. Ett test visar gränsen: en lista med 10 000 ogiltiga element ger `Invalid` med exakt 100
   `violations` (de första 100 i ordning) och `count` 10 000. `getMessage()` för motsvarande
   `ValidationException` har högst 2 000 tecken och nämner antalet.
8. Ett test visar att en egen `Validator` (en lambda) som använder `Errors.at`, `atIndex`,
   `atKey` och `reject` validerar en `Map<String, Integer>`, och att en egen
   `Constraint.of(...)` med `withMessage`/`withCode` aldrig anropas med `null`.
9. Ett test kompilerar små källtexter i minnet med `javax.tools`. Det visar att:
   - `field("age", Person::age, Constraints.notBlank())` för en `int`-komponent ger
     kompileringsfel;
   - `field("name", Person::name, Constraints.min(1))` för en `String` ger kompileringsfel;
   - `field("age", Person::age, Constraints.min(0))`, en `Constraint<Object>` på ett
     `String`-fält och `eachNested` med en `Validator<Object>` kompilerar.
10. Ett test visar att undantag från en getter, ett predikat och en egen validator släpps
    igenom oförändrade (samma instans) från både `check` och `require`. Det visar också att
    felaktiga parametrar till `Constraints` (`length(5, 1)`, `minLength(-1)`, `null`-mönster)
    och till byggaren (tomt namn, `null`-getter) ger `IllegalArgumentException` eller
    `NullPointerException` när de skapas.
11. Ett test med 200 virtuella trådar som delar en `static final` validator och validerar
    olika värden visar att varje tråd får exakt sina egna överträdelser.
12. Ett test visar att ett `message` eller en `atKey` med `\n`, `\u001b` eller en rå
    radbrytning inte hamnar oescapat i `ValidationException.getMessage()` eller i
    `Violation.path`. Det visar också att `Violation` inte har något fält som bär det
    validerade värdet.
13. `README.md` i `modules/validation/` har exemplet bredvid Spring Boot (som en kompilerad
    testkälla som ett test jämför med README), annoteringstabellen och avvikelsetabellen
    enligt kraven.
14. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i
    `framework/REGLER.md` är bruten.
15. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md anger att rad 09 är delad
    i 09a (detta) och 09b (webbintegration). ARCHITECTURE.md beskriver:
    - varför modulen är fristående;
    - `Validator`/`Errors` som kärna och byggaren ovanpå;
    - varför `Constraint` är en klass;
    - `null`-semantiken;
    - ordningen;
    - gränsen på 100;
    - att värdet inte bärs;
    - sökvägsnotationen och att den är densamma som kodekens;
    - tid mot en uttrycklig `Supplier`;
    - `email()`:s definition;
    - ingen cykeldetektering;
    - vad 09b ska bygga vidare på.

## Utanför scope
- Allt i `spola.web`: 400-svar med `errors`, `readJson`/`readForm` med validering, bindning av
  formulär till records (09b).
- Validering av konfiguration i `spola.config` och integration i `spola.demo`.
- i18n, `MessageSource`, meddelandeinterpolering med parametrar.
- Generering av validatorer från records med `spola.codegen`.
- Metodvalidering (`@Validated` på metodparametrar och returvärden), grupper och
  gruppsekvenser.
- Cykeldetektering i objektgrafer.
- Kreditkort, URL, ISBN och andra Hibernate-specifika constraints.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan (`@SafeVarargs` och
`@FunctionalInterface` är tillåtna), ingen reflektion och inga namnkonventioner. Inga nya
filtyper i `framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
