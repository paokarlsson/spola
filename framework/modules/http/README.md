# spola.http

HTTP/1.1-servern i Spola: en `ServerSocket`, en virtuell tråd per anslutning och blockerande
I/O. Modulen ersätter den inbäddade Tomcat som Spring Boot startar (`spring-boot-starter-web`
utan routing). Keep-alive, pipelining, strikt parsning enligt RFC 9112 och RFC 9110, chunked
kodning åt båda hållen, `Expect: 100-continue`, gränser och tidsgränser som värden, skydd mot
request smuggling och response splitting, mjukt stopp, och HTTPS (TLS 1.3/1.2 med PKCS#12-
eller PEM-material, valfria klientcertifikat). Inga annoteringar, ingen reflektion,
inga `requires` på andra Spola-moduler, och inte `com.sun.net.httpserver`.

Routing, sökvägsmönster, procentavkodning, queryparametrar, cookies och JSON hör till uppdrag
08. Här finns bara kärnan: en `Handler` som får en `Request` och
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
| `server.ssl.*` | `HttpServerOptions.withTls(TlsOptions...)`, se [HTTPS](#https-tls) |

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
- En startad server håller JVM:en igång tills `close()` är klart, som Tomcat, även när `main`
  har returnerat: en egen plattformstråd som inte är daemon väntar på det. Anropar en handler
  `close()` (en `/shutdown`-endpoint) lever JVM:en tills också den handlerns svar har gått ut
  (högst till `shutdownTimeout`, minst 1 s efter att `close()` returnerat).

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

## HTTPS (TLS)

TLS-protokollet är JDK:ns (`javax.net.ssl`, `SSLSocket`). Spola gör konfigurationen,
inläsningen och kontrollen av nycklar och certifikat, och kopplar TLS till anslutningens
livscykel: handslaget körs på anslutningens egen virtuella tråd (aldrig på tråden som tar emot
anslutningar) inom `HttpTimeouts.handshakeTimeout` räknat från accept, och därefter fungerar
allt som för vanlig HTTP (parsning, gränser, keep-alive, pipelining, `100-continue`,
skrivvakten, mjukt stopp). En server lyssnar antingen med eller utan TLS; vill du ha båda (t.ex.
omdirigering från 80 till 443) startar du två servrar. Ingen automatisk omdirigering och ingen
HSTS (hör till senare uppdrag).

### PKCS#12

Spring Boot (`application.properties`):

```properties
server.port=8443
server.ssl.enabled=true
server.ssl.key-store=/etc/app/server.p12
server.ssl.key-store-type=PKCS12
server.ssl.key-store-password=${KEYSTORE_PASSWORD}
server.ssl.key-alias=server
```

Spola:

```java
char[] password = System.getenv("KEYSTORE_PASSWORD").toCharArray();
var tls = TlsOptions.pkcs12(Path.of("/etc/app/server.p12"), password, "server"); // läses och kontrolleras här
java.util.Arrays.fill(password, '\0'); // Spola har redan kopierat och nollställt sin kopia
var options = HttpServerOptions.DEFAULT.withPort(8443).withTls(tls);
try (var server = HttpServer.start(options, request -> Response.text(200, "hello " + request.scheme()))) {
    ...
}
```

Utan alias krävs att keystoren har exakt en privat nyckel; annars talar felet om vilka alias
som finns.

### PEM (t.ex. Let's Encrypt)

Spring Boot:

```properties
server.ssl.certificate=/etc/letsencrypt/live/example.com/fullchain.pem
server.ssl.certificate-private-key=/etc/letsencrypt/live/example.com/privkey.pem
```

Spola:

```java
var tls = TlsOptions.pem(Path.of("/etc/letsencrypt/live/example.com/fullchain.pem"),
        Path.of("/etc/letsencrypt/live/example.com/privkey.pem"));
var options = HttpServerOptions.DEFAULT.withAddress(InetAddress.getByName("0.0.0.0"))
        .withPort(443).withTls(tls);
```

Kedjan ska börja med serverns certifikat, följt av utfärdarna i ordning (som `fullchain.pem`).
Nyckeln ska vara okrypterad PKCS#8 (`-----BEGIN PRIVATE KEY-----`): RSA, EC, Ed25519 eller
Ed448. En krypterad nyckel (`ENCRYPTED PRIVATE KEY`) eller en nyckel i det äldre formatet
(`RSA PRIVATE KEY`, `EC PRIVATE KEY`) avvisas med ett meddelande som säger hur den konverteras:

```
openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem
```

PEM läses utan JDK 25:s PEM-API (preview, JEP 470): ramarna parsas strikt av Spola och
innehållet läses med `CertificateFactory` och `KeyFactory`.

### Klientcertifikat (mTLS)

Spring Boot:

```properties
server.ssl.client-auth=need
server.ssl.trust-store=/etc/app/client-ca.p12
server.ssl.trust-store-password=${TRUSTSTORE_PASSWORD}
```

Spola:

```java
var tls = TlsOptions.pkcs12(Path.of("/etc/app/server.p12"), password)
        .withClientAuth(ClientAuth.NEED, TlsTrust.pkcs12(Path.of("/etc/app/client-ca.p12"), trustPassword));
// eller: TlsTrust.pem(Path.of("/etc/app/client-ca.pem"))

Handler handler = request -> {
    var client = request.tls().orElseThrow().clientCertificates().getFirst();
    return Response.text(200, "hello " + client.getSubjectX500Principal().getName());
};
```

`NONE` (standard) begär inget certifikat. `WANT` begär ett men släpper in klienter utan (då är
`clientCertificates()` tom); ett certifikat som skickas men inte går att verifiera mot
`TlsTrust` ger ändå ett misslyckat handslag. `NEED` kräver ett giltigt certifikat. Spärrlistor
och OCSP kontrolleras inte utöver JDK:ns standard (inget).

### `server.ssl.*` mot `TlsOptions`

| Spring Boot | Spola | Avvikelse |
|---|---|---|
| `server.ssl.enabled` | `HttpServerOptions.withTls(...)` / `withoutTls()` | TLS är ett värde i optionerna (`Optional<TlsOptions>`), ingen flagga. |
| `server.ssl.key-store` | `TlsOptions.pkcs12(path, password)` | Läses och kontrolleras när `TlsOptions` skapas, inte vid start eller första anslutningen. Ingen `classpath:`-sökväg, bara filer. |
| `server.ssl.key-store-password` | andra argumentet, `char[]` | Kopieras och nollställs efter inläsningen; anroparens array rörs inte. Syns aldrig i `toString()` eller felmeddelanden. |
| `server.ssl.key-password` | – | Nyckeln läses med keystore-lösenordet (som keytool och openssl skriver PKCS#12); separat nyckellösenord stöds inte. |
| `server.ssl.key-store-type` | – | Bara PKCS#12. JKS och annat fungerar via `TlsOptions.of(SSLContext)` eller konvertering (`keytool -importkeystore -deststoretype pkcs12`). |
| `server.ssl.key-alias` | `TlsOptions.pkcs12(path, password, alias)` | Utan alias måste keystoren ha exakt en privat nyckel (Tomcat väljer då själv en). |
| `server.ssl.certificate` | `TlsOptions.pem(certificateChain, privateKey)` | Kedjans ordning kontrolleras. |
| `server.ssl.certificate-private-key` | andra argumentet till `pem` | Bara okrypterad PKCS#8; krypterade PEM-nycklar och PKCS#1 avvisas (Spring stöder krypterade via `private-key-password`). Nyckeln kontrolleras mot certifikatet när `TlsOptions` skapas. |
| `server.ssl.client-auth` (`none`/`want`/`need`) | `withClientAuth(ClientAuth.NONE/WANT/NEED, trust)` | `WANT`/`NEED` kräver en `TlsTrust` (utom med `TlsOptions.of(SSLContext)`, vars egna trust managers används). |
| `server.ssl.trust-store` (+ `-password`, `-type`) | `TlsTrust.pkcs12(path, password)`, `TlsTrust.pem(path)` | Bara poster av typen trusted certificate i PKCS#12 räknas. Ingen standard-truststore (JDK:ns `cacerts`) för klientcertifikat. |
| `server.ssl.enabled-protocols` | `withProtocols("TLSv1.3", "TLSv1.2")` | Standard TLS 1.3 och 1.2. Äldre (`TLSv1.1`, `TLSv1`, `SSLv3`) eller okända namn ger `IllegalArgumentException`. |
| `server.ssl.ciphers` | `withCipherSuites(...)` | Standard är JDK:ns sviter för de valda protokollen. En uttrycklig lista kontrolleras mot de sviter JDK:n stöder; svaga (anon, NULL, EXPORT, RC4, DES/3DES) och listor som inte passar något av protokollen avvisas. |
| `server.ssl.bundle`, SSL-bundles med omladdning | – | Ingen omladdning i drift och ingen SNI med flera certifikat: ett certifikat per server. |
| `server.http2.enabled` | – | ALPN annonserar bara `http/1.1`. En klient som erbjuder `h2` och `http/1.1` får `http/1.1`; en klient som bara erbjuder `h2` får ett misslyckat handslag. |

Ny tidsgräns: `HttpTimeouts.handshakeTimeout` (standard 10 s) är den totala tiden från accept
till klart handslag, inte per läsning, så en klient som skickar handslaget en byte i taget
kopplas ned. En anslutning som står i handslag räknas mot `maxConnections`. Misslyckade
handslag (vanlig HTTP till TLS-porten, skräp, fel protokoll, okänt certifikat) stänger bara den
anslutningen och loggas på `DEBUG`, escapat och utan indata.

`Request.scheme()` är `"https"` över TLS, annars `"http"`, och `Request.tls()` ger det
förhandlade protokollet, chiffersviten och klientens certifikatkedja. En absolute-form-target
med `http://` över TLS avvisas inte (RFC 9112 §3.2.2 kräver det inte) och ändrar inte
`scheme()`.

### Omförhandling (TLS 1.2)

I TLS 1.2 kan en klient begära en ny förhandling mitt i en anslutning. Det kostar servern
CPU (en möjlig överbelastningsattack) och är sällan nödvändigt. JDK:n styr det med den globala
systemegenskapen `jdk.tls.rejectClientInitiatedRenegotiation` (standard `false`). Spola sätter
inga globala egenskaper; vi rekommenderar att programmet startas med

```
java -Djdk.tls.rejectClientInitiatedRenegotiation=true ...
```

eller att `withProtocols("TLSv1.3")` används när alla klienter klarar TLS 1.3 (TLS 1.3 har
ingen omförhandling). `Request.tls()` läses från sessionen för varje förfrågan och visar
därför läget efter en eventuell omförhandling.

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
| `HttpServerOptions` | record: adress, port, `HttpLimits`, `HttpTimeouts`, `Optional<TlsOptions>`; `DEFAULT` och `withX` |
| `HttpLimits`, `HttpTimeouts` | records med `DEFAULT` och `withX` (`HttpTimeouts` med `handshakeTimeout`) |
| `TlsOptions` | record: `TlsIdentity`, `ClientAuth`, `Optional<TlsTrust>`, protokoll, chiffersviter; `pkcs12`, `pem`, `of(SSLContext)`, `withX` |
| `TlsIdentity`, `TlsTrust` | serverns nyckel och kedja, resp. betrodda certifikat för klientcertifikat; lästa och kontrollerade när de skapas |
| `ClientAuth` | `NONE`, `WANT`, `NEED` |
| `TlsInfo` | record: protokoll, chiffersvit, klientens certifikatkedja |
| `TlsMaterialException` | felaktigt nyckelmaterial: fil, vad som är fel; aldrig lösenord eller nyckelbytes |
| `Handler` | `Response handle(Request request) throws Exception` |
| `Request` | `method()`, `target()`, `path()`, `query()`, `host()`, `scheme()`, `tls()`, `version()`, `headers()`, `body()`, `bytes()` |
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
