Source: uppdrag/07a-http-server-karna.md

# HTTP-server del 1: HTTP/1.1 på virtual threads med gränser mot missbruk

## Mål
En fristående modul `spola.http` med en HTTP/1.1-server som ersätter den inbäddade Tomcat
som Spring Boot startar: en virtuell tråd per anslutning, keep-alive och pipelining, strikt
parsning av förfrågningar enligt RFC 9112/9110, chunked kodning i båda riktningarna, och
gränser och tidsgränser så att en illasinnad klient inte kan ta slut på minne, trådar eller
anslutningar och inte kan smuggla förfrågningar. Parsningen tar emot extern data och är
säkerhetskritisk (REGLER §4). TLS kommer i uppdrag 07b och routing och den rikare
request/response-modellen i uppdrag 08; båda bygger på denna kärna.

## Bakgrund / kontext
Uppdrag 01–06 är klara: bygge och guardrails, testkörare (`spola.test`), DI-container
(`spola.di`), konfiguration (`spola.config`), loggning och hjälpare (`spola.util`),
builders och with-metoder (`spola.codegen`), integrationsprogrammet `spola.demo` och JSON
(`spola.json` med trädmodell, strikt parser, skrivare och `spola.json.codec`). Läs
`framework/REGLER.md`, `framework/PROGRESS.md` och `framework/ARCHITECTURE.md` innan du
börjar. Alla tester skrivs med `spola.test`. Varje modul och dess testingång läggs till
explicit i `MODULES` i `Build.java`.

Rad 07 i `PLAN.md` delas av planeraren i två uppdrag, eftersom protokollkärnan och TLS var
för sig är tillräckligt stora för fem rundor och båda är säkerhetskritiska:
- **07a (detta uppdrag):** HTTP/1.1 över vanlig TCP, keep-alive, pipelining, chunked,
  `Expect: 100-continue`, gränser, tidsgränser, start och mjukt stopp.
- **07b:** TLS (HTTPS) ovanpå samma kärna, med certifikat och nycklar från keystore och
  test som skapar sina certifikat själva (inga `.p12`/`.pem`-filer får checkas in).

Erfarenheter från 06a som gäller här också: guardrails tillåter bara `.java` och `.md` i
`framework/`, så testdata skrivs i testkoden. Ingen tredjeparts testsvit eller
smugglingskorpus får kopieras in (REGLER §1); testfallen skrivs utifrån RFC 9112 och
RFC 9110 med hänvisning till avsnitt. Felmeddelanden och loggrader får aldrig innehålla rå
indata med styrtecken (samma princip som `JsonParseException`).

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Egen implementation på `java.net.ServerSocket`/`Socket`, inte `jdk.httpserver`.**
  `com.sun.net.httpserver` är tillåten enligt REGLER §1, men dess gränser ställs via globala
  systemegenskaper, dess parsning är tolerant och går inte att göra strikt, och den saknar
  tidsgräns för långsamma klienter per förfrågan. En egen parser på JDK:ns socket-primitiver
  ger full kontroll, gränser som värden och testbarhet mot RFC:erna. Blockerande I/O på en
  virtuell tråd per anslutning (inte NIO-selektorer): koden blir rak och läsbar och skalar
  ändå till tiotusentals anslutningar.
- **Modulen har inga `requires` på andra ramverksmoduler.** Den loggar via
  `System.Logger` (loggernamn `spola.http`), som `spola.util` redan formaterar när en
  applikation konfigurerat loggning. Ingen JSON, DI eller config här; kopplingen görs av
  applikationen eller i uppdrag 08/19.
- **API (namnen kan justeras, men formen gäller):**
  - funktionellt gränssnitt `Handler` med
    `Response handle(Request request) throws Exception`.
  - `Request`: metod, request-target som det stod, rå sökväg och rå query (utan
    procentavkodning; avkodning och queryparametrar är uppdrag 08), version, huvuden och
    kropp. `Headers` är oföränderliga, skiftlägesokänsliga på namn, bevarar ordning och
    tillåter flera värden (`first(name)`, `all(name)`, `contains`).
  - Kroppen läses som en `InputStream` som är begränsad till förfrågans faktiska längd
    (Content-Length eller avkodad chunked) och till `maxBodySize`, plus en bekväm
    `bytes()` som läser allt inom gränsen. Kroppen läses lat; `100 Continue` skickas först
    när handlern börjar läsa kroppen.
  - `Response`: status, huvuden och en kropp som en sealed hierarki: tom, byte-array (med
    Content-Length) eller strömmande (en funktion som skriver till en `OutputStream`; skickas
    chunked för HTTP/1.1, avgränsad av att anslutningen stängs för HTTP/1.0). Bekväma
    fabriker som `Response.text(200, "...")` (UTF-8 med `Content-Type`) och
    `Response.status(204)`.
  - `HttpServer.start(options, handler)` returnerar en startad server; `port()` ger den
    faktiska porten (för port 0), `close()` (AutoCloseable) gör mjukt stopp.
  - `HttpServerOptions` (record) med adress, port, `HttpLimits` och tidsgränser.
- **Strikt parsning enligt RFC 9112:**
  - Raden avslutas med CRLF. Ett ensamt CR eller ett ensamt LF i request-raden eller
    huvudena avvisas med 400 (RFC 9112 §2.2 tillåter men kräver inte att LF accepteras;
    att avvisa stänger en känd smugglingsväg). Tomma rader (CRLF) före request-raden
    hoppas över (§2.2).
  - Metoden är en `token` (RFC 9110 §5.6.2); okända metoder går till handlern. Request-
    target i origin-form, absolute-form (värden i target gäller före `Host`, §3.2.2) eller
    asterisk-form (bara med `OPTIONS`). Authority-form (`CONNECT`) ger 501. Styrtecken,
    blanktecken och byte över 0x7E i target ger 400.
  - Version `HTTP/1.1` eller `HTTP/1.0`; annan korrekt formad version ger 505, felformad 400.
  - Fältnamn är `token`; blanktecken mellan namn och kolon ger 400 (§5.1); obs-fold
    (fortsättningsrad) ger 400 (§5.2); NUL, CR och LF i ett värde ger 400 (RFC 9110 §5.5).
    Inledande och avslutande blanktecken i värdet tas bort.
  - HTTP/1.1 kräver exakt ett `Host`-huvud; saknat eller fler än ett ger 400 (RFC 9112 §3.2).
- **Kroppens längd (RFC 9112 §6), skydd mot smuggling:** `Transfer-Encoding` och
  `Content-Length` i samma förfrågan ger 400 och stängd anslutning. `Transfer-Encoding` där
  enda kodningen inte är exakt `chunked` (skiftlägesokänsligt) ger 501 och stängd
  anslutning (ingen gzip/identity-stack). `Transfer-Encoding` i en HTTP/1.0-förfrågan ger
  400 och stängd anslutning. Flera `Content-Length` (som fält eller lista) ger 400 även när
  värdena är lika; värdet måste vara bara decimala siffror och rymmas i `long`.
  Chunk-storleken är hexadecimal utan tecken och utan blanktecken, med skydd mot spill;
  chunk-tillägg tillåts men raden är begränsad (gräns väljs och dokumenteras); trailers
  läses inom huvudgränserna och kastas. Varje fel i ramningen stänger anslutningen.
- **Gränser som värden (`HttpLimits`, record med `DEFAULT`):**
  - `maxRequestLineLength` 8 192 byte (överskridet: 414),
  - `maxHeaderSectionSize` 16 384 byte för alla huvuden tillsammans och `maxHeaderCount`
    100 (överskridet: 431),
  - `maxBodySize` 10 MiB (Content-Length över gränsen: 413 innan handlern anropas;
    chunked över gränsen: läsningen kastar och servern svarar 413 om svaret inte börjat),
  - `maxConnections` 10 000 samtidiga anslutningar (fler accepteras inte förrän en stängts,
    eller stängs direkt; välj och dokumentera),
  - tidsgränser: `headerTimeout` 10 s från första byte till sista huvudet (total tid, inte
    per läsning, så att en klient som skickar en byte i taget inte kan hålla anslutningen),
    `idleTimeout` 60 s mellan förfrågningar på en keep-alive-anslutning, `readTimeout` 30 s
    utan framsteg när kroppen läses, `writeTimeout` 30 s utan framsteg när svaret skrivs
    (en klient som slutar läsa kopplas ned; vanliga sockets har ingen skrivtidsgräns, så
    det kräver en vakt som stänger socketen), och `shutdownTimeout` 30 s (som Spring Boots
    `spring.lifecycle.timeout-per-shutdown-phase`).
  Standardvärdena motiveras i ARCHITECTURE.md (jämför med Tomcat och nginx).
- **Fel:** ett protokollfel ger ett kort svar i klartext med rätt statuskod och
  `Connection: close`, och anslutningen stängs. Ett undantag från handlern ger 500 utan
  stackspår eller undantagstext i svaret och loggas (escapat, utan rå indata). Har svaret
  redan börjat skrivas stängs anslutningen i stället (klienten ser ett avbrutet svar, aldrig
  ett svar som ser helt ut). Ett `Error` i handlern får inte döda servern eller
  acceptor-tråden.
- **Svaret:** servern sätter `Date` (RFC 9110 §6.6.1), `Content-Length` eller
  `Transfer-Encoding: chunked`, och `Connection: close` när den stänger. Handlern får inte
  sätta `Content-Length`, `Transfer-Encoding`, `Connection` eller `Date` själv (fabriken
  kastar `IllegalArgumentException`), och huvudnamn och -värden valideras (inga CR/LF, inget
  NUL: skydd mot response splitting). Status 100–199 från handlern avvisas (ingen upgrade i
  detta uppdrag). Inget `Server`-huvud. `HEAD` går till handlern; servern skickar huvudena
  (med samma `Content-Length` som för `GET` när kroppen är en byte-array) men ingen kropp.
  204 och 304 skickas utan kropp och utan `Content-Length`.
- **Keep-alive:** HTTP/1.1 håller anslutningen öppen om inte klienten skickar
  `Connection: close` eller ett fel inträffat. HTTP/1.0 stängs alltid efter svaret.
  Pipelinade förfrågningar besvaras i ordning. Läser handlern inte hela kroppen läser
  servern resten (inom `maxBodySize` och `readTimeout`) innan nästa förfrågan, annars
  stängs anslutningen.
- **Mjukt stopp:** `close()` slutar ta emot anslutningar, stänger lediga keep-alive-
  anslutningar direkt, låter pågående förfrågningar bli klara (deras svar får
  `Connection: close`) och avbryter det som återstår efter `shutdownTimeout`. `close()`
  returnerar först när alla anslutningstrådar är klara.

## Krav
- Ny modul `spola.http` under `framework/modules/http/`, utan `requires` på andra
  ramverksmoduler (`spola.test` bara för tester). Exporterar paketet `spola.http`.
- Server, parser, ramning, gränser, felhantering, keep-alive och mjukt stopp enligt
  besluten ovan.
- En virtuell tråd per anslutning; acceptor-tråden blockerar aldrig på en enskild klient.
- Trådsäkerhet: en `Handler` anropas samtidigt från många anslutningar; servern har inget
  delat föränderligt tillstånd utöver anslutningsräkning och registret för mjukt stopp.
- Ingen `java.lang.reflect` eller `java.lang.invoke` i huvudkoden, inga preview-funktioner.
- Tester startar servern på `127.0.0.1` port 0 och använder dels råa `Socket`s (för exakta
  bytes, felaktiga förfrågningar och långsamma klienter), dels `java.net.http.HttpClient`
  (för att visa att en riktig klient fungerar). Tidsgränserna sätts korta i testerna så att
  modulens tester tar högst cirka 30 sekunder.
- `README.md` i modulen: ett komplett exempel (starta, svara, läsa kropp, strömmande svar,
  stoppa) bredvid motsvarande Spring Boot-kod (inbäddad Tomcat, `@RestController` på
  lägsta nivå eller `HttpServlet`), och en tabell som mappar `HttpLimits`/tidsgränserna mot
  Spring Boots egenskaper (`server.max-http-request-header-size`,
  `server.tomcat.max-swallow-size`, `server.tomcat.connection-timeout`,
  `server.tomcat.keep-alive-timeout`, `server.tomcat.max-connections`,
  `server.shutdown=graceful`) med avvikelser i beteende.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och `spola.http` med sin
   testingång står i `MODULES` i `Build.java`.
2. Modulens huvudkod importerar inget från `java.lang.reflect` eller `java.lang.invoke`,
   `module-info.java` har inga `requires` på andra ramverksmoduler, och modulen använder
   inte `com.sun.net.httpserver`.
3. Ett test med `java.net.http.HttpClient` gör `GET`, `POST` med kropp (Content-Length),
   `POST` med strömmad kropp (som klienten skickar chunked) och `HEAD`, och kontrollerar
   status, huvuden och kropp, inklusive att `HEAD` ger samma `Content-Length` som `GET`
   och ingen kropp.
4. Ett test över rå socket visar keep-alive (tre förfrågningar på samma anslutning),
   pipelining (två förfrågningar i samma skrivning ger två svar i ordning),
   `Connection: close` från klienten, att HTTP/1.0 stängs efter svaret, och att en kropp
   som handlern inte läst hoppas över så att nästa förfrågan på anslutningen fungerar.
5. Ett tabelldrivet test med minst 45 felaktiga förfrågningar över rå socket, var och en
   med förväntad statuskod och en kommentar som hänvisar till avsnittet i RFC 9112 eller
   RFC 9110. De täcker minst: ensamt LF och ensamt CR, obs-fold, blanktecken före kolon,
   tomt fältnamn, NUL/CR i värde, saknat `Host`, två `Host`, felformad version, `HTTP/2.0`
   (505), styrtecken och mellanslag i target, asterisk-form med `GET`, authority-form,
   `Content-Length` + `Transfer-Encoding`, två `Content-Length` (lika och olika),
   `Content-Length` med `+`, `-`, blanktecken inne i värdet, hex och spill över `long`,
   `Transfer-Encoding: chunked, identity`, `gzip, chunked`, `xchunked`, `chunked` i
   HTTP/1.0, chunk-storlek med blanktecken, med `0x`, med spill, saknad CRLF efter
   chunk-data, för lång request-rad (414), för stora/för många huvuden (431) och
   Content-Length över `maxBodySize` (413). Varje fall ger exakt den förväntade statusen,
   `Connection: close`, en stängd anslutning, och handlern anropas aldrig.
6. Ett test visar att ett smuggelförsök (`Content-Length` + `Transfer-Encoding: chunked`
   med en andra förfrågan gömd i kroppen) aldrig leder till att den gömda förfrågan når
   handlern.
7. Ett test visar chunked i båda riktningarna: en chunked kropp med chunk-tillägg och
   trailers läses korrekt; ett strömmande svar skickas chunked till HTTP/1.1 (bytes jämförs
   exakt) och stängningsavgränsat till HTTP/1.0; en chunked kropp över `maxBodySize` ger 413.
8. Ett test visar `Expect: 100-continue`: `100 Continue` skickas när handlern läser kroppen,
   inte när handlern svarar utan att läsa den, och inte alls när `Content-Length` redan är
   över `maxBodySize` (då 413 direkt).
9. Ett test visar tidsgränserna med korta värden: en klient som skickar huvudena en byte i
   taget stängs efter `headerTimeout` (även om varje byte kommer innan någon per-läsning-
   gräns skulle löpa ut); en ledig keep-alive-anslutning stängs efter `idleTimeout`; en
   kropp som slutar komma stängs efter `readTimeout`; en klient som slutar läsa ett stort
   strömmande svar kopplas ned efter `writeTimeout` och handlerns tråd blir fri.
10. Ett test visar `maxConnections`: med gränsen 5 och fem öppna lediga anslutningar
    betjänas inte en sjätte förrän en av dem stängts (eller stängs direkt, enligt det
    dokumenterade beslutet), och servern fungerar normalt efteråt.
11. Ett test visar felhantering: ett `RuntimeException` från handlern ger 500 utan
    undantagets meddelande eller stackspår i svaret; ett `Error` från handlern dödar inte
    servern (nästa anslutning betjänas); ett undantag efter att ett strömmande svar börjat
    ger en avbruten anslutning, inte ett komplett chunked svar.
12. Ett test visar svarets validering: CR/LF eller NUL i huvudnamn eller -värde, ett
    reserverat huvud (`Content-Length`, `Transfer-Encoding`, `Connection`, `Date`) och
    status 101 ger `IllegalArgumentException`; 204 och 304 skickas utan kropp och utan
    `Content-Length`; `Date` finns i varje svar i formatet från RFC 9110 §5.6.7.
13. Ett test visar mjukt stopp: under en pågående långsam förfrågan anropas `close()`; nya
    anslutningar vägras, lediga keep-alive-anslutningar stängs, den pågående förfrågan får
    sitt fullständiga svar med `Connection: close`, och `close()` returnerar efter det. Ett
    andra test visar att en förfrågan som inte blir klar avbryts efter `shutdownTimeout`
    och att `close()` då returnerar.
14. Ett test kör 1 000 samtidiga klienter (virtuella trådar, var och en med keep-alive och
    flera förfrågningar) mot samma server och kontrollerar att alla svar är rätt.
15. Ett test visar att ingen loggrad eller felkropp som servern skriver innehåller rå
    styrtecken från en förfrågan (t.ex. en target eller ett huvud med ESC eller NUL).
16. `README.md` i `modules/http/` finns med exemplet och tabellen enligt kraven.
17. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails
    grön), inga nya filtyper under `framework/`, ingen regel i `framework/REGLER.md` bruten.
18. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive besluten om egen
    implementation i stället för `jdk.httpserver`, blockerande I/O på virtuella trådar,
    strikt CRLF, smuggelreglerna för kroppens längd, standardgränserna och
    tidsgränserna med motivering, skrivvakten, beteendet vid `maxConnections`, reserverade
    svarshuvuden, mjukt stopp och uppdelningen av rad 07 i 07a/07b.

## Utanför scope
- TLS/HTTPS (07b).
- Routing, sökvägsmönster, procentavkodning, queryparametrar, cookies, JSON-integration,
  felsidor och request/response-modellen för applikationer (uppdrag 08).
- HTTP/2, HTTP/3, WebSocket och `Upgrade` (101), server-sent events som eget API.
- Komprimering (gzip) av svar eller förfrågningar, `Range`-förfrågningar, statiska filer.
- Proxy-huvuden (`Forwarded`, `X-Forwarded-*`), access-logg.
- Koppling till `spola.config`/`spola.di` och uppstart (uppdrag 19).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod. Inga nya filtyper i
`framework/`. Ingen inkopierad tredjeparts testdata. Testerna får inte bero på nätverk
utanför `127.0.0.1` och får inte binda fasta portar.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
