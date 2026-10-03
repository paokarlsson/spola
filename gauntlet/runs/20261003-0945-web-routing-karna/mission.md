Source: uppdrag/08a-web-routing-karna.md

# Web del 1: routing som kod, parametrar, filter och felhantering

## Mål
En modul `spola.web` ovanpå `spola.http` som ersätter kärnan i Spring MVC för en
applikation: routes som skrivs som kod i stället för `@RestController`/`@GetMapping`,
sökvägsvariabler och queryparametrar med strikt procentavkodning och typade värden, filter
(motsvarigheten till `OncePerRequestFilter`/`HandlerInterceptor`), typade request-attribut
som filter kan lämna vidare, och central felhantering (motsvarigheten till
`@ControllerAdvice`/`@ExceptionHandler`) med felsvar i RFC 9457-format
(`application/problem+json`, som Spring Boots `ProblemDetail`). Routern är en vanlig
`spola.http.Handler`, så den startas med `HttpServer.start(options, router)`. JSON-kroppar
via kodekar, cookies och innehållsförhandling kommer i 08b och bygger på detta.

## Bakgrund / kontext
Uppdrag 01–07 är klara: bygge och guardrails, testkörare (`spola.test`), DI (`spola.di`),
konfiguration (`spola.config`), loggning och hjälpare (`spola.util`), builders
(`spola.codegen`), `spola.demo`, JSON (`spola.json` med trädmodell, strikt parser, skrivare
och `spola.json.codec`) och HTTP-servern (`spola.http`, 07a och 07b). Läs
`framework/REGLER.md`, `framework/PROGRESS.md` och `framework/ARCHITECTURE.md` (särskilt
avsnitten om `spola.http` och `spola.json`) innan du börjar. Alla tester skrivs med
`spola.test`. Modulen och dess testingång läggs till explicit i `MODULES` i `Build.java`.

Det som finns i `spola.http` efter 07a/07b (se `PROGRESS.md`):
- `Handler` (`Response handle(Request) throws Exception`), `HttpServer.start(options,
  handler)`, `port()`, `close()`.
- `Request`: `method()`, `target()`, **rå** `path()` och **rå** `query()` (ingen
  procentavkodning; den är uttryckligen lämnad till uppdrag 08), `host()`, `scheme()`,
  `tls()`, `version()`, `headers()`, `body()`/`bytes()`. Request-target har redan
  kontrollerats mot RFC 3986-tecken (inga styrtecken, blanktecken eller byte över 0x7E).
- `Response` (record: status, `Headers`, sealed `ResponseBody`) med fabrikerna `text`,
  `bytes`, `stream`, `status` och `withHeader`. Reserverade huvuden (`Content-Length`,
  `Transfer-Encoding`, `Connection`, `Date`) avvisas. `HEAD` går till handlern och servern
  skickar inte kroppen.
- Ett undantag från handlern ger 500 utan detaljer från servern; `RequestBodyException`
  (trasig eller för stor kropp) ger kroppens status.

Rad 08 i `PLAN.md` (routing som kod, request/response-modell, felhantering,
JSON-integration) delas av planeraren i två uppdrag, eftersom routing och felhantering
redan är ett fullt uppdrag och sökvägsavkodningen är säkerhetskritisk:
- **08a (detta uppdrag):** routing, procentavkodning, sökvägs- och queryparametrar, filter,
  attribut, felhantering och problem-svar.
- **08b:** JSON-kroppar in och ut via `spola.json.codec` (`JsonMappingException` blir 400
  med alla problem), `Content-Type`/`Accept` (415/406), cookies och formulärkroppar.

Erfarenhet från tidigare iterationer: meddelanden och loggrader som innehåller extern data
(sökvägar, parametervärden, undantagsmeddelanden) har gång på gång kunnat förfalska loggrader
eller bli orimligt långa. Varje loggrad som byggs här ska vara kort och fri från råa
styrtecken, även efter procentavkodning (`%1B`, `%0A` i en sökväg eller parameter).

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Modul:** `spola.web` (`modules/web/`, paket `spola.web`), `requires transitive
  spola.http` (API:t använder `Response` och `Headers`) och `requires spola.json` (för
  problem-svaren; trädmodellen och skrivaren räcker i 08a). Inga andra ramverksmoduler.
  Ingen reflektion, inga preview-funktioner.
- **API (namnen kan justeras, men formen gäller):**
  - `Router` implementerar `spola.http.Handler` och är oföränderlig och trådsäker. Den byggs
    med `Router.builder()` och `get`/`post`/`put`/`patch`/`delete`/`route(method, pattern,
    handler)`, `filter(Filter)`, `group(prefix, Consumer<RouterBuilder>)`,
    `onException(Class<E>, ExceptionMapper<E>)` och `build()`.
  - Funktionellt gränssnitt `WebHandler` med `Response handle(WebRequest request) throws
    Exception`. Svaret är `spola.http.Response` (ingen ny svarstyp i 08a).
  - `WebRequest` (oföränderlig): `http()` (den underliggande `Request`), `method()`,
    avkodad `path()`, `pathSegments()`, `pathParam(name)`, `pathParam(name, Param<T>)`,
    `query(name)` (`Optional<String>`, första värdet), `queryAll(name)` (`List<String>`),
    `query(name, Param<T>)` (`Optional<T>`), `requiredQuery(name, Param<T>)` (`T`),
    `queryParams()` (oföränderlig, ordnad multimap), `headers()`, `body()`/`bytes()`,
    `attribute(AttributeKey<T>)` (`Optional<T>`) och `withAttribute(key, value)` (ny
    `WebRequest`).
  - `Param<T>`: typad omvandling från sträng med inbyggda `STRING`, `INT`, `LONG`,
    `BOOLEAN` (bara `true`/`false`), `UUID`, `LOCAL_DATE`, `BIG_DECIMAL`, `Param.enumOf(Class)`
    (exakt namn) och `Param.of(name, Function<String,T>)` för egna typer. Ingen tyst
    tvångsomvandling (`"1.0"` är inte ett `INT`, `" 5"` är inte ett `INT`, tal utanför
    intervallet är fel).
  - `AttributeKey<T>`: identitetsbaserad typad nyckel (`AttributeKey.of(String name,
    Class<T> type)`), så att ett filter kan lämna t.ex. en inloggad användare till handlern
    utan strängnycklar och typkonvertering.
  - Funktionellt gränssnitt `Filter` med `Response filter(WebRequest request, Chain next)
    throws Exception`, där `Chain.proceed(WebRequest)` anropar resten av kedjan. Ett filter
    kan svara själv utan att anropa `next`, byta request (attribut) och ändra svaret.
  - `WebException` (okontrollerad, inte `final`, så att applikationer kan ärva) med status,
    titel och valfri detalj, och `Problem` (record för RFC 9457: `type`, `title`, `status`,
    `detail`, `instance` och extra medlemmar som `JsonObject`) med `toResponse()`.
  - Funktionellt gränssnitt `ExceptionMapper<E extends Throwable>` med
    `Response map(E exception, WebRequest request) throws Exception`.
- **Mönster:** ett mönster börjar med `/` och består av segment som är antingen en literal,
  en variabel `{name}` (exakt ett helt segment) eller, bara som sista segment, en
  catch-all `{name...}` (resten av sökvägen, minst ett segment, som en sträng med `/`
  mellan de avkodade segmenten och som `List<String>` via `pathSegments`-liknande åtkomst;
  välj och dokumentera). Inga reguljära uttryck, inga jokertecken, inga delsegment
  (`{id}.json`), ingen `%` i mönster. Variabelnamn är Java-identifierare och unika i
  mönstret. Rot-mönstret `/` är tillåtet.
- **Validering vid `build()` (fel vid uppstart, inte vid första anropet):** felaktiga
  mönster, felaktiga metodnamn (inte `token` enligt RFC 9110 §5.6.2), och två routes med
  samma metod och samma form (t.ex. `/a/{x}` och `/a/{y}`) ger `IllegalArgumentException`
  (eller en egen subtyp) som räknar upp **alla** problem på en gång, med mönstren i
  meddelandet. Samma princip som `Container.create` i `spola.di`.
- **Matchning (oberoende av registreringsordning):** sökvägen delas på `/` i rå form och
  först därefter procentavkodas varje segment, så `%2F` aldrig skapar ett nytt segment.
  Per segment, från vänster: literal går före variabel som går före catch-all, med
  backtracking (för `/a/b/c` och routerna `/a/{x}/c` och `/a/b/{y}` vinner `/a/b/{y}`; finns
  bara `/a/{x}/c` matchar den). Routern väljer den mest specifika route som matchar både
  sökvägen och metoden. Uppslaget är ett träd per segment (trie), inte en linjär lista med
  mönster, och kostnaden beror inte på antalet routes. Avslutande snedstreck är strikt:
  `/orders/` matchar inte `/orders` (som Spring 6). Metodnamn jämförs skiftlägeskänsligt
  (RFC 9110 §9.1).
- **404, 405, HEAD och OPTIONS:** ingen route matchar sökvägen ger 404. Matchar sökvägen
  routes men ingen med metoden ger 405 med `Allow` (unionen av metoderna för alla routes som
  matchar sökvägen, sorterade). `HEAD` utan egen route går till `GET`-routen. `OPTIONS` utan
  egen route ger 204 med `Allow`. `Allow` innehåller alltid `HEAD` när `GET` finns och
  `OPTIONS`. Request-target i asterisk-form (`OPTIONS *`) ger 404.
- **Strikt sökväg (som Spring Securitys `StrictHttpFirewall`), 400 före routing:**
  procentavkodning är strikt UTF-8 (felaktig sekvens, `%` utan två hexsiffror, överlånga
  kodningar och surrogat ger 400); kodat `/` (`%2F`), kodat `\` (`%5C`), kodat `%` som
  avkodas till en ny kodning tolkas inte en gång till (ingen dubbelavkodning), och avkodade
  styrtecken (U+0000–U+001F, U+007F) i ett segment ger 400; segmenten `.` och `..` (även
  kodade, `%2e`, `%2E%2e`) ger 400; tomma segment (`//`) ger 400. Sökvägen används bara
  avkodad efter dessa kontroller. Den råa sökvägen finns kvar via `http()`.
- **Queryparametrar:** `application/x-www-form-urlencoded`-regler (WHATWG URL Standard
  §5.1): `&` skiljer par, första `=` skiljer namn och värde, `+` är mellanslag, strikt
  UTF-8-avkodning som för sökvägen (felaktig kodning ger 400), namn utan `=` får tomt värde,
  tomma par (`&&`) hoppas över, flera värden per namn behåller ordningen. Högst 1 000 par
  (som Tomcats `maxParameterCount` i Spring Boot 3.x); fler ger 400. Avkodade styrtecken är
  tillåtna i parametervärden (de är data) men får aldrig nå en loggrad oescapade. Parsningen
  är lat och görs högst en gång per förfrågan.
- **Typade parametrar:** misslyckad omvandling ger `WebException` 400 med parameterns namn
  och ett kort meddelande (värdet citeras högst 40 tecken, utan styrtecken);
  `requiredQuery` utan värde ger 400. `pathParam` med ett namn som inte finns i den matchade
  routens mönster är ett programmeringsfel: `IllegalArgumentException` (som blir 500).
- **Filter:** routerns filter körs för varje förfrågan, även för 404, 405, automatisk
  `OPTIONS` och 400 från sökvägskontrollen (så att CORS och säkerhet i senare uppdrag kan
  ligga som filter); en grupps filter körs bara för gruppens routes, innanför routerns
  filter. Ordningen är registreringsordningen, det första filtret ytterst. Grupper kan
  nästlas och prefixet är ett mönster (får innehålla variabler).
- **Felhantering:** ett undantag från handler eller filter går till den mapper vars klass
  ligger närmast undantagets klass i arvskedjan (inte registreringsordning); två mappers för
  samma klass är ett fel vid `build()`. Utan mapper: `WebException` blir sitt problem-svar
  med sin status; `RequestBodyException` släpps vidare till `spola.http`, som vet vad som
  ska hända med anslutningen; allt annat blir 500 med ett problem-svar utan undantagets
  meddelande eller stackspår och loggas på `ERROR` (loggernamn `spola.web`) med metod och
  sökväg, escapat och kortat. Kastar en mapper själv blir det 500 utan att mappers anropas
  igen (ingen rekursion). 404/405/400 från routern är `WebException`s och går genom samma
  mappers, så en applikation kan ge egna felsidor.
- **Problem-svar (RFC 9457):** `Content-Type: application/problem+json`, medlemmarna
  `type` (standard `about:blank`), `title` (statusens standardfras när ingen annan anges),
  `status`, `detail` (bara när den finns) och `instance` (den avkodade sökvägen escapad som
  JSON). `detail` och extra medlemmar skrivs med `spola.json` och är högst 2 000 tecken.

## Krav
- Ny modul `spola.web` under `framework/modules/web/` enligt besluten ovan, exporterar
  `spola.web`. Testerna använder `spola.test` och `java.net.http.HttpClient`
  (`requires static` som i `spola.http`).
- Router, mönster, matchning, avkodning, queryparametrar, typade parametrar, attribut,
  filter, felhantering och problem-svar enligt besluten.
- `Router` och allt den bär är oföränderligt efter `build()` och trådsäkert; inget delat
  föränderligt tillstånd per förfrågan.
- Publikt API dokumenteras med Javadoc.
- `README.md` i `modules/web/`: ett komplett exempel (routes med sökvägs- och
  queryparametrar, en grupp med ett filter som lägger ett attribut, en egen
  `WebException`-subtyp med mapper, start med `HttpServer`) bredvid motsvarande Spring
  Boot-kod (`@RestController`, `@GetMapping`, `@PathVariable`, `@RequestParam`,
  `OncePerRequestFilter`, `@ControllerAdvice`/`@ExceptionHandler`, `ProblemDetail`), och en
  tabell över avvikelser från Spring MVC (strikt avslutande snedstreck, matchningsordning,
  strikt sökväg, parametergräns).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.web` med sin
   testingång står i `MODULES` i `Build.java`.
2. `modules/web/src/module-info.java` har `requires transitive spola.http`, `requires
   spola.json` och inga andra `requires` på ramverksmoduler (utöver `requires static` för
   testernas behov); huvudkoden importerar inget från `java.lang.reflect` eller
   `java.lang.invoke`.
3. Ett test över en riktig `HttpServer` (port 0, `127.0.0.1`) med `HttpClient` visar
   `GET`, `POST`, `PUT`, `PATCH` och `DELETE` till routes med sökvägsvariabler och
   queryparametrar, där handlern får rätt avkodade värden.
4. Ett tabelldrivet matchningstest med minst 20 fall visar: literal före variabel före
   catch-all, backtracking-exemplet `/a/{x}/c` mot `/a/b/{y}`, att resultatet inte beror på
   registreringsordningen (samma routes registrerade i omvänd ordning ger samma utfall),
   catch-all med ett och flera segment och att catch-all kräver minst ett segment, roten
   `/`, strikt avslutande snedstreck, att metoden väljer mellan routes med samma sökväg,
   nästlade grupper med variabler i prefixet, och `%2F` som stannar inne i ett segment
   och då ger 400.
5. Ett test visar 404 (med problem-svar), 405 med exakt `Allow`-värde, `HEAD` som går till
   `GET`-routen (utan kropp, med samma `Content-Length`), automatiskt `OPTIONS` (204 med
   `Allow`), att en egen `HEAD`- eller `OPTIONS`-route går före det automatiska, och
   `OPTIONS *` (404).
6. Ett test visar att `build()` avvisar, med alla problem i samma undantag: mönster utan
   inledande `/`, tomt variabelnamn, dubbla variabelnamn, delsegment, catch-all som inte är
   sist, `%` i mönster, ogiltig metod, två routes med samma metod och form, och två mappers
   för samma undantagsklass. En router med fem felaktiga mönster och två konflikter ger ett undantag
   som nämner alla sju.
7. Ett tabelldrivet test med minst 25 felaktiga sökvägar över rå socket (eller
   `HttpClient` där klienten tillåter det) visar 400 och att handlern aldrig anropas, och
   täcker minst: `%` utan hexsiffror, `%G0`, ofullständig UTF-8, överlång kodning
   (`%C0%AF`), kodat surrogat (`%ED%A0%80`), `%2F`, `%2f`, `%5C`, `%00`, `%0A`, `%1B`,
   `%7F`, `.`, `..`, `%2e`, `%2E%2e`, `.%2e`, tomt segment `//`, och dubbelkodning (`%252F`
   avkodas till segmentet `%2F` som text och matchar då en variabel, inte två segment;
   skriv ut förväntan i testet).
8. Ett test visar queryparametrar: flera värden i ordning, `+` som mellanslag, `%2B` som
   `+`, namn utan `=`, tomt värde, `&&`, `=` i värdet, felaktig kodning (400), exakt 1 000
   par (200) och 1 001 par (400), och att parsningen görs högst en gång även när handlern
   frågar efter många parametrar.
9. Ett test visar typade parametrar: alla inbyggda `Param` med giltiga värden, och för
   varje inbyggd typ minst ett ogiltigt värde (bl.a. `"1.0"`, `" 5"` och `2147483648` för
   `INT`, `"TRUE"`/`"1"` för `BOOLEAN`, fel skiftläge för enum) som ger 400 med
   parameterns namn i problem-svaret; `requiredQuery` utan värde ger 400; `pathParam` med
   ett okänt namn ger 500.
10. Ett test visar filter: ordning (ytterst först, loggad i en lista), att ett filter kan
    svara utan att anropa `next`, ändra svaret (lägga till ett huvud), och lämna ett
    attribut som handlern läser via `AttributeKey`; att routerns filter körs för 404, 405,
    automatiskt `OPTIONS` och 400 från sökvägskontrollen; att en grupps filter bara körs
    för gruppens routes och innanför routerns filter.
11. Ett test visar felhantering: närmaste mapper i arvskedjan vinner oberoende av
    registreringsordning; en `WebException`-subtyp utan mapper ger sin status som
    problem-svar; en mapper för `WebException` kan ge en egen 404-sida; ett okänt
    `RuntimeException` ger 500 vars kropp varken innehåller undantagets meddelande eller
    stackspår; en mapper som själv kastar ger 500 utan rekursion; ett undantag från ett
    filter går till samma mappers; `RequestBodyException` (kropp över `maxBodySize`, chunked)
    ger kroppens status från `spola.http`.
12. Ett test visar problem-svaret: `Content-Type: application/problem+json`, att kroppen
    parsas med `Json.parse` och har `type`, `title`, `status`, `instance` (och `detail`
    bara när den finns), att extra medlemmar kommer med, och att `detail` kortas till högst
    2 000 tecken.
13. Ett test visar att ingen loggrad som `spola.web` skriver innehåller råa styrtecken,
    även när sökvägen eller ett parametervärde innehåller procentkodade `%0A`, `%0D` eller
    `%1B` och handlern kastar ett undantag vars meddelande innehåller dem.
14. Ett test med minst 1 000 routes visar att alla matchar rätt, och ett test med 200
    samtidiga klienter (virtuella trådar) mot samma router visar att alla får rätt svar
    och rätt attribut (inget läcker mellan förfrågningar).
15. `README.md` i `modules/web/` finns med exemplet och avvikelsetabellen enligt kraven.
16. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails
    grön), inga nya filtyper under `framework/`, ingen regel i `framework/REGLER.md` bruten.
17. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive uppdelningen av rad 08 i
    08a/08b, modulens beroenden, mönstersyntaxen, matchningsordningen och trie-uppslaget,
    strikt sökväg och avkodning, query-reglerna och gränsen, filtrens omfång och ordning,
    attribut som typade nycklar på en oföränderlig request, valet av mapper och
    problem-formatet.

## Utanför scope
- JSON-kroppar in och ut, `Content-Type`/`Accept`, 415/406, cookies, formulärkroppar och
  multipart (08b och senare).
- Validering av indata utöver typade parametrar (uppdrag 09).
- CORS, CSRF, autentisering och sessioner (uppdrag 10–11); de ska kunna byggas som filter.
- Statiska filer, mallar/vyer, `Range`, komprimering, server-sent events, WebSocket.
- Proxy-huvuden (`Forwarded`, `X-Forwarded-*`), access-logg.
- Koppling till `spola.config`/`spola.di` och uppstart (uppdrag 19).
- Ändringar i `spola.http` utöver små tillägg som uppdraget kräver (motivera i
  ARCHITECTURE.md; 07a:s och 07b:s tester ska vara oförändrade och gröna).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod, inga annoteringar eller
namnkonventioner för routes (allt registreras med vanlig Java-kod). Inga nya filtyper i
`framework/`. Testerna får inte bero på nätverk utanför `127.0.0.1` och får inte binda
fasta portar.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
