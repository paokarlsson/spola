Source: uppdrag/07b-http-tls.md

# HTTP-server del 2: TLS (HTTPS) ovanpå spola.http

## Mål
Lägg till TLS i `spola.http`, så att samma server som i 07a kan köra HTTPS med certifikat
från en PKCS#12-keystore eller PEM-filer, med moderna protokoll (TLS 1.3 och 1.2), ALPN
som säger `http/1.1`, valfri klientcertifikatautentisering (mTLS) och samma gränser och
tidsgränser som vanlig HTTP, plus en tidsgräns för handskakningen. Detta ersätter
Spring Boots `server.ssl.*` (och SSL-bundles på lägsta nivå). TLS-protokollet själv kommer
från JDK:ns `javax.net.ssl` (REGLER §4: JDK:ns primitiver); det som byggs här är
konfigurationen, inläsningen av nycklar och certifikat, och kopplingen till anslutningens
livscykel, och det är säkerhetskritiskt.

## Bakgrund / kontext
Uppdrag 01–07a är klara: bygge och guardrails, testkörare (`spola.test`), DI (`spola.di`),
konfiguration (`spola.config`), loggning och hjälpare (`spola.util`), builders
(`spola.codegen`), integrationsprogrammet `spola.demo`, JSON (`spola.json`,
`spola.json.codec`) och HTTP/1.1-servern `spola.http` (07a). Läs `framework/REGLER.md`,
`framework/PROGRESS.md` och `framework/ARCHITECTURE.md` (avsnittet "HTTP-server del 1")
innan du börjar. Alla tester skrivs med `spola.test`.

Det som finns i `spola.http` efter 07a (se PROGRESS.md): `HttpServer.start(options, handler)`
på `java.net.ServerSocket` med acceptor som plattformstråd och en virtuell tråd per
anslutning (`Connection`, `ConnectionInput`, `GuardedOutput` som skrivvakt), `maxConnections`
via en semafor före `accept`, `HttpServerOptions(address, port, limits, timeouts)` med
`withX`-metoder, `HttpLimits`, `HttpTimeouts(headerTimeout, idleTimeout, readTimeout,
writeTimeout, shutdownTimeout)`, `Request` (metod, target, sökväg, query, `host()`, version,
`Headers`, lat kropp), mjukt stopp och 172 tester. Modulen har inga `requires` på andra
ramverksmoduler och `requires static java.net.http` bara för testerna. Uppdelningen av rad
07 i 07a/07b är redan beslutad och dokumenterad i ARCHITECTURE.md.

Erfarenheter som gäller här: guardrails tillåter bara `.java` och `.md` under `framework/`,
så **inga keystores, certifikat eller nycklar får checkas in** (`.p12`, `.jks`, `.pem`,
`.crt`, `.key` är förbjudna filtyper); testerna skapar allt material själva i en temporär
katalog. Felmeddelanden och loggrader får aldrig innehålla lösenord, nyckelmaterial eller rå
indata med styrtecken.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **TLS är ett valfritt värde i `HttpServerOptions`.** Ny komponent `tls` av typen
  `Optional<TlsOptions>` (eller en sealed `Transport` med `Plain`/`Tls`, välj och motivera),
  med `withTls(TlsOptions)`; `DEFAULT` är utan TLS, så 07a:s API och beteende är oförändrat.
  En server lyssnar antingen med eller utan TLS; den som vill ha båda (t.ex. omdirigering
  från 80 till 443) startar två servrar. Ingen automatisk omdirigering i detta uppdrag.
- **`TlsOptions` (record, i `spola.http`)** med:
  - en `SSLContext`-källa: fabrikerna `TlsOptions.pkcs12(Path keystore, char[] password)`
    (valfritt alias när keystoren har flera nycklar), `TlsOptions.pem(Path certificateChain,
    Path privateKey)` och `TlsOptions.of(SSLContext)` för den som bygger kontexten själv.
    Material läses och kontrolleras **när `TlsOptions` skapas** (fel nyckel, fel lösenord,
    nyckel som inte hör till certifikatet, saknad fil, tom kedja ger ett tydligt undantag
    med filens sökväg men utan lösenord eller nyckelbytes), inte vid första anslutningen.
  - `protocols`: standard `TLSv1.3` och `TLSv1.2`; att ange något äldre än TLS 1.2 eller ett
    okänt protokoll ger `IllegalArgumentException`. Chiffersviter: JDK:ns standard för de
    valda protokollen (ingen egen lista som standard); en uttrycklig lista får anges och
    kontrolleras mot de som JDK:n stöder.
  - `clientAuth`: enum `NONE` (standard), `WANT`, `NEED`, med en truststore (PKCS#12 eller
    PEM-certifikat) för klientcertifikaten när den inte är `NONE`.
  - `toString()` visar källa (filnamn), protokoll och `clientAuth`, aldrig lösenord.
    Lösenordsarrayen kopieras in och nollställs efter inläsningen; anroparens array rörs inte.
- **PEM utan preview-API:** JDK 25:s PEM-API (JEP 470) är preview och får inte användas.
  Certifikatkedjan läses med `CertificateFactory` (X.509, en eller flera
  `BEGIN CERTIFICATE`-block), privat nyckel som okrypterad PKCS#8 (`BEGIN PRIVATE KEY`) med
  `KeyFactory` och `PKCS8EncodedKeySpec`, där algoritmen (RSA, EC, Ed25519) avgörs av
  nyckeln och kontrolleras mot certifikatets publika nyckel. Krypterade nycklar
  (`ENCRYPTED PRIVATE KEY`) och PKCS#1 (`BEGIN RSA PRIVATE KEY`) avvisas med ett meddelande
  som säger hur nyckeln konverteras (`openssl pkcs8 -topk8 -nocrypt`). Parsningen av PEM-
  ramarna är strikt (rätt BEGIN/END-par, giltig Base64) och testas med felaktiga filer.
- **ALPN:** servern annonserar bara `http/1.1`. En klient som erbjuder `h2` och `http/1.1`
  får `http/1.1`; en klient som bara erbjuder `h2` får ett misslyckat handslag (ingen tyst
  HTTP/1.1 till en klient som inte talar det).
- **Handskakningen görs på anslutningens virtuella tråd, aldrig på acceptor-tråden.**
  `accept()` returnerar direkt; handslaget startas uttryckligen (`startHandshake()`) på
  anslutningens tråd innan första förfrågan läses. Ny tidsgräns
  `HttpTimeouts.handshakeTimeout` (standard 10 s, total tid från accept till klart handslag,
  inte per läsning) så att en klient som skickar handslaget en byte i taget kopplas ned. En
  anslutning under handslag räknas mot `maxConnections`. Misslyckade handslag (fel protokoll,
  okänt certifikat, vanlig HTTP till TLS-porten, skräp) stänger anslutningen, loggas på
  `DEBUG` (escapat, utan rå indata) och dödar aldrig servern.
- **TLS ändrar inte HTTP-semantiken:** parsning, ramning, gränser, keep-alive, pipelining,
  `Expect: 100-continue`, felhantering och skrivvakten fungerar som i 07a över
  `SSLSocket`. `writeTimeout` gäller också när klienten slutar läsa under TLS. Mjukt stopp
  stänger lediga TLS-anslutningar (med `close_notify` när det går) och väntar på pågående.
- **`Request` får TLS-information:** `Request.tls()` returnerar `Optional<TlsInfo>`
  (record: förhandlat protokoll, chiffersvit, klientens certifikatkedja som
  `List<X509Certificate>`, tom när klienten inte skickade något). `Request.scheme()` ger
  `"https"` eller `"http"`. Absolute-form med fel schema (`http://` på en TLS-anslutning)
  avvisas inte (RFC 9112 §3.2.2 säger inget om det), men beteendet dokumenteras.
- **Ingen omladdning av certifikat i drift, ingen SNI med flera certifikat** (ett certifikat
  per server; alias väljer vilket). Klientinitierad omförhandling i TLS 1.2 styrs av JDK:ns
  globala egenskap `jdk.tls.rejectClientInitiatedRenegotiation`; modulen sätter inga globala
  systemegenskaper, men README och ARCHITECTURE.md beskriver risken och rekommendationen.
- **Testernas certifikat skapas av testerna:** JDK:n har inget publikt API för att skapa
  X.509-certifikat, och `sun.security.x509` exporteras inte. Testerna kör därför `keytool`
  från `System.getProperty("java.home")/bin` med `ProcessBuilder` i en temporär katalog
  (en CA, ett servercertifikat med SAN `IP:127.0.0.1` och `DNS:localhost` signerat av CA:n,
  ett klientcertifikat, ett utgånget eller självsignerat certifikat för negativa fall, med
  RSA och EC-nycklar). PEM-filer till PEM-testerna skrivs från samma keystore via
  `KeyStore`-API:t (`getEncoded()` + Base64). Material skapas en gång per testkörning, inte
  per test. Inga `--add-exports` eller interna JDK-klasser.

## Krav
- `TlsOptions`, `TlsInfo`, `ClientAuth` (eller motsvarande namn) och `handshakeTimeout` i
  `spola.http`, enligt besluten ovan. `module-info.java` får inga `requires` på andra
  ramverksmoduler (`javax.net.ssl` och `java.security` finns i `java.base`).
- Ingen `java.lang.reflect` eller `java.lang.invoke` i huvudkoden, inga preview-funktioner,
  inga interna JDK-paket, inga globala systemegenskaper som sätts av modulen.
- 07a:s 172 tester ska gå gröna oförändrade (de kör utan TLS). Utöver de nya testerna körs
  ett urval av 07a:s protokolltester även över TLS (minst keep-alive, pipelining, chunked,
  en handfull felaktiga förfrågningar och 100-continue), helst genom att samma testfall
  parametriseras på transporten i stället för att kopieras.
- Tester startar servern på `127.0.0.1` port 0 och använder dels `SSLSocket` från en
  klient-`SSLContext` som litar på testets CA (för exakta bytes, långsamma handslag,
  protokollversioner och mTLS), dels `java.net.http.HttpClient` med samma kontext (för att
  visa att en riktig klient fungerar över HTTPS, inklusive ALPN). Modulens samtliga tester
  ska fortfarande ta högst cirka 45 sekunder.
- `README.md` i `modules/http/` får ett avsnitt om HTTPS: exempel med PKCS#12 och med PEM
  (t.ex. filer från Let's Encrypt), mTLS-exempel, bredvid motsvarande Spring Boot-
  konfiguration, och en tabell som mappar `server.ssl.enabled`, `server.ssl.key-store`,
  `server.ssl.key-store-password`, `server.ssl.key-store-type`, `server.ssl.key-alias`,
  `server.ssl.certificate`, `server.ssl.certificate-private-key`, `server.ssl.client-auth`,
  `server.ssl.trust-store`, `server.ssl.enabled-protocols` och `server.ssl.ciphers` mot
  `TlsOptions`, med avvikelser (t.ex. att material läses när `TlsOptions` skapas och att
  krypterade PEM-nycklar inte stöds).

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och alla 07a-tester går
   fortfarande gröna utan ändrade förväntningar.
2. Modulens huvudkod importerar inget från `java.lang.reflect`, `java.lang.invoke` eller
   `sun.*`/`jdk.internal.*`, sätter inga systemegenskaper, och `module-info.java` har inga
   `requires` på andra ramverksmoduler.
3. Ett test med `java.net.http.HttpClient` gör `GET` och `POST` med kropp över HTTPS mot en
   server med PKCS#12-material och kontrollerar status, huvuden och kropp; samma test körs
   mot en server med PEM-material (RSA och EC). Klienten litar bara på testets CA, och
   servercertifikatets SAN `127.0.0.1` verifieras (ingen avstängd värdnamnskontroll).
4. Ett test visar att en klient som bara tillåter TLS 1.1 eller äldre inte får något
   handslag, att TLS 1.3 och TLS 1.2 fungerar (förhandlat protokoll syns i
   `Request.tls()`), och att `TlsOptions` med `TLSv1.1` eller ett okänt protokoll ger
   `IllegalArgumentException`.
5. Ett test visar ALPN: en klient som erbjuder `h2,http/1.1` får `http/1.1`
   (`SSLSocket.getApplicationProtocol()`), och en klient som bara erbjuder `h2` får ett
   misslyckat handslag; `HttpClient` med standardversion (HTTP/2 föredras) fungerar och får
   HTTP/1.1.
6. Ett test visar mTLS: med `NEED` misslyckas handslaget utan klientcertifikat och med ett
   certifikat som inte är signerat av truststorens CA; med ett giltigt klientcertifikat ser
   handlern dess kedja i `Request.tls()`. Med `WANT` lyckas förfrågan utan certifikat och
   kedjan är tom. Med `NONE` begärs inget klientcertifikat.
7. Ett tabelldrivet test visar att felaktigt material ger ett tydligt undantag när
   `TlsOptions` skapas, och att varken lösenordet eller nyckelns bytes (Base64 eller rått)
   finns i meddelandet, orsakskedjan eller `toString()`. Fallen omfattar minst: saknad fil,
   fel keystore-lösenord, okänt alias, keystore utan privat nyckel, PEM-nyckel som inte hör
   till certifikatet, krypterad PKCS#8, PKCS#1 (`RSA PRIVATE KEY`), PEM utan
   `END`-rad, felaktig Base64, tom certifikatkedja och en fil som inte är PEM alls.
8. Ett test visar att anroparens lösenordsarray inte ändras och att `TlsOptions.toString()`
   inte innehåller lösenordet.
9. Ett test visar `handshakeTimeout` med kort värde: en klient som skickar ClientHello en
   byte i taget (snabbare än någon per-läsning-gräns) kopplas ned efter `handshakeTimeout`,
   och en klient som öppnar en TCP-anslutning och inte skickar något kopplas ned efter
   samma tid. Under tiden betjänar servern andra klienter (acceptor-tråden blockeras inte).
10. Ett test visar att misslyckade handslag inte skadar servern: vanlig HTTP till TLS-porten,
    slumpmässiga bytes, ett avbrutet handslag och en klient som inte litar på servern ger
    stängda anslutningar, och nästa giltiga HTTPS-förfrågan betjänas. Loggraderna för dessa
    fall innehåller inga råa styrtecken.
11. Ett test kör ett urval av 07a:s protokolltester över TLS: keep-alive (tre förfrågningar
    på samma TLS-anslutning), pipelining, chunked i båda riktningarna, `Expect:
    100-continue`, minst tio fall ur tabellen med felaktiga förfrågningar (med samma status
    och stängd anslutning) och smuggelförsöket.
12. Ett test visar `writeTimeout` över TLS: en klient som slutar läsa ett stort strömmande
    svar kopplas ned och handlerns tråd blir fri. Ett test visar `maxConnections` där
    anslutningar som står i handslag räknas.
13. Ett test visar mjukt stopp över TLS: lediga TLS-anslutningar stängs, en pågående
    förfrågan får sitt fullständiga svar med `Connection: close`, och `close()` returnerar.
14. Ett test kör 200 samtidiga HTTPS-klienter (virtuella trådar, keep-alive, flera
    förfrågningar var) och kontrollerar att alla svar är rätt.
15. Testernas nycklar och certifikat skapas av testerna i en temporär katalog med `keytool`
    och `KeyStore`-API:t; inga keystores, certifikat eller nycklar finns i repot, och inga
    interna JDK-klasser eller `--add-exports` används.
16. `README.md` i `modules/http/` har HTTPS-avsnittet med exemplen och tabellen enligt
    kraven, och beskriver omförhandlingsrisken och `jdk.tls.rejectClientInitiatedRenegotiation`.
17. Inga annoteringar utöver tillåtelselistan, inga beroenden utöver JDK:n (guardrails
    grön), inga nya filtyper under `framework/`, ingen regel i `framework/REGLER.md` bruten.
18. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade, inklusive besluten om TLS som valfritt
    värde i optionerna, när material läses och kontrolleras, PEM utan preview-API,
    protokoll- och chifferstandard, ALPN, handslag på anslutningens tråd med
    `handshakeTimeout`, `Request.tls()`/`scheme()`, mTLS, att modulen inte sätter globala
    egenskaper, och hur testerna skapar sitt certifikatmaterial.

## Utanför scope
- Omladdning av certifikat i drift (Spring Boots SSL-bundle-reload), flera certifikat per
  server via SNI, OCSP-stapling och CRL-kontroll av klientcertifikat utöver vad JDK:ns
  `TrustManager` gör som standard.
- Krypterade PEM-nycklar, PKCS#1, JKS som eget format (fungerar via `TlsOptions.of`).
- Automatisk omdirigering från HTTP till HTTPS och HSTS-huvud (hör till web/security, 08/11).
- HTTP/2 (även över ALPN), HTTP/3, WebSocket.
- Koppling till `spola.config`/`spola.di` och uppstart (uppdrag 19).
- Routing, procentavkodning och request/response-modellen för applikationer (uppdrag 08).
- Att åtgärda 07a:s kända begränsningar i PROGRESS.md, om de inte behövs för TLS.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion i modulens huvudkod. Inga nya filtyper i
`framework/` och inget nyckelmaterial i repot. Testerna får inte bero på nätverk utanför
`127.0.0.1`, får inte binda fasta portar och får inte ändra globala systemegenskaper på ett
sätt som påverkar andra tester (en klient-`SSLContext` per test i stället för
`javax.net.ssl.trustStore`).

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
