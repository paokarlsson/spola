# Spec: REST-klient för JSON-API:er på java.net.http (spola.client)

## Uppgift
Bygg en ny modul `spola.client`, motsvarigheten till Springs `RestClient`, ovanpå `java.net.http` och `spola.json`: JSON in och ut via codecs, standardhuvuden, tidsgränser, storleksgräns och typade fel med status, utan att läcka huvudvärden. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261014-0332-rest-klient/mission.md` (källa `uppdrag/14-rest-klient.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det. Uppdraget tillåter en ändring i `Build.java` (testlagret löser `requires static` på Spola-moduler).
