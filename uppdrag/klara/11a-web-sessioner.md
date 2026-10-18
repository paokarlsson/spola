# Sessioner del 1: sessioner i minnet med sessionscookie (`spola.web`)

## Mål
`spola.web` ska få HTTP-sessioner, motsvarigheten till servlet-API:ts `HttpSession` med Spring
Boots `server.servlet.session.*`: en session i minnet per webbläsare, identifierad av ett
slumpat id i en cookie, med typade attribut, utgång efter inaktivitet, en övre gräns för antalet
sessioner, nytt id (`request.changeSessionId()`) och ogiltigförklaring (`session.invalidate()`).
Det här är **11a**, första delen av behovet "Inloggning med sessioner och CSRF-skydd för ett
JSON-API" från appen psykologen. **11b** (inloggning och utloggning med sessionen i
`spola.security.web`) och **11c** (CSRF-skydd med `XSRF-TOKEN`) ligger redan som uppdragsfiler i
`uppdrag/` och bygger på det här. Sessions-id:t är en hemlighet som ska bära inloggningen i 11b,
så koden är säkerhetskritisk: PROGRESS.md ska säga att en människa bör granska den innan
ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–10 är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-inloggning-sessioner-csrf.md`.

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "Web del 1" (filtrens ordning
och omfång, attribut, att undantag mappas där de uppstår), "Web del 3" (cookieparsningen,
`ResponseCookie` och dess standardvärden, `CookieManager` och `Max-Age`) och "Säkerhet del 2"
(identiteten som attribut, varför ett saknat filter ger 500). Alla tester skrivs med
`spola.test`.

Det som finns och som 11a bygger på:
- `spola.web`: `Filter`/`Filter.Chain` (ett filter ser svaret efter `next.proceed`, också svar
  från mappade undantag), `RouterBuilder.filter(...)`, `WebRequest.withAttribute`/`attribute`
  med `AttributeKey<T>` (identitetsbaserad nyckel; alla `withAttribute`-kopior bär samma
  värden), `WebRequest.cookieAll(name)` (strikt RFC 6265-tolkning, felaktiga par hoppas över,
  högst 200, citattecken behålls), `ResponseCookie` (oföränderlig, även `Builder`; standard
  `Path=/`, `HttpOnly`, `SameSite=Lax`, inte `Secure`, ingen `Max-Age`; validering i `build()`),
  `Responses.withCookie`, `WebException` och `Problem`.
- `spola.web` loggar på `System.Logger` `spola.web`, och `RouterBuilder.log(System.Logger)` är
  publik så att tester kan läsa routerns loggrader. Routern loggar inte `WebException`.
- Testerna i `spola.web` kör mot en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` och
  rå socket (`Support.java`).
- JDK:ns `CookieManager` skickar tillbaka en sessionscookie (utan `Max-Age`) oförändrad och tar
  bort en cookie som får `Max-Age=0` (se "Web del 3").

Behovets användningsfall för den här delen (behovets namn behålls):

```java
var sessions = Sessions.inMemory(SessionOptions.DEFAULT
        .withIdleTimeout(Duration.ofMinutes(30))
        .withCookie(cookie -> cookie.secure(config.booleanValue("cookie.secure"))));   // HttpOnly, SameSite=Lax
```

Behovets krav som gäller 11a: sessions-id från en kryptografiskt säker slumpkälla, minst 128
bitar, och jämförelser som inte läcker tid; sessioner går ut efter inaktivitet (30 minuter i
appen); utgångna sessioner städas bort och antalet sessioner i minnet har en övre gräns, så att
anonyma förfrågningar inte kan fylla minnet; sessioner skapas bara när något sparas i dem (ett
anrop till `/actuator/health` skapar ingen); `Secure` på cookien måste kunna sättas uttryckligen,
eftersom appen kör bakom en TLS-proxy. Säkerhetskritiskt: konstruerad indata utifrån
(cookievärden) räknas som realistisk.

Erfarenhet från tidigare iterationer: text som kan hamna i en loggrad, ett undantag eller ett
problem-svar ska vara kort och fri från råa styrtecken, och hemligheter (här sessions-id:n) får
aldrig ekas. Gränser ska kontrolleras innan arbete eller minne hinner växa. Beteendet för `null`,
tomma värden, felaktiga data och fel i användarkod ska vara bestämt och testat. Säkerhetsbeslut
ska vara fail-closed.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **I `spola.web`, ingen ny modul.** Sessioner hör till webblagret i Spring (servlet-API:ts
  `HttpSession`, Tomcats sessionshantering), inte till Spring Security, och de används också
  utan inloggning (en varukorg, ett formulär i flera steg). MONSTER.md säger att en befintlig
  modul byggs ut före en ny. Inga nya `requires` (`SecureRandom` och `MessageDigest` finns i
  `java.base`), inga ändringar i `spola.http` eller någon annan modul. 11b kopplar sessionen
  till `spola.security.web`.
- **API** (paketet `spola.web`):

  ```java
  static final AttributeKey<Cart> CART = AttributeKey.of("cart", Cart.class);

  Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(Duration.ofMinutes(30)));
  Router router = Router.builder()
          .filter(sessions.filter())
          .post("/cart/items", request -> {
              Session session = Sessions.getOrCreate(request);              // request.getSession()
              Cart cart = session.get(CART).orElseGet(Cart::empty);
              session.put(CART, cart.add(request.readJson(Item.CODEC)));
              return Responses.noContent();
          })
          .get("/cart", request -> Responses.json(request, 200, Cart.CODEC,
                  Sessions.find(request).flatMap(s -> s.get(CART)).orElseGet(Cart::empty)))  // getSession(false)
          .build();
  ```

  - `Sessions` (final klass, oföränderlig konfiguration, trådsäker): `inMemory(SessionOptions)`,
    `inMemory(SessionOptions, InstantSource)` (klockan för utgång; tester styr den, standard
    systemklockan), `filter()`, `size()` (antalet sessioner i minnet, inklusive utgångna som
    ännu inte städats bort), och de statiska metoderna `find(WebRequest)` →
    `Optional<Session>`, `getOrCreate(WebRequest)` → `Session`, `renew(WebRequest)` → `Session`
    och `invalidate(WebRequest)`.
  - `Session` (final klass, trådsäker): `get(AttributeKey<T>)` → `Optional<T>`,
    `put(AttributeKey<T>, T)` och `remove(AttributeKey<?>)`. Attributen har samma typade
    `AttributeKey` som förfrågans attribut; `put` kontrollerar värdet mot nyckelns typ och
    avvisar `null`. Värdena sparas som referenser (ingen kopiering eller serialisering).
    **Ingen `id()`**: id:t är en hemlighet och applikationen behöver det inte. `toString()`
    visar antalet attribut, aldrig id:t eller värdena.
  - `SessionOptions` (record med `DEFAULT` och `withX`-metoder): `idleTimeout` (standard 30
    minuter, som Spring Boot), `maxSessions` (standard 10 000), `cookieName` (standard
    `SESSION`, som Spring Session) och `cookie` (`UnaryOperator<ResponseCookie.Builder>`,
    standard oförändrad builder).

  Skapa, förnya och ogiltigförklara är statiska metoder med förfrågan, inte metoder på
  `Session`, eftersom de ändrar svaret (`Set-Cookie`) och tillståndet för det hör till
  förfrågan, på samma sätt som `WebSecurity.identity(request)` i 10b. Motiveras i
  ARCHITECTURE.md.
- **Filtret.** `sessions.filter()` registreras på routern, före filter som använder sessionen.
  Det läser sessionscookien, slår upp sessionen och lägger ett internt tillstånd som attribut
  under en nyckel som bara `spola.web` har. Sedan anropar det `next.proceed` och lägger till
  `Set-Cookie` på svaret om sessionen skapats, fått nytt id eller ogiltigförklarats under
  förfrågan. Det gäller alla svar som kommer tillbaka genom filtret, också svar från mappade
  undantag. Filtret skapar aldrig en session på egen hand. Når filtret en förfrågan som redan
  har tillståndet (registrerat två gånger, eller två `Sessions` på samma router) kastar det
  `IllegalStateException` (500). `find`, `getOrCreate`, `renew` och `invalidate` kastar
  `IllegalStateException` (500) när filtret inte har körts, med ett meddelande som säger att
  `filter()` ska registreras. En glömd registrering ska märkas i stället för att alla
  förfrågningar ser ut att sakna session.
- **Sessions-id.** 32 byte från en `SecureRandom` (en `new SecureRandom()` per `Sessions`, inte
  `getInstanceStrong()`, som kan blockera), kodade som Base64url utan utfyllnad: exakt 43
  tecken ur `[A-Za-z0-9_-]`, alltså 256 bitar. Bara servern skapar id. Ett id som klienten har
  hittat på blir aldrig en session (ingen "adoption" av okända id).
- **Uppslagning utan tidsläcka.** Lagringen är en `ConcurrentHashMap` vars nyckel är SHA-256
  av id:t (id:ts ASCII-byte). Själva id:t sparas inte i lagringen, i `Session` eller i något
  annat fält som lever efter att svaret byggts. Tiden för en uppslagning beror då bara på
  hashvärdet av klientens text, inte på hur stor del av ett giltigt id den har gissat rätt, och
  en minnesdump innehåller inga giltiga id. Ett cookievärde som inte har exakt formatet ovan
  (längd, alfabet, inga citattecken) slås aldrig upp.
- **Cookien in.** Alla värden med namnet `cookieName` (`cookieAll`) provas i ordning, och det
  första som pekar på en levande session används, som i Tomcat. Det kan finnas flera när en
  gammal cookie med en annan `Path` eller `Domain` ligger kvar. Ett felaktigt, okänt eller
  utgånget värde ger ingen session, inget 400 och inget `Set-Cookie`.
- **Cookien ut.** `ResponseCookie.of(cookieName, id)` (standard `Path=/`, `HttpOnly`,
  `SameSite=Lax`, ingen `Max-Age`, alltså en sessionscookie) och därefter `cookie`-funktionen:
  `withCookie(c -> c.secure(true))` ger `SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax`.
  Borttagning: `ResponseCookie.of(cookieName, "")` genom samma funktion och sist
  `maxAge(Duration.ZERO)`, så att `Path`, `Domain` och `Secure` stämmer med cookien som sattes.
  `Secure` sätts bara när funktionen säger det. Det sker inget automatiskt efter schemat, eftersom
  schemat är `http` bakom en TLS-proxy. Ett svar som sätter eller tar bort sessionscookien får
  `Cache-Control: no-store` om det saknar `Cache-Control`, så att en delad cache inte sparar ett
  svar med ett sessions-id. Ett svar får högst ett `Set-Cookie` för sessionen, med det slutliga
  utfallet: skapad och sedan ogiltigförklarad i samma förfrågan ger bara borttagningen,
  ogiltigförklarad och sedan skapad ger bara det nya id:t.
- **Livscykel.**
  - `find` skapar aldrig en session.
  - `getOrCreate` ger förfrågans levande session eller skapar en ny (och svaret sätter
    cookien).
  - `renew` motsvarar `changeSessionId` och skyddar mot session fixation. Har förfrågan en
    levande session får den ett nytt id atomärt: det gamla id:t slutar gälla direkt och
    attributen behålls. Annars skapas en ny tom session.
  - `invalidate` tar bort sessionen med alla attribut, och svaret tar bort cookien. Utan
    session gör den ingenting (inget `Set-Cookie`).
  - Ett `Session`-objekt vars session har ogiltigförklarats (av vilken förfrågan som helst)
    eller gått ut: `get` ger tomt, `remove` gör ingenting och `put` kastar
    `IllegalStateException` (som `HttpSession`).
- **Utgång och städning.** En session går ut när `idleTimeout` har gått sedan den senast
  användes (utgången när `nu >= senast använd + idleTimeout`). Att filtret hittar sessionen för
  en förfrågan räknas som användning, även om handlern aldrig läser den, och det gör också att
  skapa och förnya den. Tiden kommer från `InstantSource`. En utgången session tas bort när den
  slås upp. Filtret städar också bort alla utgångna sessioner högst en gång per minut (enligt
  klockan), på en förfrågan som passerar det. Det finns ingen bakgrundstråd, så `Sessions`
  behöver ingen `close()`. Städningen körs dessutom alltid innan en ny session nekas vid
  gränsen.
- **Gränsen.** Högst `maxSessions` sessioner i minnet, också när många förfrågningar skapar
  sessioner samtidigt. Är gränsen nådd även efter städningen ger `getOrCreate` (och `renew`
  utan session) `WebException` 503 som problem-svar med en fast detalj och utan `Set-Cookie`.
  Befintliga sessioner påverkas inte och tas aldrig bort för att ge plats, så att den som kan
  skapa sessioner inte kan logga ut andra. Att förnya en befintlig session räknas inte som en
  ny.
- **Kontroll av `SessionOptions`.** Den kanoniska konstruktorn (och därmed varje `withX`)
  kontrollerar sina värden och samlar alla problem i ett `IllegalArgumentException` med en rad
  per problem: `idleTimeout` minst 1 sekund och högst 365 dagar; `maxSessions` 1–10 000 000;
  `cookieName` en `token` (RFC 9110 §5.6.2). Ett `null` ger `NullPointerException`.
  `Sessions.inMemory` provbygger cookien och borttagningen genom `cookie`-funktionen och ger
  `IllegalArgumentException` om funktionen returnerar `null` eller om `build()` avvisar
  resultatet (till exempel `SameSite=None` utan `Secure`, eller `__Host-` med `Domain`). Fel i
  inställningarna märks alltså vid uppstart och inte på första inloggningen.

## Krav
- `Sessions`, `Session` och `SessionOptions` i `spola.web` enligt besluten ovan, med Javadoc
  för hela det publika API:t, inklusive var filtret ska registreras, id:ts format och
  slumpkälla, livscykeln, utgången och städningen, gränsen och 503, cookiens attribut och
  `Cache-Control`, och vad som händer utan filtret.
- `module-info.java` i `spola.web` får en mening om sessionerna i sin Javadoc; `requires` och
  `exports` är oförändrade.
- `README.md` i `modules/web/` får ett avsnitt "Sessioner". Det visar Spring Boot-kod
  (`HttpSession` i en `@RestController` med `getAttribute`/`setAttribute`,
  `request.changeSessionId()`, `session.invalidate()`, `server.servlet.session.timeout=30m` och
  `server.servlet.session.cookie.secure=true`) bredvid Spola-kod. Spola-delen är en testkälla
  som kompileras, och ett test kontrollerar att README visar den oförändrad (som för de andra
  README-exemplen i modulen). Avsnittet har nya rader i jämförelsetabellen och avvikelserader:
  ingen `getId()`, 503 vid gränsen i stället för att ta bort sessioner, cookienamnet `SESSION`
  i stället för `JSESSIONID`, inget automatiskt `Secure` över HTTPS, ingen persistens eller
  delning mellan servrar, städning utan bakgrundstråd, `Cache-Control: no-store` när cookien
  sätts. Det rekommenderar `__Host-SESSION` med `secure(true)` i produktion.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i
   `spola.web`s svit (registrerade i `WebTests`).
2. `requires` och `exports` i `modules/web/src/module-info.java` är oförändrade, och testet i
   `SourceRulesTests` som fäster listan är oförändrat. Huvudkoden importerar inget från
   `java.lang.reflect` eller `java.lang.invoke`. `git diff` visar inga ändringar i `Build.java`
   och inga under andra modulers `src/` eller `test/`. Befintliga testfiler i `spola.web` är
   oförändrade utom registreringen i `WebTests` och README-kontrollen, om den behöver känna
   till den nya testkällan.
3. Behovets användningsfall: ett test bygger
   `Sessions.inMemory(SessionOptions.DEFAULT.withIdleTimeout(Duration.ofMinutes(30)).withCookie(cookie -> cookie.secure(secure)))`
   med en lokal `boolean secure` i stället för `config.booleanValue(...)`. Över rå socket visar
   det att svaret som skapar sessionen har exakt `Set-Cookie: SESSION=<id>; Path=/; Secure;
   HttpOnly; SameSite=Lax` med `secure = true`, utan `; Secure` med `false` och med `DEFAULT`,
   och att `<id>` matchar `[A-Za-z0-9_-]{43}`. Svaret har `Cache-Control: no-store`, och ett
   svar vars handler redan satt `Cache-Control: private, max-age=60` behåller sitt värde.
   `withCookieName("__Host-SESSION")` med `secure(true)` fungerar.
4. Genom en riktig `HttpServer` med `HttpClient` och `CookieManager`, och en router med
   `sessions.filter()`, en räknare (`getOrCreate` och `put`) och en väg som inte rör sessionen,
   visar ett test att:
   - vägen som inte rör sessionen inte ger något `Set-Cookie` och att `sessions.size()` förblir
     0, också med ett välformat okänt sessions-id och med ett felaktigt värde i cookien;
   - första anropet till räknaren skapar exakt en session (ett `Set-Cookie`) och att följande
     anrop inte ger något `Set-Cookie` medan räknaren går 1, 2, 3; en andra klient får en egen
     session och en egen räknare;
   - en väg som bara anropar `find` aldrig skapar en session.
5. Ett test av livscykeln visar att:
   - efter `renew` slutar det gamla id:t att fungera direkt (skickat igen över rå socket ger
     `find` tomt), det nya id:t skiljer sig från det gamla, attributen finns kvar och `size()`
     är oförändrat; `renew` utan session skapar en ny;
   - `invalidate` ger exakt `Set-Cookie: SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax`
     (med `Secure`, `Path` och `Domain` från funktionen när den sätter dem), att det gamla
     id:t sedan inte fungerar och att `size()` minskar; `invalidate` utan session ger inget
     `Set-Cookie`;
   - skapa och ogiltigförklara i samma förfrågan bara ger borttagningen, och ogiltigförklara
     och sedan `getOrCreate` bara ger det nya id:t (ett `Set-Cookie` för sessionen i båda
     fallen);
   - ett `Session`-objekt efter `invalidate` ger tomt från `get`, att `remove` inte gör något
     och att `put` kastar `IllegalStateException`;
   - `put` med ett värde av fel typ (genom en rå nyckel) och med `null` avvisas.
6. Ett test med en styrd `InstantSource` visar att en session lever `idleTimeout` minus en
   sekund efter senaste användningen och är borta precis vid `idleTimeout`, att varje förfrågan
   med cookien skjuter utgången framåt (också till en väg som inte läser sessionen), och att en
   förfrågan utan cookie, när klockan passerat `idleTimeout` plus en minut, städar bort alla
   utgångna sessioner så att `size()` blir 0.
7. Ett test med `maxSessions` 3 visar att den fjärde `getOrCreate` ger 503
   (`application/problem+json`, utan `Set-Cookie`), att de tre befintliga sessionerna
   fortsätter att fungera, att förfrågningar som inte skapar sessioner fortfarande ger 200, att
   `renew` av en befintlig session fungerar vid gränsen, och att en ny session kan skapas så
   fort en av de tre har gått ut, utan att vänta på minutstädningen.
8. Ett tabelldrivet test med minst 15 konstruerade värden på cookien `SESSION` visar att varje
   värde ger 200 (inte 400 eller 500), ingen session och inget `Set-Cookie` på en väg som anropar
   `find`. Värdena ska bland annat vara: tomt, 42 och 44 tecken, tecknen `+`, `/` och `=` ur
   standard-Base64, ett giltigt id inom citattecken, ett giltigt id med ett extra tecken, ett
   välformat men okänt id, ett id som ogiltigförklarats, ett utgånget id, ett id som ersatts av
   `renew` och ett id från en annan `Sessions`. Testet visar också att med två
   `SESSION`-värden, där det första är okänt och det andra levande, används det levande.
9. Ett test skapar 10 000 sessioner och visar att alla id är olika och matchar
   `[A-Za-z0-9_-]{43}`. Koden visar vid läsning: en `new SecureRandom()` per `Sessions` som
   fyller 32 byte per id, nyckeln i lagringen är SHA-256 av id:t, och id:t sparas inte i
   lagringen, i `Session` eller i något annat fält som lever efter att svaret byggts.
10. Ett tabelldrivet test av `SessionOptions` och `Sessions.inMemory` visar att `idleTimeout`
    0, negativ, under 1 sekund och över 365 dagar, `maxSessions` 0, -1 och 10 000 001,
    `cookieName` tom, `"a b"` och `"a=b"`, och en `cookie`-funktion som returnerar `null`, ger
    `SameSite=None` utan `Secure` eller sätter `Domain` på ett `__Host-`-namn, ger
    `IllegalArgumentException`, och att `null` ger `NullPointerException`. Den kanoniska
    konstruktorn med tre felaktiga värden ger ett undantag med tre rader.
11. Ett test visar att `find`, `getOrCreate`, `renew` och `invalidate` i en router utan
    `sessions.filter()` ger 500 (`IllegalStateException`), och att `sessions.filter()`
    registrerat två gånger ger 500.
12. Ett test med 200 virtuella trådar, var och en med en egen `HttpClient` och `CookieManager`
    mot samma server, skapar en session, räknar upp tio gånger, gör `renew`, räknar upp en gång
    till och gör `invalidate`. Varje tråd ser exakt sin egen räknarföljd, alla id är olika och
    `size()` är 0 efteråt. Ett andra test med 200 virtuella trådar som samtidigt anropar
    `getOrCreate` mot `maxSessions` 100 visar att exakt 100 lyckas, 100 får 503 och `size()`
    är 100.
13. Ett test visar med markörer att inget sessions-id som testerna sett i ett `Set-Cookie`
    finns i någon loggrad som routern skriver under sessionstesterna, i `toString()` för
    `Session`, `Sessions` eller `SessionOptions`, i något undantagsmeddelande eller i någon
    problem-kropp.
14. `README.md` i `modules/web/` har avsnittet "Sessioner" enligt kraven, med Spola-exemplet
    som kompilerad testkälla som ett test jämför med README.
15. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
16. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md beskriver det levererade,
    säger att 11b och 11c återstår för behovet, säger att koden är säkerhetskritisk och bör
    granskas av en människa, och listar kända begränsningar: sessionerna försvinner vid omstart
    och delas inte mellan servrar, ingen loggrad när gränsen nås, och två samtidiga `renew` på
    samma session ger två `Set-Cookie` där det som webbläsaren tar emot sist gäller.
    ARCHITECTURE.md får ett avsnitt "Sessioner (`spola.web`)" som beskriver:
    - varför i `spola.web` och inte i en ny modul eller i `spola.security.web`;
    - API:ts form med statiska metoder som tar förfrågan;
    - id:t, slumpkällan och uppslagningen på SHA-256;
    - cookien in och ut och `Cache-Control`;
    - livscykeln, utgången och städningen utan bakgrundstråd;
    - gränsen med 503 i stället för att ta bort sessioner.

    Raden för `spola.web` i modulkatalogen i `framework/MONSTER.md` (motsvarar också
    `HttpSession`, ingångarna `Sessions` och `Session`) och i modultabellen i `README.md`
    (sessioner) är uppdaterade.

## Utanför scope
- Inloggning, utloggning och identiteten i sessionen (11b). CSRF och `XSRF-TOKEN` (11c).
- Sessioner som överlever en omstart eller delas mellan servrar (Spring Session med JDBC eller
  Redis), serialisering av attribut och ett utbytbart lagringsgränssnitt.
- Absolut maxlivstid, "kom ihåg mig", gräns för samtidiga sessioner per användare och
  händelser vid skapande och utgång (`HttpSessionListener`).
- Sessions-id i URL:en (`;jsessionid=`) eller i ett huvud (`X-Auth-Token`).
- Automatiskt `Secure` efter schemat och `X-Forwarded-Proto` (ett eget behov väntar:
  `uppdrag/behov/2026-10-13-proxyhuvuden.md`).
- Loggning och mätvärden när gränsen nås (utöver `size()`).

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion, ingen
`ThreadLocal` och inga namnkonventioner. Slumptal och hashning med JDK:ns primitiver
(`SecureRandom`, `MessageDigest` med SHA-256, `java.util.Base64`). Inga nya filtyper i
`framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
