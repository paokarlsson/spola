# Spec: HTTP-server del 1 (07a) — HTTP/1.1 på virtual threads med gränser mot missbruk

## Uppgift
Bygg en ny fristående modul `spola.http` (`framework/modules/http/`): en HTTP/1.1-server på
`ServerSocket` med en virtuell tråd per anslutning, keep-alive, pipelining, strikt parsning
enligt RFC 9112/9110, chunked i båda riktningarna, `Expect: 100-continue`, gränser och
tidsgränser som värden (`HttpLimits`, `HttpServerOptions`), skydd mot request smuggling och
response splitting, och mjukt stopp. Alla detaljer, designbeslut och krav står i
`mission.md` i denna katalog och är bindande (avsnitten "Designbeslut" och "Krav").

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

## Acceptanskriterier
(Övertagna oförändrade från `mission.md`.)

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

## Antaganden
- Namnen i API:t får justeras enligt mission.md ("namnen kan justeras, men formen gäller");
  avvikelser dokumenteras i README/ARCHITECTURE.
- Beteendet vid `maxConnections` (vänta med accept eller stäng direkt) väljs av byggaren och
  dokumenteras; testet i kriterium 10 följer det valda beteendet.
- Gränsen för chunk-tilläggsraden väljs av byggaren och dokumenteras.
- Modulens tester ska ta högst cirka 30 sekunder; tidsgränser i tester sätts korta.
- Testerna binder bara `127.0.0.1` port 0.
