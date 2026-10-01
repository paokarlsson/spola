# DI-kärna

## Mål
En dependency injection-container som ersätter Spring Core: komponenter registreras och
kopplas ihop explicit i Java-kod, utan annoteringar, scanning eller reflektion. Fel i
kopplingen (saknade beroenden, cykler) ska upptäckas när containern byggs, inte när en
komponent används första gången.

## Bakgrund / kontext
Uppdrag 01 (bygge, guardrails) och 02 (testkörare) är klara. Läs `framework/REGLER.md`,
`framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du börjar. Alla tester skrivs
med `spola.test`.

## Krav
- Ny modul `spola.di` under `framework/modules/di/`, utan beroenden till andra
  ramverksmoduler än `spola.test` (endast för tester).
- Typsäkra nycklar (`Key<T>`). Flera implementationer av samma typ särskiljs med
  kvalificerare som är vanliga värden, inte annoteringar.
- Registrering via fabriksfunktioner som får en resolver, t.ex.
  `bind(OrderService.class, r -> new OrderService(r.get(OrderRepo.class)))`.
- Moduler: registreringar kan grupperas i återanvändbara enheter som installeras i
  containern.
- Scopes: singleton (lat och ivrig), prototype och ett anropsscope byggt på `ScopedValue`.
- Livscykel: komponenter som implementerar `AutoCloseable` stängs när containern stängs,
  i omvänd skapandeordning. Fel vid stängning samlas och rapporteras, och stoppar inte
  stängning av övriga.
- Valideringen när containern byggs upptäcker saknade beroenden och cykler, och felet visar
  hela beroendekedjan (t.ex. `A -> B -> C -> A`).
- För att valideringen ska fungera utan reflektion deklarerar varje registrering sina
  beroenden. Välj och motivera i `ARCHITECTURE.md` hur detta görs så att det är svårt att
  deklarera fel.
- Trådsäker resolution av singletons, även med virtual threads.
- Ingen användning av `java.lang.reflect` eller `MethodHandles` i modulens huvudkod.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. Modulens huvudkod importerar inget från `java.lang.reflect` eller
   `java.lang.invoke` (kontrollerbart med sökning i källkoden).
3. Ett test visar att en cykel `A -> B -> C -> A` ger fel när containern byggs, med hela
   kedjan i felmeddelandet.
4. Ett test visar att ett saknat beroende ger fel när containern byggs, med vem som saknar
   vad i felmeddelandet.
5. Ett test visar två implementationer av samma typ som hämtas med olika kvalificerare.
6. Ett test visar att lat singleton skapas en gång även när 1 000 virtual threads hämtar den
   samtidigt.
7. Ett test visar att prototype ger ny instans per hämtning och att anropsscope ger samma
   instans inom ett scope men olika mellan scope.
8. Ett test visar stängning i omvänd ordning, och att ett undantag vid stängning av en
   komponent inte hindrar stängning av övriga.
9. `README.md` i modulen visar ett exempel bredvid motsvarande Spring-kod med `@Component`,
   `@Autowired` och `@Qualifier`.
10. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails grön).
11. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.

## Utanför scope
- Konfiguration och properties (eget uppdrag).
- AOP, proxys, interceptorer.
- Integration med web, data eller security.
- Kodgenerering.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
