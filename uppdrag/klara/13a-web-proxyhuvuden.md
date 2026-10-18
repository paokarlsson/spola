# Bakom en omvänd proxy del 1: schema och klientadress från `X-Forwarded-*` (`spola.web`)

## Mål
En app bakom en omvänd proxy som avslutar TLS (Caddy, nginx) ska få veta klientens schema och
adress, men bara när förfrågan kommer från en betrodd proxy. I Spring Boot gör
`server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`) det, och sedan ger
`request.getScheme()`, `isSecure()` och `getRemoteAddr()` klientens värden. Spola får filtret
`ForwardedHeaders.trusting(TrustedProxies)`. Det läser `X-Forwarded-Proto` och `X-Forwarded-For`
när den direkta avsändaren är betrodd. `WebRequest` får `scheme()` och `clientAddress()`, och alla
filter och handlers efter filtret ser klientens värden. `spola.http` får avsändarens adress
(`Request.remoteAddress()`), som saknas helt i dag. Det här är **13a**, första delen av behovet
"Rätt schema och klientadress bakom en omvänd proxy" från appen psykologen. I **13b** får
sessions- och CSRF-cookien `Secure` efter schemat. Koden är säkerhetskritisk, eftersom huvudena
är data utifrån. PROGRESS.md ska säga att en människa bör granska den innan ramverket används på
riktigt.

## Bakgrund / kontext
Uppdrag 01–12 är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-proxyhuvuden.md`.

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar. Läs särskilt "HTTP-server del 1" och "del 2"
(`Request`, `scheme()`, `tls()`), "Web del 1" (filter, `WebRequest`, `WebException`) och
"Sessioner". Alla tester skrivs med `spola.test`.

Det som finns och som uppdraget bygger på:
- `spola.http.Request` är final och skapas bara av `Connection`. Den har `scheme()`, som är
  `"https"` över TLS och annars `"http"`, alltså `"http"` bakom en TLS-proxy. Den har också
  `tls()`, `host()` och `headers()`, men **ingen avsändaradress**. `Connection` har socketen och
  sparar `String.valueOf(socket.getRemoteSocketAddress())` för loggar.
- `spola.web.WebRequest` är final och oföränderlig. Konstruktorn är paketprivat och används bara
  av `Router` och `withAttribute`. Klassen har `http()`, `method()`, `path()`, `headers()`,
  `cookie…`, `query…`, `readJson` och `attribute`/`withAttribute`, men varken `scheme()` eller
  `clientAddress()`.
- `Filter` (`filter(request, next)`) och `RouterBuilder.filter(...)`, där det filter som
  registreras först ligger ytterst. `WebException(status, detail)` blir
  `application/problem+json` hos routern, också när ett filter kastar det.
- `Headers.all(name)` ger alla fält med ett namn, i ordning. `spola.http` tar redan bort SP och
  HTAB runt fältvärden. Ett värde får innehålla obs-text (0x80–0xFF) men inga tecken över U+00FF.
- `RequestMatcher` i `spola.security.web` är ett funktionellt gränssnitt som tar `WebRequest`.
- Sessioner (11a) och CSRF-cookien (11c) sätter `Secure` bara när cookie-funktionen säger det,
  "aldrig efter schemat, eftersom schemat är `http` bakom en TLS-proxy (automatiskt `Secure`
  efter schemat och `X-Forwarded-Proto` är ett eget behov)". Det ändras inte här, utan i 13b.
- Har tidigare uppdrag levererat andra namn än de som står här gäller de levererade.

Behovets användningsfall ska fungera som det står:

```java
var router = Router.builder()
        .filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))  // 10/8, 172.16/12, 192.168/16, loopback, fc00::/7
        .get("/api/whoami", request -> Response.text(200,
                request.scheme() + " " + request.clientAddress().getHostAddress()))
        .build();
// Bakom Caddy:          "https 203.0.113.7"
// Direkt utan proxy:    "http 127.0.0.1"
// Utifrån med falska huvuden till en server som inte litar på avsändaren: huvudena ignoreras
```

Behovets krav:
- Huvudena används bara när den direkta avsändaren är betrodd. Annars ignoreras de helt.
- `X-Forwarded-For` med flera adresser läses från höger, och betrodda proxyadresser hoppas
  över, som i `RemoteIpValve`.
- Ogiltiga värden ger ett tydligt och dokumenterat utfall, aldrig 500.
- `X-Forwarded-Proto` får bara vara `http` eller `https`.
- Filter som körs efter, till exempel säkerhetsfiltret, ska se klientens schema och adress.
- Koden är säkerhetskritisk. Huvudena är data utifrån, så konstruerad indata räknas som
  realistisk.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)

**Var och vad.**
- I `spola.http` tillkommer `Request.remoteAddress()`. Den ger en `InetSocketAddress` med adress
  och port för TCP-anslutningens andra ände, som `getRemoteAddr()` och `getRemotePort()` i
  Spring. Över TLS är det TCP-socketen under TLS. Värdet tas när anslutningen accepteras och är
  aldrig `null`. Inget namnuppslag görs, så `getHostName()` anropas aldrig. Javadoc säger att
  värdet bakom en proxy är proxyns adress och hänvisar till `ForwardedHeaders` i `spola.web`.
- I `spola.web`, paketet `spola.web`, tillkommer följande. Inga nya `requires` behövs.
  - `WebRequest.scheme()` ger `"http"` eller `"https"`, och `WebRequest.clientAddress()` ger en
    `InetAddress`. Utan `ForwardedHeaders` är de `http().scheme()` och
    `http().remoteAddress().getAddress()`. `withAttribute` och varje `WebRequest` som routern gör
    behåller dem.
  - `TrustedProxies` är ett funktionellt gränssnitt med metoden `boolean trusts(InetAddress address)`
    och de statiska fabrikerna `privateNetworks()` och `of(String... networks)`. Det går också att
    skriva det som ett lambda (`address -> false`). Ett undantag från `trusts` går till routerns
    mappers som allt annat ett filter kastar.
  - `ForwardedHeaders` är en final klass som implementerar `Filter`. Den skapas med
    `ForwardedHeaders.trusting(TrustedProxies)`, där `null` ger `NullPointerException`. Den är
    oföränderlig och trådsäker.
- Det blir ingen ny modul. Filtret är ett vanligt `spola.web`-filter, eftersom MONSTER.md säger att
  en befintlig modul utökas först.
- **Bara `ForwardedHeaders` kan ändra `scheme()` och `clientAddress()`.** En paketprivat metod på
  `WebRequest` gör kopian med de nya värdena. Ett värde som skiljer sig från anslutningens kommer
  alltså alltid från en betrodd proxy. `http()` ändras inte: `http().scheme()` och
  `http().remoteAddress()` beskriver anslutningen, alltså proxyn.

**Betrodd avsändare.**
- Filtret frågar `proxies.trusts(request.http().remoteAddress().getAddress())`. Är svaret `false`
  skickas förfrågan vidare oförändrad och huvudena läses inte alls. En obetrodd avsändare kan
  alltså varken ändra värdena eller framkalla 400 med huvudena.
- Filtret räknar alltid från `request.http()`, alltså avsändaren och huvudena, och aldrig från
  förfrågans nuvarande `scheme()` eller `clientAddress()`. Två `ForwardedHeaders` i samma kedja
  ger därför samma resultat som ett.

**`X-Forwarded-Proto` från en betrodd avsändare.**
- Saknas fältet behålls anslutningens schema.
- Annars krävs exakt ett fält vars värde, utan SP och HTAB runt, är `http` eller `https`. Skiftläget
  spelar ingen roll (ASCII). Schemat blir värdet med gemener.
- Allt annat ger 400, till exempel två fält, en lista (`https, http`), ett tomt värde och andra
  värden (`ftp`, `wss`, `https:`).

**`X-Forwarded-For` från en betrodd avsändare.**
- Alla fält läses i ordning som en enda lista (RFC 9110 §5.3) och delas vid `,`. SP och HTAB runt
  varje element tas bort, och tomma element hoppas över (RFC 9110 §5.6.1).
- Saknas fältet, eller har listan inga element, är klienten avsändaren.
- Listan gås igenom från höger:
  - ett element som inte är en IP-literal enligt reglerna nedan ger 400;
  - en adress som `proxies` litar på hoppas över;
  - den första adress som `proxies` inte litar på är klienten, och genomgången slutar där.
- Element till vänster om klienten läses aldrig. Litar `proxies` på alla adresser är klienten den
  vänstraste. Samma `TrustedProxies` avgör både avsändaren och elementen, som `internalProxies` i
  `RemoteIpValve`.
- Därför 400 och inte att elementet ignoreras: ett ogiltigt element nås bara när allt till höger
  om det kommer från betrodda proxyer. Då har en betrodd proxy skickat något trasigt. Om filtret
  då föll tillbaka på avsändaren skulle klienten få proxyns adress, som är intern, och en regel
  som släpper in interna adresser skulle släppa in klienten. 400 är fail-closed och syns direkt.
- En proxy som lägger klientens adress längst till höger (nginx `$proxy_add_x_forwarded_for`)
  placerar skräp som klienten själv skickar till vänster om klientens adress, så det läses aldrig.
  En klient på ett betrott nät som skickar skräp får 400.

**IP-literaler (i `X-Forwarded-For` och i `TrustedProxies.of`).**
- IPv4 är fyra decimala delar åtskilda av `.`. Varje del har 1–3 ASCII-siffror, värdet 0–255 och
  ingen inledande nolla (utom `0` självt). `127.1`, `2130706433`, `1.2.3`, `010.0.0.1` och
  `0x7f.0.0.1` avvisas.
- IPv6 följer textformen i RFC 4291 §2.2: grupper med 1–4 hexsiffror i valfritt skiftläge, högst
  ett `::`, och valfritt en IPv4-del enligt regeln ovan i stället för de två sista grupperna.
  Hakparenteser, zon (`%`), port och blanktecken är inte tillåtna.
- Adressen skapas först efter kontrollen, med `InetAddress.ofLiteral` (JDK 22 och senare, utan
  namnuppslag). JDK:ns egen tolkning är för generös för data utifrån. Under planeringen godtog
  `InetAddress.ofLiteral` i JDK 25 bland annat `127.1`, `2130706433`, `1.2.3` (som blev
  `1.2.0.3`), `010.0.0.1` (som blev `10.0.0.1`), `[2001:db8::1]` och `fe80::1%1`. Därför behövs
  den egna kontrollen.
- Huvudkoden använder inte `InetAddress.getByName`, `getAllByName`, `getHostName` eller
  `getCanonicalHostName`. Ett namn som `example.com` ger alltså aldrig ett DNS-uppslag.
- En IPv4-mappad adress i `X-Forwarded-For` (`::ffff:203.0.113.7`) blir en `Inet4Address`, som JDK:n
  gör, och prövas som IPv4.

**`TrustedProxies`.**
- I `of(String... networks)` är varje post en IP-literal, valfritt följd av `/` och en
  prefixlängd. Prefixlängden är decimal utan inledande nolla (utom `0`): 0–32 för IPv4 och 0–128
  för IPv6. Utan prefix gäller /32 respektive /128.
- Bitar utanför prefixet måste vara noll. `10.0.0.1/8` avvisas och meddelandet föreslår
  `10.0.0.0/8`, så att ett skrivfel syns vid uppstart. En IPv4-mappad post (`::ffff:10.0.0.0/104`)
  avvisas också, och meddelandet säger att IPv4 skrivs som IPv4.
- `null` som array eller post ger `NullPointerException`. Inga poster eller en ogiltig post ger
  `IllegalArgumentException`, och meddelandet citerar posten. Det är konfiguration, inte en
  hemlighet. Arrayen kopieras.
- Ett IPv4-nät matchar bara `Inet4Address`, och ett IPv6-nät matchar bara `Inet6Address`. En zon på
  adressen räknas inte. `trusts(null)` ger `NullPointerException`.
- `privateNetworks()` är
  `of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "::1/128", "fc00::/7")`,
  alltså RFC 1918, loopback och unika lokala IPv6-adresser, som behovet säger. Tomcats standard för
  `internal-proxies` omfattar fler nät, bland annat länklokala adresser och 100.64.0.0/10. Spola
  litar inte på dem, och README har en avvikelserad om det.
- Implementationerna är oföränderliga och trådsäkra. `toString()` listar näten i den ordning de
  gavs, till exempel
  `TrustedProxies[10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8, ::1/128, fc00::/7]`.

**400-svaret.** Filtret kastar `WebException` 400. Dess `detail` namnger huvudet (`X-Forwarded-For`
eller `X-Forwarded-Proto`) men aldrig värdet. Handlern och filtren innanför anropas inte. Filtret
loggar inget.

**Ordningen i kedjan.** `ForwardedHeaders` ska registreras först, alltså ytterst. Då ser alla
filter efter det klientens schema och adress, till exempel `sessions.filter()` och
`security.filter()`. Filter som registreras före det ser anslutningens värden. Det står i Javadoc
och README.

## Krav
- `Request.remoteAddress()`, `WebRequest.scheme()`, `WebRequest.clientAddress()`,
  `TrustedProxies` och `ForwardedHeaders` byggs enligt besluten. Deras Javadoc beskriver:
  - regeln om betrodd avsändare;
  - genomgången från höger;
  - IP-literalerna;
  - fallen som ger 400;
  - ordningen i kedjan;
  - att `http()` beskriver anslutningen.
- Klass-Javadoc för `WebRequest` och Javadoc i `module-info.java` för `spola.web` och `spola.http`
  nämner det nya. `requires` och `exports` är oförändrade i båda modulerna.
- `README.md` i `modules/web/` får ett avsnitt "Bakom en omvänd proxy".
  - Spring-koden (`server.forward-headers-strategy=native` och `request.getScheme()`,
    `getRemoteAddr()`) står bredvid Spola-koden.
  - Spola-delen är behovets kod i en testkälla som kompileras, och ett test kontrollerar att
    README visar den oförändrad.
  - Avsnittet säger att `ForwardedHeaders` ska registreras först och förklarar Docker-fallet i
    kriterium 13.
  - Avvikelsetabellen får rader om:
    - att `Forwarded` (RFC 7239), `X-Forwarded-Host`, `X-Forwarded-Port` och
      `X-Forwarded-Prefix` inte stöds;
    - att ogiltiga värden från en betrodd proxy ger 400;
    - de strikta IP-literalerna;
    - näten i `privateNetworks()` jämfört med Tomcats `internal-proxies`;
    - att `Secure` på cookies ännu inte följer schemat (kommer i 13b).
- `README.md` i `modules/http/` nämner `Request.remoteAddress()` i "API i korthet".

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0. De nya testerna körs i
   `spola.web.WebTests` och `spola.http.HttpTests`, och testet i kriterium 12 körs i
   `spola.security.web.SecurityWebTests`.
2. Modulernas gränser:
   - `requires` och `exports` i `modules/web/src/module-info.java` och
     `modules/http/src/module-info.java` är oförändrade.
   - Huvudkoden i båda modulerna importerar inget från `java.lang.reflect` eller
     `java.lang.invoke`. Den anropar inte `getByName`, `getAllByName`, `getHostName` eller
     `getCanonicalHostName`, och ett källkodstest kontrollerar det för `spola.web`.
   - `git diff` visar inga ändringar i `Build.java`. Under `src/` och `test/` ändras bara `web` och
     `http`, med ett undantag: en ny testfil i `modules/security-web/test/` och dess registrering i
     `SecurityWebTests` (kriterium 12).
   - Befintliga testfiler är oförändrade, utom registreringen av nya tester och, om
     källkodskontrollen läggs där, `SourceRulesTests` i `spola.web`.
3. Behovets användningsfall: behovets kod är en testkälla. Ett test går genom en riktig
   `HttpServer` (127.0.0.1, port 0) och `HttpClient` och visar att:
   - `X-Forwarded-Proto: https` och `X-Forwarded-For: 203.0.113.7` ger `https 203.0.113.7`;
   - en förfrågan utan huvudena ger `http 127.0.0.1`;
   - samma router med `TrustedProxies.of("10.0.0.0/8")`, där avsändaren 127.0.0.1 inte är
     betrodd, ger `http 127.0.0.1` utan 400. Det gäller både de falska huvudena och varje ogiltigt
     värde i kriterium 5 och 6.
4. Ett tabelldrivet test av `X-Forwarded-For` från en betrodd avsändare använder
   `TrustedProxies.of("127.0.0.0/8", "10.0.0.0/8", "fd00::/8")`. Det visar klientadressen för
   minst dessa fall:
   - `203.0.113.7` ger `203.0.113.7`;
   - `198.51.100.1, 203.0.113.7` ger `203.0.113.7`;
   - `skräp, 203.0.113.7` ger `203.0.113.7`, utan 400, eftersom elementet till vänster inte läses;
   - `203.0.113.7, 10.0.0.2, 10.0.0.3` ger `203.0.113.7`;
   - `10.0.0.5, 10.0.0.2` ger `10.0.0.5`, eftersom båda är betrodda och den vänstraste gäller;
   - två fält, `203.0.113.7` och `10.0.0.2`, ger `203.0.113.7`;
   - `" 203.0.113.7 ,, 10.0.0.2 ,"` ger `203.0.113.7`;
   - ett tomt värde och `" , "` ger avsändaren `127.0.0.1`;
   - `2001:db8::1` och `2001:DB8::1, fd00::2` ger `2001:db8:0:0:0:0:0:1` som `Inet6Address`;
   - `::ffff:203.0.113.7` ger `203.0.113.7` som `Inet4Address`.
5. Ett tabelldrivet test med minst 20 värden visar att ett ogiltigt element som genomgången når
   ger 400 utan att handlern anropas. Svaret är `application/problem+json`, och `detail` nämner
   `X-Forwarded-For` men inte värdet. Bland fallen finns:
   - `unknown` och `example.com`;
   - `203.0.113.7:8080`, `[2001:db8::1]`, `fe80::1%1` och `fe80::1%eth0`;
   - `127.1`, `2130706433`, `1.2.3`, `010.0.0.1`, `0x7f.0.0.1`, `256.1.1.1` och `1.2.3.4.5`;
   - `203.0.113.7 10.0.0.1`, `2001:db8::1::2` och `1:2:3:4:5:6:7:8:9`;
   - `skräp, 10.0.0.2`, där det ogiltiga elementet kommer efter en betrodd adress;
   - ett element med obs-text, till exempel `203.0.113.7` följt av byten 0xA0. Det skickas över rå
     socket om `HttpClient` inte kan skicka det.
6. Ett test av `X-Forwarded-Proto` från en betrodd avsändare visar att:
   - `https`, `HTTPS` och `Https` ger `https`, och `http` ger `http`;
   - `https` utan `X-Forwarded-For` ger `https 127.0.0.1`;
   - två fält, `https, http`, ett tomt värde, `ftp`, `wss`, `https:` och `httpss` ger 400 utan att
     handlern anropas, och `detail` nämner `X-Forwarded-Proto` men inte värdet.
7. Ett test av kedjan visar att:
   - ett filter som registrerats efter `ForwardedHeaders`, ett filter i en grupp och handlern ser
     klientens värden, också på en förfrågan som ett filter skickat vidare med `withAttribute`;
   - ett filter som registrerats före ser anslutningens värden;
   - `request.http().scheme()` är `http` och `request.http().remoteAddress()` är avsändaren också
     efter filtret;
   - två `ForwardedHeaders` efter varandra ger samma resultat som ett, också för
     `10.0.0.5, 10.0.0.2`;
   - routerns egna svar (404, 405) går genom filtret, och ett ogiltigt värde ger 400 också för en
     väg som inte finns.
8. Ett test av `TrustedProxies` utan nätverk använder adresser från `InetAddress.ofLiteral` och
   visar att:
   - `privateNetworks()` litar på gränserna `10.0.0.0`, `10.255.255.255`, `172.16.0.0`,
     `172.31.255.255`, `192.168.0.0`, `192.168.255.255`, `127.0.0.1`, `127.255.255.255`, `::1`,
     `fc00::` och `fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff`, och på `::ffff:10.0.0.1`;
   - `privateNetworks()` inte litar på `9.255.255.255`, `11.0.0.0`, `172.15.255.255`, `172.32.0.0`,
     `192.167.255.255`, `192.169.0.0`, `169.254.1.1`, `100.64.0.1`, `203.0.113.7`, `0.0.0.0`, `::`,
     `::2`, `fe80::1`, `fbff:ffff::1`, `fe00::1` och `2001:db8::1`;
   - `of("0.0.0.0/0")` litar på varje IPv4-adress och ingen IPv6-adress, `of("::/0")` gör tvärtom,
     och `of("203.0.113.7")` litar bara på den adressen;
   - prefixgränserna stämmer, till exempel `/12` och `/7` precis på och precis utanför gränsen,
     och `/32` och `/128`;
   - dessa ger `IllegalArgumentException`: `of()`, `10.0.0.0/33`, `::/129`, `10.0.0.1/8`,
     `10.0.0.0/08`, `10.0.0.0/`, `/8`, `10.0.0.0/8/8`, `10.0.0.0 /8`, `10.0.0.0/-1`,
     `10.0.0.0/+8`, `::ffff:10.0.0.0/104`, `localhost`, `example.com`, `[::1]`, `fe80::1%1` och
     `127.1`. Meddelandet för `10.0.0.1/8` föreslår `10.0.0.0/8`;
   - `null` som array, post eller adress ger `NullPointerException`;
   - en ändring i den givna arrayen efteråt inte syns;
   - `toString()` för `privateNetworks()` är exakt strängen i besluten.
9. Ett test i `spola.http` visar att `Request.remoteAddress()` är klientsocketens lokala adress
   och port, både över vanlig TCP och över TLS. Det visar också att två förfrågningar på samma
   anslutning ger samma värde och att två anslutningar ger olika portar.
10. Ett test med 200 virtuella trådar skickar samtidigt olika `X-Forwarded-For`, och varannan
    skickar `X-Forwarded-Proto: https`. Varje tråd får exakt sitt eget schema och sin egen adress
    tillbaka.
11. Ett test med markörer visar att inget värde från `X-Forwarded-For` eller `X-Forwarded-Proto`
    finns i ett 400-svar, i en loggrad som routern skriver under testerna eller i ett
    undantagsmeddelande.
12. Ett test i `spola.security.web` visar att säkerhetsfiltret ser klientens värden. Där registreras
    `ForwardedHeaders` först och `WebSecurity` efter.
    - En `RequestMatcher` (ett lambda) i en regel ser klientens schema och adress.
    - En regel som nekar adresser utanför `privateNetworks()` nekar `X-Forwarded-For: 203.0.113.7`
      och släpper in en förfrågan utan huvudet.
13. `README.md` i `modules/web/` har avsnittet "Bakom en omvänd proxy" enligt kraven, med
    Spola-exemplet som kompilerad testkälla som ett test jämför med README. Avsnittet förklarar två
    saker:
    - Bara proxyns port ska publiceras.
    - `privateNetworks()` litar på hela nätet. Med Dockers userland-proxy, och i Docker Desktop,
      kan en direkt anslutning till en publicerad port komma från bryggans gateway (till exempel
      `172.17.0.1`). Den adressen är betrodd och kan förfalska huvudena. Då ska appen lita bara på
      proxyns adress med `TrustedProxies.of(...)`.
14. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
15. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.
    - PROGRESS.md beskriver det levererade och säger att 13a är första delen av behovet
      `2026-10-13-proxyhuvuden` och att 13b återstår. Det säger att koden är säkerhetskritisk och
      bör granskas av en människa. Det listar kända begränsningar:
      - bara `X-Forwarded-For` och `X-Forwarded-Proto` läses, och `host()` är det `Host` som
        proxyn skickar;
      - `privateNetworks()` litar på hela nätet, med Docker-fallet;
      - en proxy som skickar vidare klientens egna huvuden utan att ersätta dem gör att klienten
        styr värdena;
      - ogiltiga värden från en betrodd proxy ger 400, till exempel `unknown`;
      - `Secure` följer ännu inte schemat.
    - ARCHITECTURE.md får ett avsnitt "Bakom en omvänd proxy (`spola.web`, `spola.http`)" som
      beskriver:
      - varför det är ett filter i `spola.web` och inte en inställning i `spola.http`;
      - att bara `ForwardedHeaders` kan ändra värdena och att `http()` beskriver anslutningen;
      - regeln om betrodd avsändare;
      - genomgången från höger;
      - varför ogiltiga värden ger 400;
      - de strikta IP-literalerna och varför `InetAddress.ofLiteral` inte räcker ensam;
      - näten i `privateNetworks()`;
      - ordningen i kedjan.

    Raden för `spola.web` i modulkatalogen i `framework/MONSTER.md` är uppdaterad. Den ska nämna
    `server.forward-headers-strategy` (`RemoteIpValve`) och ingångarna `ForwardedHeaders` och
    `TrustedProxies`. Raden för `spola.web` i modultabellen i `README.md` ska nämna proxyhuvuden.

## Utanför scope
- `Secure` efter schemat på sessions- och CSRF-cookien. Det kommer i 13b.
- `Forwarded` (RFC 7239), `X-Forwarded-Host`, `X-Forwarded-Port`, `X-Forwarded-Prefix`,
  `X-Real-IP`, `CF-Connecting-IP` och PROXY-protokollet.
- Att ändra `host()`, porten eller absoluta URL:er efter proxyn, och omdirigering till `https`.
- Ett publikt sätt för andra filter att ändra `scheme()` och `clientAddress()`, att ta bort
  huvudena ur förfrågan (`removeOnly`) och `X-Forwarded-By`.
- Accessloggar med klientadressen och hastighetsbegränsning per adress.
- Ändringar i `spola.security.web` (utom testet i kriterium 12), i `Build.java` och i andra
  moduler.
- Release och ny version, som görs för hand.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion, ingen
`ThreadLocal` och inga namnkonventioner. Huvudkoden gör inga namnuppslag, och adresser skapas med
`InetAddress.ofLiteral` efter den egna kontrollen. Inga nya filtyper i `framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
