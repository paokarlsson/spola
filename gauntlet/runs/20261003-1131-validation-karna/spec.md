# Spec: Validering del 1: komponerbara validatorer och felrapporter (`spola.validation`)

## Uppgift
Bygg den nya fristående modulen `spola.validation` (09a): validatorer som vanlig Java-kod (builder av getters + `Constraint`), utan annoteringar och reflektion, som ger en ordnad lista `Violation(path, code, message)` med kodekens sökvägsnotation. Alla designbeslut, krav och detaljer står i `mission.md` i den här katalogen och är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/` (ny modul `framework/modules/validation/`, `Build.java`, `PROGRESS.md`, `ARCHITECTURE.md`).

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

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
Övertagna oförändrade från `mission.md`. Designbesluten i `mission.md` (API-signaturer, koder, meddelanden, null-semantik, ordning, gräns 100, email-definition) ingår i kontraktet; avvikelse från dem räknas som att kriteriet inte är uppfyllt.

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

## Antaganden
- Rundgräns: 5 (enligt `Rundor` i mission.md).
- Där mission.md säger "dokumenterade" (t.ex. meddelanden för `decimalMin`, `digits`, `oneOf`, `positive`) väljer byggaren texten, skriver den i Javadoc och testar den ordagrant.
- `requires static java.compiler` är tillåtet enbart för kompileringsfelstestet (kriterium 9).
