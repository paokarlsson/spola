# REST-klient för JSON-API:er (`spola.client`)

## Mål
En ny modul `spola.client` med `RestClient`, motsvarigheten till Spring Frameworks `RestClient`.
Det är en delbar, trådsäker klient ovanpå `java.net.http.HttpClient` och `spola.json`. Den
skickar en JSON-kropp med några huvuden och ger tillbaka ett typat svar via en `JsonCodec`. När
API:t svarar med något annat än 2xx ger den ett tydligt fel med status. Svaret är data utifrån,
så klienten har en tidsgräns för hela anropet och en storleksgräns för kroppen. Värdena i
huvudena (API-nycklar) syns aldrig i fel eller `toString`. Samma klient och en `HttpServer`
räcker för integrationstester mot appens egen router, som `@SpringBootTest(webEnvironment =
RANDOM_PORT)` med en `RestClient`. Det här är behovet "HTTP-klient för JSON-API:er" från appen
psykologen.

## Bakgrund / kontext
Uppdrag 01–13b är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-rest-klient.md`. Psykologen anropar Anthropics Messages
API och OpenAIs Chat Completions. Klienterna för de två API:erna stannar i appen, och Spola ger
den allmänna klienten.

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar. Läs särskilt "Bygget", "JSON del 1", "JSON del 2"
och "HTTP-server del 1". Alla tester skrivs med `spola.test`.

Det som finns och som uppdraget bygger på:
- `spola.json` (06a, 06b) har inga `requires`. Den har:
  - `Json.parse(byte[], JsonLimits)`: strikt RFC 8259 och UTF-8. `JsonLimits.DEFAULT` har
    `maxInputSize` 4 MiB, och det finns `withMaxInputSize`.
  - `Json.write`.
  - `JsonCodec<T>` med `encode`, `decode`, `read(byte[])` och `write`.
  - `JsonParseException`, vars meddelande är kort, utskrivbar ASCII.
  - `JsonMappingException` med alla problem och deras sökvägar.
- `spola.http` (07a, 07b) har `HttpServer.start(options, handler)` med port 0, `Handler`,
  `Request` (med `target()` och `headers()`) och `Response` (med `Response.stream` och
  `BodyWriter` för strömmande kroppar). Testerna i `spola.http` och `spola.web` använder
  `java.net.http.HttpClient` med HTTP/1.1 mot servern.
- `spola.web` (08a–13b) har `Router`, `Responses.json`, `WebRequest.readJson` och problem-svar
  (`application/problem+json`).
- `Build.java`: en ny modul läggs till i `MODULES` med sin testingång. En moduls tester körs i ett
  eget `ModuleLayer` (`runTests`) där bara modulen och `spola.test` är rötter. En Spola-modul som
  modulen bara `requires static` finns alltså inte i testlagret i dag.
- `MONSTER.md` har redan raden "Anropa ett annat HTTP-API | `RestClient` | Ja, ovanpå
  `java.net.http` med `spola.json`".
- Har tidigare uppdrag levererat andra namn än de som står här gäller de levererade.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Ny modul `spola.client`** (`modules/client/`, paketet `spola.client`). `module-info.java`
  har:
  - `requires transitive java.net.http`, eftersom det publika API:t använder `HttpHeaders`;
  - `requires transitive spola.json`;
  - `requires static spola.web`, **bara för testerna** (servern och routern i
    integrationstesterna);
  - `exports spola.client`.

  Huvudkoden använder inget från `spola.web`, `spola.http` eller `spola.validation`. Vid körning
  behöver modulen alltså bara `spola.json`, och den kan användas fristående. Klienten beror inte
  på servern.
- **Testlagret i `Build.java`:** en moduls testlager löser också de Spola-moduler som modulen
  `requires static`, så att testerna når dem vid körning. Annars löses `requires static` inte.
  Ingen annan Spola-modul kräver en Spola-modul med `static`, så de andra modulernas testlager
  blir som förut.
- **API.** Namnen kommer från behovet där det går.
  - `RestClient.builder()` ger en `RestClient.Builder` med:
    - `baseUri(URI)`, som krävs;
    - `defaultHeader(name, value)`;
    - `timeout(Duration)` för hela anropet, standard 30 s;
    - `connectTimeout(Duration)` för anslutningen, standard 10 s;
    - `maxResponseSize(int)` i byte, standard 4 MiB (`JsonLimits.DEFAULT.maxInputSize()`);
    - `build()`.
  - `RestClient` är final, oföränderlig, trådsäker och `AutoCloseable`. `get`, `post`, `put`,
    `patch` och `delete` tar en sökväg och ger en `RestRequest`. Dessutom finns `close()`.
  - `RestRequest` är oföränderlig: varje metod ger en ny. Samma förfrågan kan skickas flera gånger
    och delas mellan trådar. Den har:
    - `header(name, value)`;
    - `json(T value, JsonCodec<T> codec)` och `json(JsonValue)`. Ett nytt `json(...)` ersätter
      kroppen;
    - de avslutande `retrieve(JsonCodec<T>)` som ger `T`, `retrieveJson()` som ger `JsonValue`
      och `retrieveBodiless()` som ger 2xx-statusen som `int` (för 204 och liknande).

    Varje avslutande anrop skickar en ny förfrågan och blockerar den anropande tråden. Det är
    vanlig blockerande stil och fungerar på virtuella trådar.
  - Undantagen följer Springs hierarki. `RestClientException` är en abstrakt, `sealed`
    `RuntimeException` med `method()` och `uri()`. Den har två slutliga underklasser:
    - `RestClientResponseException`: ett svar kom men går inte att använda. Det gäller status
      utanför 2xx, kropp över gränsen, tom kropp, kropp som inte är JSON och JSON som inte passar
      kodeken. Metoderna är `status()`, `headers()` (`java.net.http.HttpHeaders`) och
      `bodyExcerpt()`.
    - `RestClientTransportException`: inget användbart svar kom. Det gäller nekad anslutning, fel
      i TLS, avbruten anslutning, felaktigt svar, tidsgräns och avbruten tråd. Metoden är
      `timedOut()`.

    Konstruktorerna är inte publika.
  - **Avvikelse från behovet.** Behovet fångar `RestClientException` och läser `status()` och
    `bodyExcerpt()`. En tidsgräns eller en nekad anslutning har ingen status, så de metoderna
    finns på `RestClientResponseException`. Det är samma uppdelning som i Spring, där
    `getStatusCode()` finns på `RestClientResponseException` och inte på `RestClientException`.
    `catch (RestClientException e)` fångar fortfarande alla fel, och en `switch` över den är
    uttömmande.
- **URI.**
  - `baseUri` är absolut, har schemat `http` eller `https` och en värd, och saknar userinfo,
    query och fragment. Sökvägen får vara tom eller till exempel `/api`. Ett avslutande `/` tas
    bort.
  - Sökvägen i `post(path)` och de andra metoderna börjar med `/`. Den är en giltig URI-sökväg
    med valfri query och utan fragment.
  - Sökvägen läggs **som text** efter basens sökväg: `https://h/api` + `/v1/x` blir
    `https://h/api/v1/x`, till skillnad från `URI.resolve`. Inget kodas och det finns inga mallar,
    så sökvägen skickas som den står.
  - Förfrågan går därmed alltid till basens schema, värd och port.
- **HTTP/1.1.** Det är Springs standard (`SimpleClientHttpRequestFactory`) och den version som
  `spola.http` talar.
- **Omdirigeringar följs aldrig** (`Redirect.NEVER`). En 3xx ger ett
  `RestClientResponseException`, så huvudena med nyckeln skickas aldrig vidare till en annan
  värd.
- **Huvuden.**
  - Namnet är en `token` enligt RFC 9110.
  - Värdet består av synlig ASCII, mellanslag och tab. Det får vara tomt men får inte börja eller
    sluta med mellanslag eller tab.
  - Följande namn avvisas oavsett skiftläge: `Connection`, `Content-Length`, `Content-Type`,
    `Expect`, `Host`, `Keep-Alive`, `Proxy-Connection`, `TE`, `Trailer`, `Transfer-Encoding` och
    `Upgrade`. Klienten styr ramar och anslutning, och `json(...)` sätter `Content-Type`.
  - Samma namn två gånger i `defaultHeader` är ett fel, också i olika skiftläge.
  - `header` på en förfrågan ersätter ett standardhuvud med samma namn, och ett tidigare `header`
    med samma namn.
  - `Accept: application/json` skickas om varken standardhuvudena eller förfrågan har ett
    `Accept`.
  - `json(...)` kodar värdet direkt och sätter `Content-Type: application/json`. Ett fel från
    kodeken släpps igenom oförändrat, och inget skickas.
- **Fel i uppsättningen.**
  - `build()` samlar alla problem i ett `IllegalArgumentException` med en rad per problem:
    saknad eller felaktig `baseUri`, felaktigt eller förbjudet huvud, samma huvud två gånger, en
    tidsgräns som inte är positiv och `maxResponseSize` under 1.
  - `header(...)` och en felaktig sökväg ger `IllegalArgumentException` direkt vid anropet.
  - `null` som argument ger `NullPointerException`.
  - Inget meddelande innehåller ett huvudvärde eller sökvägens query.
- **Tidsgräns.** `timeout` är en total deadline för hela anropet: anslutning, TLS, förfrågan,
  statusrad, huvuden **och hela kroppen**. Går den ut avbryts förfrågan och anslutningen stängs.
  Klienten kastar då `RestClientTransportException` med `timedOut()`. `connectTimeout` gäller
  dessutom för anslutningen.
- **Storleksgräns.** `maxResponseSize` gäller kroppen i alla avslutande anrop.
  - Ett `Content-Length` över gränsen ger fel utan att kroppen läses.
  - En längre kropp slutar läsas vid gränsen, också när den är chunked eller aldrig tar slut.
  - Felet är ett `RestClientResponseException` med statusen.
  - JSON tolkas med `JsonLimits.DEFAULT.withMaxInputSize(maxResponseSize)`.
  - `retrieveBodiless()` läser kroppen inom gränserna och kastar den.
- **Svar med 2xx.** Kroppen tolkas som JSON (UTF-8, strikt) oavsett `Content-Type`. Det är en
  avvikelse från Spring, som väljer konverterare efter `Content-Type`. En tom kropp ger fel i
  `retrieve` och `retrieveJson`. Orsaken (`JsonParseException` eller `JsonMappingException`) blir
  `getCause()`.
- **Svar utanför 2xx.** Klienten läser högst de första 8 KiB av kroppen till utdraget. Sedan
  stänger den svaret utan att vänta på resten.
- **`bodyExcerpt()`.**
  - Kroppen avkodas som UTF-8. Ogiltiga byte blir U+FFFD, också en ofullständig sekvens sist.
  - Utdraget är de första högst 1 000 tecknen (`char`) av kroppen. Det delar aldrig ett
    surrogatpar.
  - Efter kortningen skrivs följande tecken som `\uXXXX`: styrtecken (U+0000–U+001F och
    U+007F–U+009F), U+2028, U+2029, bidi-tecken (U+200E, U+200F, U+202A–U+202E och U+2066–U+2069)
    och ensamma surrogat. Bokstäver utanför ASCII behålls.
  - Ett kortat utdrag slutar med `...`.
  - En tom kropp ger en tom sträng.
- **Meddelanden.**
  - `getMessage()` är en rad utskrivbar ASCII på högst 1 000 tecken. Den innehåller metoden,
    URI:n utan query och fragment, och vad som gick fel. För svarsfel innehåller den också
    statusen och början av utdraget, med tecken utanför ASCII som `\uXXXX`.
  - Inga huvudvärden och ingen förfrågningskropp finns i något meddelande, någon `toString()`
    eller någon orsak som modulen skapar.
  - `RestClient.toString()` och `RestRequest.toString()` visar bara namnen på huvudena.
- **Avbrott.** Avbryts den anropande tråden medan den väntar avbryts förfrågan. Avbrottsflaggan
  sätts igen och klienten kastar `RestClientTransportException` med `InterruptedException` som
  orsak.
- **`close()`** stänger den underliggande `HttpClient`. Den väntar på pågående anrop, som vart
  och ett har sin tidsgräns. Efter `close()` ger ett avslutande anrop `IllegalStateException`.
  Ett andra `close()` gör ingenting.
- **Ingen loggning** i modulen.

## Krav
- Modulen enligt besluten, med Javadoc på allt publikt API. Javadoc beskriver beteendet,
  standardvärdena, gränserna och avvikelserna.
- `Build.java`: `spola.client` i `MODULES` med testingången `spola.client.ClientTests`, och
  testlagret enligt beslutet.
- `README.md` i `modules/client/` visar Spring-koden bredvid Spola-koden för två fall:
  1. Behovets anrop till Anthropic och OpenAI, och felhanteringen. Spring-sidan använder
     `RestClient.builder().baseUrl(...).defaultHeader(...)`,
     `post().uri(...).contentType(APPLICATION_JSON).body(...).retrieve().body(...)` och
     `RestClientResponseException`.
  2. Ett integrationstest mot en egen router bredvid `@SpringBootTest(webEnvironment =
     RANDOM_PORT)`.

  Exemplen är kompilerade testkällor som ett test jämför med README. README har också en
  jämförelsetabell och en avvikelsetabell. Avvikelsetabellen tar minst upp:
  - undantagshierarkin;
  - att `Content-Type` inte styr tolkningen;
  - att omdirigeringar inte följs;
  - HTTP/1.1;
  - att sökvägen skickas utan kodning och utan mallar;
  - att det inte finns några cookies;
  - att det inte går att ange en egen `SSLContext` eller proxy;
  - att `Content-Type` inte kan sättas.
- Modulkatalogen i `MONSTER.md` och modultabellen i rotens `README.md` får en rad för
  `spola.client`.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0. Testerna körs via
   `spola.client.ClientTests`.
2. Modulens gränser:
   - `modules/client/src/module-info.java` har exakt `requires transitive java.net.http`,
     `requires transitive spola.json`, `requires static spola.web` och `exports spola.client`. Ett
     test fäster det.
   - Ett test visar att huvudkoden inte importerar något från `spola.web`, `spola.http`,
     `spola.validation`, `java.lang.reflect` eller `java.lang.invoke`.
   - `git diff` av `Build.java` visar bara den nya raden i `MODULES` och det som testlagret
     behöver. Inga andra moduler ändras under `src/` eller `test/`.
3. Behovets fall med Anthropic. En testkälla innehåller behovets kod, med avvikelsen `catch
   (RestClientResponseException e)`. Ett test kör koden mot en `HttpServer` (127.0.0.1, port 0)
   som spelar Anthropic. Servern kontrollerar metoden, sökvägen, `x-api-key`,
   `anthropic-version`, `Content-Type: application/json`, `Accept: application/json` och att
   kroppen är det kodade `MessagesRequest`. Testet visar att:
   - 200 med JSON ger ett avkodat `MessagesResponse`;
   - 400 med Anthropics felformat ger ett `RestClientResponseException` med `status()` 400 och ett
     `bodyExcerpt()` som innehåller felets typ och text;
   - loggraden från behovet innehåller statusen och utdraget men inte nyckeln;
   - 500 och 529 ger ett `RestClientResponseException` med den statusen;
   - `timeout(Duration.ofSeconds(60))` från behovet godtas.
4. Behovets fall med OpenAI. Klienten har `defaultHeader("Authorization", "Bearer " + key)` och
   anropar med `json(JsonValue)` och `retrieveJson()`. Servern är en `HttpServer` som
   kontrollerar huvudet och kroppen. Testet visar att svaret är trädet som servern skickade.
5. Integrationstest mot en router. Ett test bygger en `Router` med `spola.web` som har fyra
   vägar:
   - en GET som svarar med JSON;
   - en POST som läser JSON med `readJson` och svarar 201;
   - en DELETE som svarar 204;
   - en väg som ger 400 med `errors`.

   Testet startar routern på en `HttpServer` med port 0. Det visar att följande fungerar:
   - `get(...).retrieve(codec)`;
   - `post(...).json(...).retrieve(codec)`;
   - `delete(...).retrieveBodiless()`, som ger 204;
   - 404, med problem-svaret i `bodyExcerpt()`;
   - 400.

   Testet är README:s andra exempel.
6. Svar som inte går att använda. Varje fall ger ett `RestClientResponseException` med rätt
   status och, där det finns en orsak, rätt `getCause()`:
   - 301, 302, 307 och 308 till en annan lokal server ger ett undantag med statusen, och den
     andra servern får ingen förfrågan;
   - 200 med tom kropp, i både `retrieve` och `retrieveJson`;
   - 200 med HTML;
   - 200 med trasig JSON;
   - 200 med JSON som inte passar kodeken, där orsaken är ett `JsonMappingException` med sökvägen.

   Två fall till ger inget undantag:
   - 200 med `text/plain` och giltig JSON ger värdet, eftersom `Content-Type` inte styr;
   - `retrieveBodiless()` ger 200, 201 och 204. För 404 ger den ett undantag.
7. Storleksgränsen, med `maxResponseSize(1024)`:
   - en kropp på exakt 1 024 byte går igenom;
   - en kropp på 1 025 byte ger ett undantag, både med `Content-Length` och chunked;
   - `Content-Length: 10000000000` ger ett undantag utan att servern behöver skicka kroppen;
   - en chunked kropp utan slut ger ett undantag inom 2 s.

   Med `timeout` 30 s ger dessutom ett felsvar (500) med en kropp utan slut ett
   `RestClientResponseException` inom 2 s. Utdraget är då högst 1 000 tecken plus `...`.
8. Tidsgränsen, med `timeout` 500 ms. Två servrar ger båda `RestClientTransportException` med
   `timedOut()` inom 2 s:
   - en server som tar emot förfrågan och aldrig svarar;
   - en server som skickar statusrad och huvuden och sedan en byte var 100:e ms utan slut.

   I båda fallen ser servern att anslutningen stängs inom 5 s.
9. Transportfel. Följande fall ger `RestClientTransportException` utan `timedOut()`:
   - nekad anslutning (en stängd port);
   - en server som skickar skräp i stället för HTTP;
   - en server som stänger mitt i en kropp med `Content-Length`.

   En `https`-bas mot en lokal server som inte talar TLS visar att klienten börjar med ett
   TLS-handslag (första byten är 0x16). Anropet ger `RestClientTransportException`.

   En tråd som avbryts medan den väntar på ett svar får `RestClientTransportException` inom 2 s.
   Orsaken är `InterruptedException`, och avbrottsflaggan är satt.
10. Utdraget och meddelandena. Tabelltester för `bodyExcerpt()` täcker:
    - ASCII, svenska tecken och emoji;
    - 1 000 och 1 001 tecken;
    - ett surrogatpar vid gränsen;
    - ogiltig UTF-8 och en ofullständig sekvens sist;
    - varje tecken i escapelistan, också CR och LF.

    För varje sorts fel visar ett test att `getMessage()` är en rad utskrivbar ASCII på högst
    1 000 tecken. Den innehåller metoden och URI:n utan query, och för svarsfel också statusen.
11. Hemligheter. Testet lägger markörer i `x-api-key`, i `Authorization` och i en query i
    sökvägen. Ingen markör syns i:
    - `RestClient.toString()` eller `RestRequest.toString()`;
    - `getMessage()`, `toString()` eller det utskrivna stackspåret (med orsaker) för varje sorts
      fel i kriterierna 6–9;
    - `IllegalArgumentException` från `build()` och från `header(...)` med ett felaktigt värde
      som innehåller en markör.

    Markören i queryn finns i `uri()`.
12. Uppsättningen. Ett test per problem i `build()`:
    - saknad `baseUri`;
    - en `baseUri` som har schemat `ftp:`, är relativ, saknar värd eller har userinfo, query eller
      fragment;
    - ett felaktigt huvudnamn;
    - ett värde med CR, LF, NUL eller tecken utanför ASCII, och ett värde med mellanslag först
      eller sist;
    - varje förbjudet namn i blandat skiftläge;
    - samma namn två gånger i olika skiftläge;
    - tidsgräns noll och negativ;
    - `maxResponseSize(0)`.

    Ett test med flera problem ger en rad per problem. `header(...)` med samma fall ger
    `IllegalArgumentException` vid anropet. Det gör också en sökväg som inte börjar med `/`, har
    ett fragment eller har ogiltiga tecken.
13. Förfrågan. Tester visar att:
    - `Accept: application/json` skickas som standard, och att `defaultHeader("Accept", ...)` och
      `header("Accept", ...)` ersätter det;
    - `header` ersätter ett standardhuvud med samma namn i annat skiftläge;
    - `get` och `delete` utan kropp saknar `Content-Type`;
    - `baseUri` med sökväg (`/api` och `/api/`) och en sökväg med query ger rätt `Request.target()`
      hos servern;
    - `//annan.example/x` går till basens värd;
    - samma `RestRequest` kan skickas två gånger;
    - ett fel från kodeken i `json(...)` släpps igenom, och servern får ingen förfrågan.
14. Samtidighet. 200 virtuella trådar delar en `RestClient` och skickar var sin kropp. Varje tråd
    får sitt eget svar.
15. `close()`. Efter `close()` ger ett avslutande anrop `IllegalStateException`. Ett andra
    `close()` kastar inte.
16. En testkälla har en `switch` över en fångad `RestClientException` med de två underklasserna
    och utan `default`, och den kompilerar.
17. README-exemplen är kompilerade testkällor, och ett test jämför dem med
    `modules/client/README.md`.
18. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n och Spola-modulerna
    (guardrails är gröna). Inga nya filtyper finns under `framework/`, och ingen regel i
    `framework/REGLER.md` är bruten.
19. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.
    - `PROGRESS.md` beskriver det levererade och säger att behovet `2026-10-13-rest-klient` är
      levererat. Den listar kända begränsningar, minst dessa:
      - sökvägen skickas som den står, så värden utifrån måste kodas av appen;
      - det finns inga cookies, ingen egen `SSLContext` och ingen proxy;
      - bara HTTP/1.1;
      - omdirigeringar följs inte;
      - `Content-Type` styr inte tolkningen;
      - svarshuvuden finns bara på `RestClientResponseException`;
      - ett felsvar läses bara till de första 8 KiB;
      - samma tidsgräns gäller alla anrop från en klient;
      - `close()` väntar på pågående anrop.
    - `ARCHITECTURE.md` har ett avsnitt "REST-klient (`spola.client`)" med besluten ovan, och en
      punkt under "Bygget" om testlagret och `requires static`.
    - Modulkatalogen i `MONSTER.md` och modultabellen i rotens `README.md` har en rad för
      `spola.client`.

## Utanför scope
- Mallar och kodning av sökvägsvariabler (`uri("/items/{id}", id)`), och en byggare för query.
- Cookies (`CookieManager`) och sessioner mellan anrop.
- Egen `SSLContext`, truststore, mTLS, proxy, HTTP/2 och komprimering.
- Omförsök (Spring Retry), cirkelbrytare och hastighetsbegränsning.
- Strömmande svar (server-sent events), multipart, formulär och andra kroppar än JSON (`String`,
  `byte[]`).
- Svarshuvuden från lyckade anrop (`toEntity`, `exchange`).
- Tidsgräns per förfrågan, egna `JsonLimits`, interceptorer, loggning och mätvärden.
- Klienterna för Anthropic och OpenAI, som är appspecifika.
- Release och ny version, som görs för hand.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion och inga
namnkonventioner. Vid körning används bara `java.net.http` och `spola.json`. Testerna anropar
inga externa tjänster, och alla servrar är lokala (127.0.0.1).

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
