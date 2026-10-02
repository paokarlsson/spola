# spola.http

HTTP/1.1-servern i Spola: en `ServerSocket`, en virtuell tråd per anslutning och blockerande
I/O. Modulen ersätter den inbäddade Tomcat som Spring Boot startar (`spring-boot-starter-web`
utan routing). Keep-alive, pipelining, strikt parsning enligt RFC 9112 och RFC 9110, chunked
kodning åt båda hållen, `Expect: 100-continue`, gränser och tidsgränser som värden, skydd mot
request smuggling och response splitting, och mjukt stopp. Inga annoteringar, ingen reflektion,
inga `requires` på andra Spola-moduler, och inte `com.sun.net.httpserver`.

Routing, sökvägsmönster, procentavkodning, queryparametrar, cookies och JSON hör till uppdrag
08; TLS till uppdrag 07b. Här finns bara kärnan: en `Handler` som får en `Request` och
returnerar en `Response`.

| Spring Boot (inbäddad Tomcat) | Spola |
|---|---|
| `SpringApplication.run(App.class)` startar Tomcat | `HttpServer.start(options, handler)` |
| `server.port`, `server.address` | `HttpServerOptions.withPort(...)`, `withAddress(...)` |
| `HttpServlet.service(req, resp)` / `@RestController` | `Handler`: `Response handle(Request request)` |
| `HttpServletRequest.getInputStream()` | `request.body()` (begränsad ström), `request.bytes()` |
| `ResponseEntity.ok().body(...)` | `Response.text(200, ...)`, `Response.bytes(200, typ, data)` |
| `StreamingResponseBody` | `Response.stream(200, typ, out -> ...)` |
| `server.shutdown=graceful` + `ConfigurableApplicationContext.close()` | `server.close()` (alltid mjukt) |
| `server.tomcat.*`, `server.max-http-request-header-size` | `HttpLimits`, `HttpTimeouts` |

## Ett komplett exempel

### Spring Boot

```java
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        // application.properties: server.port=8080, server.shutdown=graceful
        ConfigurableApplicationContext context = SpringApplication.run(App.class, args);
        // ... context.close() stoppar Tomcat mjukt
    }
}

@RestController
class HelloController {
    @GetMapping("/hello")
    String hello() {
        return "hello";
    }

    @PostMapping("/echo")
    String echo(@RequestBody byte[] body) {
        return "got " + body.length + " bytes";
    }

    @GetMapping("/numbers")
    ResponseEntity<StreamingResponseBody> numbers() {
        StreamingResponseBody stream = out -> {
            for (int i = 0; i < 3; i++) {
                out.write(("line " + i + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(stream);
    }
}
```

På lägsta nivån, utan Spring MVC, är det en `HttpServlet` i en inbäddad Tomcat:

```java
Tomcat tomcat = new Tomcat();
tomcat.setPort(8080);
Context context = tomcat.addContext("", null);
Tomcat.addServlet(context, "hello", new HttpServlet() {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("text/plain; charset=utf-8");
        resp.getWriter().write("hello " + req.getRequestURI());
    }
});
context.addServletMappingDecoded("/*", "hello");
tomcat.start();
// ... tomcat.stop();
```

### Spola

```java
import spola.http.*;

Handler handler = request -> switch (request.path()) {
    case "/hello" -> Response.text(200, "hello");
    case "/echo" -> {
        byte[] body = request.bytes();               // högst maxBodySize, annars RequestBodyException (413)
        yield Response.text(200, "got " + body.length + " bytes");
    }
    case "/numbers" -> Response.stream(200, "text/plain; charset=utf-8", out -> {
        for (int i = 0; i < 3; i++) {
            out.write(("line " + i + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();                             // en chunk per flush (HTTP/1.1)
        }
    });
    default -> Response.text(404, "not found");
};

var options = HttpServerOptions.DEFAULT                // loopback, port 8080
        .withPort(8080)
        .withLimits(HttpLimits.DEFAULT.withMaxBodySize(1 << 20))
        .withTimeouts(HttpTimeouts.DEFAULT.withIdleTimeout(Duration.ofSeconds(20)));

try (var server = HttpServer.start(options, handler)) {
    System.out.println("listening on port " + server.port());
    awaitShutdownSignal();                             // applikationens sak
}                                                      // close(): mjukt stopp
```

Det som skiljer:

- **Handlern är en lambda.** Ingen klass skannas, ingen annotering läses. Routing som kod
  kommer i uppdrag 08; här är `switch` på `request.path()` det enklaste.
- **Kroppen läses lat.** `request.body()` är en `InputStream` som slutar vid förfrågans längd
  (Content-Length eller avkodad chunked) och aldrig ger mer än `maxBodySize`. Den läses på
  handlerns tråd medan handlern (eller en `BodyWriter`) kör. Har klienten skickat
  `Expect: 100-continue` skickas `100 Continue` först när kroppen läses.
- **Servern äger ramningen.** `Content-Length`, `Transfer-Encoding`, `Connection` och `Date`
  sätts av servern; att sätta dem i en `Response` ger `IllegalArgumentException`. Inget
  `Server`-huvud skickas.
- **Fel:** ett undantag från handlern ger 500 utan meddelande eller stackspår, loggas
  (escapat) och anslutningen stängs. Har ett strömmande svar redan börjat avbryts
  anslutningen i stället, så att klienten aldrig ser ett svar som ser komplett ut.
- **Mjukt stopp är standard.** `close()` slutar ta emot anslutningar, stänger lediga
  keep-alive-anslutningar, låter pågående förfrågningar bli klara (med `Connection: close`)
  och avbryter resten efter `shutdownTimeout`. Bara första anropet gör jobbet: ett senare
  anrop från en handler returnerar direkt (så att dess svar kan gå ut), ett senare anrop från
  en annan tråd väntar tills det första är klart. En handler får alltså stoppa servern samtidigt
  som en shutdown hook gör det.
- **Adressen är loopback som standard.** Spring Boot lyssnar på alla adresser; i Spola väljer
  man det uttryckligen (`withAddress(InetAddress.getByName("0.0.0.0"))`).
- Acceptor-tråden är en plattformstråd som inte är daemon, så en startad server håller
  JVM:en igång tills `close()`, som Tomcat.

## Gränser och tidsgränser mot Spring Boot

| Spola | Standard | Spring Boot (Tomcat) | Standard där | Avvikelse i beteende |
|---|---|---|---|---|
| `HttpLimits.maxRequestLineLength` | 8 192 byte | `server.max-http-request-header-size` (gäller request-raden och huvudena tillsammans) | 8 KB | Egen gräns för request-raden; överskriden ger 414. Tomcat ger 400 för hela huvudet. |
| `HttpLimits.maxHeaderSectionSize` | 16 384 byte | `server.max-http-request-header-size` | 8 KB | Bara huvudraderna (utan request-raden); överskriden ger 431, Tomcat ger 400. Gäller också trailers i en chunked kropp. |
| `HttpLimits.maxHeaderCount` | 100 | ingen egenskap; Tomcats `maxHeaderCount` via en `TomcatConnectorCustomizer` | 100 | Överskriden ger 431 (Tomcat ger 400). |
| `HttpLimits.maxBodySize` | 10 MiB | `server.tomcat.max-swallow-size` (hur mycket oläst kropp Tomcat sväljer) och `spring.servlet.multipart.max-request-size` | 2 MB / 10 MB | En gräns för hela kroppen, läst eller inte. `Content-Length` över gränsen ger 413 innan handlern anropas; en chunked kropp över gränsen gör att läsningen kastar och servern svarar 413. Oläst kropp läses och kastas inom gränsen innan svaret skrivs (efter svaret för ett strömmande svar); går det inte får klienten kroppens status i stället, och anslutningen stängs. Tomcat har ingen generell kroppsgräns för en servlet som läser `getInputStream()` själv. |
| `HttpLimits.maxConnections` | 10 000 | `server.tomcat.max-connections` (+ `server.tomcat.accept-count`) | 8 192 (+ 100) | Samma beteende: vid gränsen slutar servern ta emot, nya anslutningar väntar i operativsystemets kö (Spolas kö är 1 024). Tomcat har dessutom en trådpool (`server.tomcat.threads.max` 200); Spola har en virtuell tråd per anslutning och ingen trådgräns. |
| `HttpTimeouts.headerTimeout` | 10 s | `server.tomcat.connection-timeout` | Tomcats standard, 60 s (20 s i Tomcats egen `server.xml`) | Total tid från första byte till sista huvudet. Tomcats tidsgräns gäller per läsning, så en klient som skickar en byte i taget kan hålla anslutningen. Svar: 408 och stängd anslutning. Gäller också hur länge en ny anslutning får vänta före första byten. |
| `HttpTimeouts.idleTimeout` | 60 s | `server.tomcat.keep-alive-timeout` | samma som `connection-timeout` | Stänger utan svar. |
| `HttpTimeouts.readTimeout` | 30 s | `server.tomcat.connection-timeout` (gäller också kroppen) | 60 s | Utan framsteg, per läsning (en kropp som kommer långsamt men stadigt är tillåten). Svar 408 om svaret inte börjat, sedan stängd anslutning. |
| `HttpTimeouts.writeTimeout` | 30 s | ingen (Tomcat har bara `connection-timeout` för NIO-skrivningar) | – | En vakttråd stänger socketen när en skrivning stått still längre än gränsen; handlerns `write` kastar då och tråden blir fri. |
| `HttpTimeouts.shutdownTimeout` | 30 s | `spring.lifecycle.timeout-per-shutdown-phase` med `server.shutdown=graceful` | 30 s | Spola stoppar alltid mjukt. Efter tidsgränsen stängs socketarna och trådarna avbryts; `close()` returnerar när alla anslutningstrådar är klara, eller senast 1 s efter avbrottet (en handler som ignorerar avbrottet hålls inte kvar). |
| `BodyStream.MAX_CHUNK_LINE` (fast) | 4 096 byte | Tomcats `maxExtensionSize` | 8 192 | Chunk-storleksraden inklusive tillägg; längre ger 400. Tilläggen läses aldrig. |

## Strikt parsning: vad som avvisas

Varje fel ger ett kort svar i klartext med statuskoden, `Connection: close`, och anslutningen
stängs. Handlern anropas inte (utom när felet sitter i en chunked kropp, som läses först när
handlern läser den; då kastar läsningen och servern svarar med kroppens status). Läser
handlern inte hela kroppen läser servern resten innan svaret skrivs, och är kroppen trasig
skickas kroppens status (400/408/413/431) med `Connection: close` i stället för handlerns svar.
Två undantag behåller handlerns svar och stänger anslutningen efter det: ett strömmande svar
(dess `BodyWriter` får läsa kroppen) och `Expect: 100-continue` där kroppen aldrig begärdes.

| Indata | Svar | Avsnitt |
|---|---|---|
| ensamt LF eller ensamt CR i request-raden, huvudena eller chunk-raderna | 400 | RFC 9112 §2.2 |
| fler än 8 tomma rader före request-raden | 400 | RFC 9112 §2.2 |
| obs-fold, blanktecken före första huvudet | 400 | RFC 9112 §5.2, §2.2 |
| blanktecken mellan fältnamn och kolon, tomt fältnamn, namn som inte är `token` | 400 | RFC 9112 §5.1, RFC 9110 §5.1 |
| styrtecken (NUL, ESC, DEL, ...) i ett fältvärde | 400 | RFC 9110 §5.5 |
| HTTP/1.1 utan `Host`, två `Host`, ogiltigt `Host` | 400 | RFC 9112 §3.2 |
| felformad version (`http/1.1`, `HTTP/1`, `HTTP/11.1`) | 400 | RFC 9112 §2.3 |
| annan korrekt formad version (`HTTP/2.0`, `HTTP/0.9`) | 505 | RFC 9110 §15.6.6 |
| styrtecken, blanktecken eller byte över 0x7E i target; tecken utanför RFC 3986 (`#`, `{`, trasig `%`) | 400 | RFC 9112 §3.2 |
| asterisk-form utan `OPTIONS`; authority-form med annan metod | 400 | RFC 9112 §3.2.4, §3.2 |
| `CONNECT` (authority-form) | 501 | RFC 9112 §3.2.3 |
| userinfo eller tom värd i absolute-form, annat schema än http/https | 400 | RFC 9110 §4.2.4, §4.2.1 |
| `Content-Length` och `Transfer-Encoding` | 400 | RFC 9112 §6.1, §6.3 |
| två `Content-Length` (även lika), lista, `+`, `-`, blanktecken, hex, spill över `long` | 400 | RFC 9112 §6.3, RFC 9110 §8.6 |
| `Transfer-Encoding` där `chunked` inte är sista kodningen (`chunked, identity`, `xchunked`, `identity`) | 400 | RFC 9112 §6.3 |
| `Transfer-Encoding` med `chunked` sist men fler kodningar (`gzip, chunked`, `chunked, chunked`) | 501 | RFC 9112 §6.1 |
| `Transfer-Encoding` i HTTP/1.0 | 400 | RFC 9112 §6.1 |
| `Expect` med annat än `100-continue` | 417 | RFC 9110 §10.1.1 |
| request-rad över `maxRequestLineLength` | 414 | RFC 9110 §15.5.15 |
| huvuden över `maxHeaderSectionSize` eller `maxHeaderCount` | 431 | RFC 9110 §5.4 |
| `Content-Length` över `maxBodySize`; chunked kropp över gränsen | 413 | RFC 9110 §15.5.14 |
| chunk-storlek med blanktecken, `0x`, tecken, spill; saknad CRLF efter data; trasigt tillägg | 400 | RFC 9112 §7.1, §7.1.1 |

Avvikelse från uppdragets text: uppdraget säger 501 för varje `Transfer-Encoding` som inte är
exakt `chunked`. RFC 9112 §6.3 kräver (MUST) 400 när `chunked` inte är den sista kodningen,
så Spola svarar 400 för `chunked, identity`, `xchunked` och `identity`, och 501 bara när
`chunked` är sist men andra kodningar står före. Anslutningen stängs i båda fallen; se
ARCHITECTURE.md.

## API i korthet

| Typ | Roll |
|---|---|
| `HttpServer` | `start(options, handler)`, `port()`, `address()`, `close()` (mjukt stopp) |
| `HttpServerOptions` | record: adress, port, `HttpLimits`, `HttpTimeouts`; `DEFAULT` och `withX` |
| `HttpLimits`, `HttpTimeouts` | records med `DEFAULT` och `withX` |
| `Handler` | `Response handle(Request request) throws Exception` |
| `Request` | `method()`, `target()`, `path()`, `query()`, `host()`, `version()`, `headers()`, `body()`, `bytes()` |
| `Headers` | oföränderliga, ordnade, skiftlägesokänsliga namn, flera värden: `first`, `all`, `contains`, `of`, `with` |
| `Response` | record: status, `Headers`, `ResponseBody`; `text`, `bytes`, `stream`, `status`, `withHeader` |
| `ResponseBody` | sealed: `Empty`, `Bytes`, `Streaming` |
| `BodyWriter` | `void writeTo(OutputStream out) throws IOException` |
| `RequestBodyException` | kroppen gick inte att läsa: `status()` 400, 408, 413 eller 431 |
| `HttpVersion` | `HTTP_1_0`, `HTTP_1_1` |

Avvikelser från namnen i uppdraget: tidsgränserna ligger i ett eget record, `HttpTimeouts`, i
`HttpServerOptions` (i stället för som fem fält direkt i optionerna), och `Request.host()`
finns för att absolute-form går före `Host` (RFC 9112 §3.2.2).

Loggning sker via `System.Logger` med namnet `spola.http` (`spola.util` formaterar den när
loggning är konfigurerad). Protokollfel loggas på `DEBUG` med en fast beskrivning, aldrig med
indata. Fel i handlern loggas på `ERROR` med en kopia av undantaget där klassnamn och
meddelande är escapade (styrtecken som `\x1b`), så att indata som hamnat i ett meddelande inte
kan förfalska loggrader.
