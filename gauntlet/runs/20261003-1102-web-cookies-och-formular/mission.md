Source: uppdrag/08c-web-cookies-och-formular.md

# Web del 3: cookies och formulärkroppar

## Mål
`spola.web` ska kunna läsa cookies, sätta cookies och ta emot HTML-formulär på samma sätt som
`@CookieValue`, `ResponseCookie` och `@RequestParam`/`@ModelAttribute` för
`application/x-www-form-urlencoded` i Spring MVC. Allt ska ske med uttryckliga anrop och
utan reflektion. En handler läser en cookie med `request.cookie("session")` och ett formulär
med `request.readForm()`, och den sätter en cookie med `Responses.withCookie(response,
ResponseCookie.of(...)...)`. Säkra standardvärden (`HttpOnly`, `SameSite=Lax`, `Path=/`) och
validering av cookie-prefixen `__Secure-`/`__Host-` görs när cookien skapas. Därmed är rad 08 i
`PLAN.md` klar, och uppdrag 10–11 (sessioner, CSRF) har det de behöver från webblagret.

## Bakgrund / kontext
Uppdrag 01–07, 08a och 08b är klara. Läs `framework/REGLER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "HTTP-server del 1",
"Web del 1" och "Web del 2". Alla tester skrivs med `spola.test`.

Det som finns efter 08b (se `PROGRESS.md`):
- `spola.web` (`requires transitive spola.http`, `requires transitive spola.json`,
  `requires static java.net.http` bara för testerna). Där finns `Router`, `RouterBuilder`
  (med `jsonLimits`), `WebRequest` (oföränderlig; `query`/`queryAll`/`requiredQuery`/
  `queryParams`, `headers()`, `contentType()`, `accept()`, `negotiate(...)`, `readJson`,
  `body()`/`bytes()`, attribut), `Param` (strikta typade värden, fel ger 400 med namn och
  escapat värde), `WebException`, `Problem` (med `FieldError`/`withErrors`), `MediaType`,
  `AcceptHeader` och `Responses` (`json`, `created`, `noContent`).
- Interna hjälpare: `Decoding` (strikt procentavkodning, `parseQuery` enligt WHATWG URL
  §5.1 med högst 1 000 par), `QueryState` (lat parsning, högst en gång per förfrågan, delad
  mellan härledda `WebRequest`), `BodyState` (kroppen läses en gång, annars
  `IllegalStateException`), `JsonBody` (ordningen `Content-Encoding` → `Content-Type` → tom
  kropp → strömmande parsning med 413 → mappning) och `Text` (escaping och kortning).
- `spola.http`: `Headers.with` lägger till ett fält (flera fält med samma namn är
  tillåtna), `Response.withHeader`, reserverade huvuden (`Content-Length`,
  `Transfer-Encoding`, `Connection`, `Date`) avvisas. `HttpLimits.DEFAULT` har
  `maxHeaderSectionSize` 16 384 och `maxBodySize` 10 MiB.

08b delade den ursprungliga 08b i två delar (se ARCHITECTURE.md, "Web del 2"). Detta är
**08c**: cookies in, `Set-Cookie` ut och formulärkroppar. Multipart och filuppladdning ligger
fortfarande utanför.

Erfarenhet från tidigare iterationer: allt som byggs av extern data (cookievärden,
formulärvärden) och hamnar i en loggrad eller ett problem-svar ska vara kort och fritt från
råa styrtecken. Gränser ska kontrolleras innan minnet hinner växa. Återanvänd befintliga
hjälpare (`Decoding.parseQuery`, `BodyState`, ordningen i `JsonBody`) i stället för att
skriva parallella varianter.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Modul:** allt läggs i `spola.web` (paketet `spola.web`). Inga nya `requires`, ingen
  reflektion. Inga ändringar i `spola.http` utöver små tillägg som motiveras i
  ARCHITECTURE.md. 08a:s, 08b:s och 07:s tester ska vara oförändrade och gröna.
- **Inkommande cookies (RFC 6265 §4.2 och §5.4):**
  - `WebRequest.cookie(String name)` ger `Optional<String>` (det första värdet),
    `cookieAll(String name)` ger `List<String>` (alla, i huvudets ordning; webbläsare skickar
    samma namn flera gånger för olika `Path`), `cookie(String name, Param<T>)` ger
    `Optional<T>` med samma strikta konvertering och 400 som för query, och `cookies()` ger
    en oföränderlig, ordnad `Map<String, List<String>>`.
  - Parsningen är **lat och görs högst en gång per förfrågan** (som `QueryState`, delad av
    alla `WebRequest` som härleds med `withAttribute`).
  - Flera `Cookie`-fält slås ihop som om de skilts med `"; "`.
  - Huvudet delas på `;`, och varje par trimmas på mellanslag och tab. Namnet är det före
    första `=` och måste vara en `token` (RFC 9110 §5.6.2). Värdet är `*cookie-octet` eller
    samma sak inom `"`, enligt RFC 6265 §4.1.1. **Citattecknen är en del av värdet och tas
    inte bort.** Ingen procentavkodning görs, eftersom värdet är opakt för ramverket.
  - **Felaktiga par hoppas över, de ger inte 400.** Det gäller ett par utan `=`, ett namn som
    inte är en token, ett tomt namn, otillåtna tecken i värdet och icke-ASCII. Skälet är att
    andra applikationer på samma domän kan sätta cookies som webbläsaren skickar med, och en
    400 skulle låsa ute användaren. Tomcats `Rfc6265CookieProcessor` gör likadant.
    Överhoppade par loggas inte per förfrågan.
  - Högst 200 cookies per förfrågan. Fler giltiga par än så ger 400, med en detalj som inte
    innehåller några värden. Storleken begränsas redan av `maxHeaderSectionSize`.
- **Utgående cookies: `ResponseCookie`** (oföränderlig, motsvarar Springs
  `ResponseCookie`). Den skapas med `ResponseCookie.of(name, value)`, som ger en builder,
  eller med `ResponseCookie.expire(name)` för att ta bort en cookie.
  - Attributen är `path`, `domain`, `maxAge` (`Duration`), `secure`, `httpOnly` och
    `sameSite` (enum `SameSite { STRICT, LAX, NONE }`).
  - **Standardvärdena är `Path=/`, `HttpOnly` och `SameSite=Lax`.** `Secure` måste anges
    uttryckligen, eftersom utveckling över `http://localhost` annars inte fungerar. Utan
    `maxAge` blir cookien en sessionscookie. Standardvärdena står i Javadoc och i README:s
    avvikelsetabell, eftersom Spring inte har några standardvärden.
  - `expire(name)` ger `Max-Age=0` och ett tomt värde. Den har samma `path`/`domain`-
    builder som `of`, så att en cookie med en annan sökväg går att ta bort.
  - Validering sker när cookien byggs och ger `IllegalArgumentException` med ett kort,
    escapat meddelande. Det som valideras:
    - namnet är en `token`;
    - värdet består av `cookie-octet` (eller är citerat);
    - `path` är `/` eller börjar med `/` och saknar styrtecken och `;`;
    - `domain` innehåller bara LDH-etiketter och punkter (en inledande punkt tas bort);
    - `maxAge` är inte negativ och skrivs i hela sekunder;
    - `SameSite=None` kräver `Secure`;
    - namn som börjar med `__Secure-` kräver `Secure`;
    - namn som börjar med `__Host-` kräver `Secure`, `Path=/` och ingen `domain`.

    Prefixen jämförs skiftlägeskänsligt, som i RFC 6265bis.
  - `toString()`/`headerValue()` ger exakt ett `Set-Cookie`-värde i en fast ordning:
    `name=value; Path=...; Domain=...; Max-Age=...; Secure; HttpOnly; SameSite=...`. Inget
    `Expires` skrivs, bara `Max-Age`. Det är en avvikelse som ska stå i README.
  - `Responses.withCookie(Response, ResponseCookie)` lägger till ett **eget**
    `Set-Cookie`-fält. Flera cookies ger flera fält och slås aldrig ihop med komma (RFC 6265
    §3). Testet ska visa att `spola.http` skickar dem som separata fält.
- **Formulärkroppar (`application/x-www-form-urlencoded`):**
  - `WebRequest.readForm()` och `readForm(int maxBytes)` ger `FormData`. `FormData` är
    oföränderlig och har `first(name)`, `all(name)`, `get(name, Param<T>)` (ger
    `Optional<T>`), `required(name, Param<T>)` och `asMap()` med samma semantik och samma
    400-fel som queryn, men med ordet "form field" i stället för "query parameter" i
    detaljen.
  - Kontrollerna görs i den här ordningen:
    1. `Content-Encoding` som inte saknas och inte är `identity` ger 415.
    2. Ett saknat `Content-Type`, en annan typ än `application/x-www-form-urlencoded` eller en
       `charset` som inte är UTF-8 ger 415. Svaret har huvudet
       `Accept: application/x-www-form-urlencoded`.
    3. Kroppen läses strömmande upp till `maxBytes`. Standard är 2 MiB (Tomcats
       `maxPostSize`), och routerns standard ändras med `RouterBuilder.maxFormSize(int)`. En
       kropp över gränsen ger 413, och läsningen avbryts vid gränsen. `RequestBodyException`
       från `spola.http` får inte sväljas.
    4. Kroppen avkodas som strikt US-ASCII på byte-nivå, så en byte över 0x7F ger 400.
       Därefter tolkas den med samma `Decoding.parseQuery` som queryn: WHATWG §5.1, `+` som
       mellanslag, strikt UTF-8 efter procentavkodningen och högst 1 000 par. Fel ger 400
       utan text ur kroppen.
  - **En tom kropp är ett tomt formulär**, inte ett fel. Ett HTML-formulär utan fält är
    giltigt. Det skiljer sig från `readJson`.
  - Kroppen läses en gång, delat med `readJson`/`body()`/`bytes()` via `BodyState`. Ett andra
    försök ger `IllegalStateException` (500).
  - **Formulär och query slås inte ihop.** Springs `@RequestParam` läser båda; i Spola
    väljer handlern uttryckligen `query(...)` eller `readForm()`. Det ska stå som en rad i
    README:s avvikelsetabell.
  - Mappning av ett formulär till ett record med fel per fält ingår inte. Det kommer med
    validering i uppdrag 09, som ska kunna bygga på `FormData` och `errors`-formatet.

## Krav
- `cookie`, `cookieAll`, `cookie(name, Param)`, `cookies()`, `ResponseCookie` (med
  `SameSite`), `Responses.withCookie`, `readForm`, `FormData` och
  `RouterBuilder.maxFormSize` enligt besluten ovan.
- Allt nytt är oföränderligt och trådsäkert. Inget tillstånd delas mellan förfrågningar.
- Publikt API dokumenteras med Javadoc, även standardvärdena för `ResponseCookie`.
- `README.md` i `modules/web/` utökas med:
  - ett komplett exempel som tar emot ett HTML-formulär med `POST` (`readForm`, typade fält),
    sätter en cookie i svaret och läser cookien i nästa förfrågan. Exemplet står bredvid
    motsvarande Spring Boot-kod (`@RequestParam`/`@ModelAttribute`, `@CookieValue`,
    `ResponseCookie` med `HttpHeaders.SET_COOKIE`). Exemplet ska vara en testkälla som
    kompileras, och ett test ska kontrollera att README visar den oförändrad (som `ItemApi`
    i 08b);
  - nya rader i avvikelsetabellen:
    - standardvärdena för `ResponseCookie`;
    - inget `Expires`;
    - felaktiga cookies hoppas över;
    - citattecken behålls i cookievärden;
    - formulär och query slås inte ihop;
    - en tom formulärkropp är giltig;
    - 415 för fel `Content-Type` på formulär.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. `modules/web/src/module-info.java` har inga nya `requires`. Huvudkoden importerar inget från
   `java.lang.reflect` eller `java.lang.invoke`.
3. Ett tabelldrivet test av cookieparsningen har minst 25 fall. Det visar:
   - flera cookies;
   - samma namn två gånger, som ger `cookieAll` i ordning och `cookie` med det första;
   - mellanslag och tab runt par;
   - ett citerat värde som behåller citattecknen;
   - ett tomt värde (`a=`);
   - `=` inne i värdet;
   - två `Cookie`-fält som slås ihop;
   - att procentkodning inte avkodas;
   - att vart och ett av följande hoppas över utan att giltiga par runt omkring påverkas: ett
     par utan `=`, ett tomt namn, ett namn med otillåtet tecken, ett värde med mellanslag,
     komma, `\`, styrtecken och icke-ASCII;
   - att 200 cookies godtas och att 201 ger 400 utan värden i detaljen;
   - att en saknad `Cookie` ger tomma resultat.
4. Ett test visar att cookieparsningen görs högst en gång per förfrågan även när flera
   härledda `WebRequest` (via `withAttribute`) och flera trådar läser cookies, och att
   `cookie(name, Param.INT)` ger 400 med namnet och ett escapat, kortat värde för `x=abc`.
5. Ett tabelldrivet test av `ResponseCookie` har minst 15 giltiga fall med exakt förväntad
   `Set-Cookie`-text. Fallen ska täcka:
   - standardvärdena;
   - varje attribut;
   - ett citerat värde;
   - `maxAge` på 0 och på 30 dagar;
   - `expire` med och utan `path`/`domain`;
   - `__Host-` och `__Secure-`, korrekt använda.

   Testet har också minst 15 ogiltiga fall som ger `IllegalArgumentException`:
   - ett namn med `=`, mellanslag och styrtecken, och ett tomt namn;
   - ett värde med `;`, `,`, mellanslag, `"` mitt i och icke-ASCII;
   - en `path` med `;` och en `path` som inte börjar med `/`;
   - en `domain` med `/`;
   - en negativ `maxAge`;
   - `SameSite=None` utan `Secure`;
   - `__Secure-` utan `Secure`;
   - `__Host-` med `domain` och `__Host-` med en annan `path` än `/`.

   Inget felmeddelande innehåller ett oescapat styrtecken.
6. Ett test över en riktig `HttpServer` (port 0, `127.0.0.1`) med rå socket visar att ett
   svar med tre cookies har tre separata `Set-Cookie`-rader. Ett test med `HttpClient` och
   en `CookieManager` visar att en cookie som sätts i ett svar skickas tillbaka och läses av
   `request.cookie(...)` i nästa förfrågan.
7. Ett test över en riktig server visar formulärflödet enligt README-exemplet:
   - fält med `+`, `%20`, `%2B`, UTF-8 (`%C3%A5`) och upprepade namn;
   - `FormData.all` i ordning;
   - typade fält via `Param`;
   - `required` för ett saknat fält, som ger 400 med "form field" och namnet;
   - en tom kropp, som ger ett tomt `FormData`;
   - att queryn och formuläret hålls isär (samma namn i båda ger olika värden).
8. Ett test visar 415, med `Accept: application/x-www-form-urlencoded` i svaret, för:
   - ett saknat `Content-Type`;
   - `application/json`;
   - `multipart/form-data`;
   - ett felaktigt `Content-Type`;
   - `charset=iso-8859-1`;
   - `Content-Encoding: gzip`.

   Testet visar också att `application/x-www-form-urlencoded; charset=UTF-8` godtas.
9. Ett test visar 400 utan text ur kroppen (kontrollerat med en unik markörsträng) för:
   - en ogiltig procentkodning (`%G1`);
   - ogiltig UTF-8 efter avkodning (`%C3%28`);
   - en rå byte över 0x7F;
   - 1 001 par.

   Testet visar också att exakt 1 000 par godtas.
10. Ett test visar storleksgränserna:
    - en formulärkropp över `RouterBuilder.maxFormSize(...)` (men under `maxBodySize`) ger
      413 utan att hela kroppen buffras; använd en kropp som är många gånger större än
      gränsen och skickas chunked, och visa att svaret kommer;
    - `readForm(maxBytes)` går före routerns standard;
    - en kropp över `HttpLimits.maxBodySize` ger fortfarande kroppens status från
      `spola.http`.
11. Ett test visar att `readForm` efter `readJson`, `body()` eller `readForm` i samma
    förfrågan ger 500 (via `IllegalStateException`), och att en mapper för `WebException`
    kan ge ett eget svar för 415 och 400 från `readForm`.
12. Ett test med 200 samtidiga klienter (virtuella trådar) som postar olika formulär och
    olika cookies visar att varje klient får tillbaka sina egna värden.
13. Ett test visar att ett cookie- eller formulärvärde med `%0A`, `\u001b` eller en rå
    radbrytning inte hamnar oescapat i någon loggrad eller problem-detalj.
14. `README.md` i `modules/web/` har formulär- och cookieexemplet bredvid Spring Boot (som en
    kompilerad testkälla som ett test jämför med README) och de nya avvikelseraderna enligt
    kraven.
15. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i
    `framework/REGLER.md` är bruten.
16. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. Rad 08 i `PLAN.md` står som klar i
    `PROGRESS.md`. ARCHITECTURE.md beskriver:
    - hur cookies parsas och varför felaktiga par hoppas över;
    - gränsen på 200;
    - att citattecken behålls och ingen avkodning görs;
    - standardvärdena för `ResponseCookie` och prefixreglerna;
    - varför inget `Expires` skrivs;
    - separata `Set-Cookie`-fält;
    - ordningen i `readForm` och dess statuskoder;
    - att en tom kropp är ett tomt formulär;
    - att formulär och query inte slås ihop;
    - att `BodyState` delas med `readJson`.

## Utanför scope
- Multipart (`multipart/form-data`) och filuppladdning.
- Sessioner, signerade eller krypterade cookies och CSRF-skydd (uppdrag 11).
- Mappning av formulär till records och validering per fält (uppdrag 09).
- `Expires`, `Partitioned`, `Priority` och andra cookieattribut utöver de uppräknade.
- Att slå ihop query och formulär, eller att läsa formulär som "request parameters".
- Andra teckenkodningar än UTF-8 i formulär.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Ingen reflektion, inga annoteringar och inga namnkonventioner. Inga
nya filtyper i `framework/`. Testerna får inte bero på nätverk utanför `127.0.0.1` och får
inte binda fasta portar.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
