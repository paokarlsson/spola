# Spec: Proxy del 1 – klientens schema och adress från X-Forwarded-* (spola.web)

## Uppgift
Ge `spola.http` `Request.remoteAddress()` och `spola.web` `WebRequest.scheme()`/`clientAddress()`, `TrustedProxies` och filtret `ForwardedHeaders.trusting(...)`, motsvarigheten till Spring Boots `server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`). Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261014-0119-web-proxyhuvuden/mission.md` (källa `uppdrag/13a-web-proxyhuvuden.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
