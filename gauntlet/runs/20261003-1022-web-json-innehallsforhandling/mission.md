Source: uppdrag/08b-web-json-och-innehallsforhandling.md

# Web del 2: JSON-kroppar, mediatyper och innehållsförhandling

## Mål
`spola.web` ska kunna ta emot och skicka JSON på samma sätt som `@RequestBody` och
`@ResponseBody` i Spring MVC, men med en uttrycklig `JsonCodec` från `spola.json.codec` i
stället för Jackson och reflektion. En handler läser en kropp med
`request.readJson(codec)` och svarar med `Responses.json(request, status, codec, value)`.
Felaktiga kroppar ger 400 med **alla** mappningsproblem i problem-svaret. Fel `Content-Type`
ger 415 och ett `Accept` som inte godtar JSON ger 406. Grunden är en strikt parser för
mediatyper (RFC 9110 §8.3.1) och för `Accept` med q-värden (RFC 9110 §12.5.1), som
applikationer också kan använda direkt för egen innehållsförhandling. Cookies och
formulärkroppar kommer i 08c.

## Bakgrund / kontext
Uppdrag 01–07 och 08a är klara. Läs `framework/REGLER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "JSON del 1", "JSON del 2",
"HTTP-server del 1" och "Web del 1". Alla tester skrivs med `spola.test`.

Det som finns efter 08a (se `PROGRESS.md`):
- `spola.web` med `requires transitive spola.http` och `requires transitive spola.json`.
  Där finns `Router` (en `spola.http.Handler`), `RouterBuilder`, `RouteGroup`, `WebHandler`,
  `WebRequest` (oföränderlig, med `http()`, `headers()`, `body()` som `InputStream` och
  `bytes()`), `Param`, `AttributeKey`, `Filter`, `ExceptionMapper`, `WebException` (status,
  titel, detalj, huvuden) och `Problem` (RFC 9457-record med `extensions` som `JsonObject`,
  `toResponse()`). Undantag mappas där de uppstår. `WebException` utan mapper blir sitt
  problem-svar. `RequestBodyException` går till `spola.http`, som svarar med kroppens status
  (t.ex. 413 över `HttpLimits.maxBodySize`) och stänger anslutningen. Övriga undantag blir
  500 utan detaljer och loggas escapat.
- `spola.json`: `Json.parse(InputStream/byte[], JsonLimits)` med `JsonLimits` (djup,
  stränglängd, tallängd, antal poster och `maxInputSize`, standard 4 MiB).
  `JsonParseException` har `description()`, `line()` och `column()`. Skrivaren har
  `Json.writeUtf8(value, OutputStream)`.
- `spola.json.codec`: `JsonCodec<T>` med `decode(JsonValue)`, `encode(T)`, `read` och
  `write`. `JsonMappingException.problems()` ger `JsonProblem(path, message)` i
  dokumentordning, högst 100 plus en sammanfattning. Meddelandena är säkra (utskrivbar ASCII,
  värden avkortade).
- `spola.http.Response` har `bytes`, `stream`, `text`, `status` och `withHeader`, men ingen
  JSON. Reserverade huvuden avvisas.

Rad 08 i `PLAN.md` är redan delad i 08a och 08b (se ARCHITECTURE.md, "Web del 1"). 08b var
tänkt att också omfatta cookies och formulärkroppar. Planeraren delar nu 08b ytterligare så
att varje uppdrag blir lagom stort:
- **08b (detta uppdrag):** mediatyper, `Accept`, JSON-kroppar in och ut, 415, 406 och
  problem-svar för kroppsfel.
- **08c:** cookies (`Cookie` in, `Set-Cookie` ut med `Secure`, `HttpOnly`, `SameSite`,
  `Max-Age`) och formulärkroppar (`application/x-www-form-urlencoded`). Multipart ligger
  utanför båda.

Erfarenhet från tidigare iterationer: allt som byggs av extern data (huvudvärden, delar av
kroppen, mappningsproblem) och hamnar i en loggrad eller ett problem-svar ska vara kort och
fritt från råa styrtecken. Gränser ska kontrolleras innan minnet hinner växa.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Modul:** allt läggs i `spola.web` (paketet `spola.web`). Inga nya `requires`, ingen
  reflektion, inga ändringar i `spola.http` eller `spola.json` utöver små tillägg som
  motiveras i ARCHITECTURE.md (08a:s, 07:s och 06:s tester ska vara oförändrade och gröna).
- **`MediaType` (record):** `type`, `subtype` och `parameters` (oföränderlig, ordnad
  `Map<String,String>`). Den har `parse(String)` och `of(type, subtype)`, konstanterna
  `APPLICATION_JSON`, `APPLICATION_PROBLEM_JSON`, `TEXT_PLAIN` och `ALL` (`*/*`),
  `includes(MediaType)` för jokertecken, `isJson()` (`application/json` eller
  `application/<något>+json`), `charset()` (`Optional<String>`) och `toString()` i
  kanonisk form.
  - Syntaxen följer RFC 9110 §8.3.1 strikt: `type "/" subtype *( OWS ";" OWS parameter )`,
    där typ, subtyp och parameternamn är `token` (§5.6.2) och värdet är `token` eller
    `quoted-string` (§5.6.4, med `\`-escape). Typ, subtyp och parameternamn görs till
    gemener. Parametervärden behåller skiftläget, utom `charset` som jämförs utan hänsyn
    till skiftläge.
  - Allt annat är ett fel (`IllegalArgumentException` med ett kort, escapat meddelande):
    saknad subtyp, tomma delar, `*` som typ med en konkret subtyp (`*/json`), en parameter
    utan `=`, samma parameternamn två gånger, styrtecken, icke-ASCII, ett oavslutat citat,
    mer än 64 parametrar och en indata över 1 024 tecken.
- **`Accept` (RFC 9110 §12.5.1):** `AcceptHeader.parse(String)` (eller ett motsvarande namn)
  ger en lista av mediaintervall med q-värden. `q` är `0`–`1` med högst tre decimaler
  (`q=1.0000`, `q=1.5` och `q=-1` är fel). Parametrar före `q` hör till intervallet, och
  parametrar efter `q` (accept-ext) ignoreras. Flera `Accept`-fält slås ihop med komma
  (§5.3). Högst 100 intervall. Kommatecken inne i ett citerat parametervärde delar inte.
  - Kvaliteten för en erbjuden typ bestäms av det **mest specifika** intervallet som
    innehåller den: med parametrar före utan parametrar, `type/subtype` före `type/*` och
    `type/*` före `*/*`. Ordningen i huvudet spelar ingen roll. `q=0` betyder "inte godtagbar".
  - `negotiate(List<MediaType> offered)` väljer den erbjudna typen med högst kvalitet över
    0. Vid lika kvalitet vinner serverns ordning. Saknas `Accept` helt godtas allt och den
    första erbjudna typen väljs.
  - Ett syntaktiskt felaktigt `Accept` ger 400 med detalj (inte 406). Det är en avvikelse
    från Spring och dokumenteras i README:s avvikelsetabell.
- **`WebRequest`, nya metoder:**
  - `contentType()` ger `Optional<MediaType>`. Ett felaktigt `Content-Type` ger 415.
  - `negotiate(MediaType... offered)` ger den valda `MediaType`. När inget godtas blir det
    `WebException` 406. Ett felaktigt `Accept` ger 400.
  - `readJson(JsonCodec<T>)` och `readJson(JsonCodec<T>, JsonLimits)` ger `T`.
- **Regler för `readJson` (motsvarar `@RequestBody`), kontrollerade i den här ordningen:**
  1. `Content-Encoding` som inte saknas och inte är `identity` ger 415 (RFC 9110 §8.4).
  2. Saknat `Content-Type` ger 415. Detsamma gäller en typ där `isJson()` är falskt, eller
     en `charset` som inte är UTF-8 (jämförs utan hänsyn till skiftläge; `"utf-8"` citerat
     godtas). 415-svaret har huvudet `Accept: application/json` (som Spring).
  3. En tom kropp ger 400 med detaljen `Request body is required`.
  4. Kroppen parsas strömmande med `Json.parse(InputStream, limits)`. Standard är
     `JsonLimits.DEFAULT`, och routerns standard kan ändras med
     `RouterBuilder.jsonLimits(JsonLimits)`.
     - En kropp större än `limits.maxInputSize()` ger 413. Läsningen avbryts vid gränsen.
       Hela kroppen läses aldrig in först.
     - `HttpLimits.maxBodySize` gäller som förut via `RequestBodyException`, och läsaren får
       inte svälja den.
     - Ett syntaxfel ger 400. Detaljen innehåller `description()`, rad och kolumn men
       ingen text ur kroppen.
     - Övriga överträdelser av `JsonLimits` (djup, antal poster, stränglängd) ger 400.
  5. `codec.decode` körs. `JsonMappingException` ger 400 med detaljen
     `Request body has N problem(s)` och den extra medlemmen `errors`: en array av
     `{"path": ..., "message": ...}` i dokumentordning, högst 100 poster (samma gräns som
     kodeken). Formatet `errors` med `path` och `message` är **det gemensamma felformatet**
     som uppdrag 09 (validering) ska återanvända. Det dokumenteras som ett beslut.
  - Kroppen kan bara läsas en gång. Ett andra `readJson` (eller `readJson` efter `body()`/
    `bytes()`) ger `IllegalStateException`, som är ett programmeringsfel och ger 500.
  - Alla dessa fel är `WebException` (eller subtyper) och går genom routerns mappers som
    andra fel.
- **`Responses` (ny final klass med statiska fabriker, motsvarar `@ResponseBody`):**
  - `json(WebRequest request, int status, JsonCodec<T> codec, T value)` och
    `json(WebRequest request, int status, JsonValue value)` förhandlar först mot `Accept`
    (`negotiate(APPLICATION_JSON)`, så 406 när JSON inte godtas). Svaret får
    `Content-Type: application/json` utan charset (RFC 8259 §11) och en UTF-8-kropp med
    `Content-Length`.
  - `created(WebRequest request, String location, JsonCodec<T> codec, T value)` ger 201 med
    `Location`. `noContent()` ger 204.
  - Ett `JsonMappingException` från `encode` (t.ex. ett `null` där kodeken inte tillåter det)
    är serverns fel och blir 500 utan detaljer. Det får aldrig bli en 400.
  - Problem-svar förhandlas inte. De skickas alltid som `application/problem+json`, även
    när `Accept` inte godtar det (som Spring Boot och RFC 9457).
  - Kroppen kodas och skrivs till en byte-array innan svaret returneras, så att ett fel i
    kodningen blir 500 och inte en avbruten ström.
- **`HEAD`** på en JSON-route ger samma `Content-Type` och `Content-Length` utan kropp
  (följer redan av 08a och `spola.http`; ska testas).

## Krav
- `MediaType`, `Accept`-tolkningen, `negotiate`, `contentType`, `readJson`,
  `RouterBuilder.jsonLimits` och `Responses` enligt besluten ovan.
- Allt nytt är oföränderligt och trådsäkert. Inget tillstånd delas mellan förfrågningar.
- Publikt API dokumenteras med Javadoc.
- `README.md` i `modules/web/` utökas med:
  - ett komplett CRUD-exempel med JSON in och ut (en `record` med kodek, `POST` som ger 201
    med `Location`, `GET` som ger 200, `PUT` som ger 200 och `DELETE` som ger 204) bredvid
    motsvarande Spring Boot-kod (`@RequestBody`, `@ResponseBody`/`ResponseEntity`,
    `produces`/`consumes`);
  - ett exempel på egen innehållsförhandling med `negotiate`;
  - nya rader i avvikelsetabellen: felaktigt `Accept` ger 400, `charset` måste vara UTF-8,
    `errors`-formatet, ingen automatisk förhandling för problem-svar, och att `produces`/
    `consumes` inte är en del av routingen.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. `modules/web/src/module-info.java` har inga nya `requires`. Huvudkoden importerar inget från
   `java.lang.reflect` eller `java.lang.invoke`.
3. Ett tabelldrivet test av `MediaType.parse` har minst 20 giltiga fall och minst 20
   ogiltiga. Det täcker gemener för typ, subtyp och parameternamn, `quoted-string` med
   escape, `charset` utan hänsyn till skiftläge, `+json`-suffix, `*/*` och `type/*`, och för
   de ogiltiga alla fel som räknas upp i besluten (bl.a. `*/json`, en dubbel parameter,
   styrtecken, icke-ASCII, ett oavslutat citat, 65 parametrar och 1 025 tecken).
4. Ett tabelldrivet test av `Accept` och `negotiate` har minst 20 fall. Det visar:
   - att det mest specifika intervallet bestämmer kvaliteten oberoende av ordningen;
   - `q=0` som utesluter en typ trots `*/*`;
   - att serverns ordning vinner vid lika kvalitet;
   - ett saknat `Accept`;
   - flera `Accept`-fält;
   - ett kommatecken i ett citerat värde;
   - accept-ext efter `q`;
   - ogiltiga q-värden (`1.5`, `-1`, `0.0001`, `abc`) som ger 400;
   - att 101 intervall ger 400.
5. Ett test över en riktig `HttpServer` (port 0, `127.0.0.1`) med `HttpClient` visar
   `POST`, `GET`, `PUT` och `DELETE` med JSON enligt README-exemplet. `POST` ger 201 med
   `Location` och kroppen. Svaren har `Content-Type: application/json` och rätt
   `Content-Length`. Kroppen går att läsa tillbaka med samma kodek. `HEAD` ger samma huvuden
   utan kropp.
6. Ett test visar 415, med `Accept: application/json` i svaret, för:
   - ett saknat `Content-Type`;
   - `text/plain`;
   - `application/xml`;
   - ett felaktigt `Content-Type`;
   - `charset=iso-8859-1`;
   - `Content-Encoding: gzip`.

   Samma test visar att `application/json; charset=UTF-8`, `application/json;charset="utf-8"`
   och `application/vnd.example+json` godtas.
7. Ett test visar 400 med problem-svar för:
   - en tom kropp;
   - ett syntaxfel, där detaljen innehåller rad och kolumn men inte en unik markörsträng
     från kroppen;
   - en kropp över djupgränsen.
8. Ett test visar att en kropp med minst tre mappningsfel på olika nivåer ger 400, och att
   `errors` innehåller alla tre med `path` och `message` i dokumentordning. En kropp med
   över 100 fel ger högst 100 poster i `errors` och en detalj som anger antalet. Ett
   felvärde med `%0A`/`\u001b` hamnar inte oescapat i någon loggrad.
9. Ett test visar storleksgränserna:
   - en kropp över `RouterBuilder.jsonLimits(...).maxInputSize()` (men under
     `maxBodySize`) ger 413 utan att hela kroppen buffras; mät med en kropp som är många
     gånger större än gränsen och skickas strömmande (chunked) och visa att svaret kommer;
   - en kropp över `HttpLimits.maxBodySize` ger fortfarande kroppens status från
     `spola.http`;
   - `readJson(codec, limits)` med egna gränser går före routerns.
10. Ett test visar att ett andra `readJson` i samma förfrågan ger 500 (via
    `IllegalStateException`), och att en mapper för `WebException` kan ge en egen
    respons för både 415 och 400 från `readJson`.
11. Ett test visar 406 från `Responses.json` för `Accept: text/html` och för
    `Accept: application/json;q=0, */*`. Det visar också 200 för `Accept: */*`,
    `application/*` och `application/json;q=0.5, text/html;q=0.9`. Ett problem-svar skickas
    som `application/problem+json` även med `Accept: text/html`.
12. Ett test visar att ett `encode`-fel i `Responses.json` ger 500 utan detaljer, inte 400.
13. Ett test med 200 samtidiga klienter (virtuella trådar) som postar olika JSON-kroppar
    visar att varje klient får tillbaka sin egen kropp.
14. `README.md` i `modules/web/` har CRUD-exemplet bredvid Spring Boot, förhandlingsexemplet
    och de nya avvikelseraderna enligt kraven.
15. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i
    `framework/REGLER.md` är bruten.
16. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. De beskriver:
    - uppdelningen 08b/08c;
    - `MediaType`-syntaxen och gränserna;
    - specificitetsregeln och oavgjorda fall i `negotiate`;
    - ordningen i `readJson` och dess statuskoder;
    - `errors`-formatet som gemensamt felformat inför 09;
    - strömmande parsning och 413;
    - att problem-svar inte förhandlas;
    - att `encode`-fel ger 500.

## Utanför scope
- Cookies, `Set-Cookie` och formulärkroppar (08c).
- Multipart, filuppladdning, `Content-Encoding` (gzip) i någon riktning, och strömmande
  JSON-svar (`Responses.json` buffrar).
- `produces`/`consumes` som en del av routingen (förhandlingen görs i handlern).
- Validering av indata utöver det kodeken gör (uppdrag 09).
- `Accept-Charset`, `Accept-Language`, ETag och villkorliga förfrågningar.
- CORS och säkerhetshuvuden (uppdrag 10–11).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion, inga annoteringar och inga namnkonventioner:
kodekar anges alltid uttryckligen. Inga nya filtyper i `framework/`. Testerna får inte bero
på nätverk utanför `127.0.0.1` och får inte binda fasta portar.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
