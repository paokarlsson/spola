Source: uppdrag/11c-security-csrf.md

# Sessioner del 3: CSRF-skydd med `XSRF-TOKEN` (`spola.security.web`)

## Mål
`spola.security.web` ska skydda ändrande anrop mot CSRF med Angulars standardmönster, som
motsvarar Spring Securitys `CookieCsrfTokenRepository.withHttpOnlyFalse()` med
`CsrfTokenRequestAttributeHandler` och ett filter som sätter cookien redan på första svaret.
Servern sätter en cookie `XSRF-TOKEN` som JavaScript kan läsa, klienten skickar värdet i huvudet
`X-XSRF-TOKEN`, och ett ändrande anrop utan matchande huvud får 403. Det här är **11c**, sista
delen av behovet "Inloggning med sessioner och CSRF-skydd för ett JSON-API" från appen
psykologen. Efter den fungerar behovets hela flöde: `GET` ger `XSRF-TOKEN`, inloggning utan
huvudet ger 403, inloggning med huvudet ger 200 och en ny sessionscookie, `/api/auth/me` ger
namnet, och efter utloggning ger `/api/auth/me` 401. Koden är säkerhetskritisk: PROGRESS.md ska
säga att en människa bör granska den innan ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–10, 11a och 11b är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-inloggning-sessioner-csrf.md`.

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "Web del 3" (cookieparsningen,
`ResponseCookie`), "Säkerhet del 2" (filtrets steg, 403-svaret), "Sessioner" (11a) och
"Inloggning med session" (11b). Alla tester skrivs med `spola.test`.

Det som finns och som 11c bygger på:
- `spola.security.web` (10b, 11b): `WebSecurity.builder()` med `authentication(...)`,
  `sessions(...)` och `rule(...)`, `security.filter()` (kör sessionsfiltret självt, ytterst),
  de statiska `identity`, `requireIdentity`, `login` (nytt sessions-id och identiteten i
  sessionen) och `logout` (sessionen tas bort). 403-svaret är `Problem.of(403)` utan `detail`
  med `Cache-Control: no-store`, byggt en gång i `build()`.
- `spola.web`: `WebRequest.cookieAll(name)`, `WebRequest.headers()`, `ResponseCookie` (standard
  `Path=/`, `HttpOnly`, `SameSite=Lax`; `httpOnly(false)` slår av `HttpOnly`) och
  `Responses.withCookie`.
- Har 11a eller 11b levererat andra namn än de som står här gäller de levererade (se
  PROGRESS.md och ARCHITECTURE.md).

Behovets användningsfall är koden i 11b med en rad till i buildern:

```java
var security = WebSecurity.builder()
        .sessions(sessions)
        .csrf(Csrf.cookie())                                  // XSRF-TOKEN / X-XSRF-TOKEN, 403 vid fel
        .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
        .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
        .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
        .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
        .build();
```

Avvikelserna från behovets kod som beslutades i 11b gäller också här: ingen
`.unauthenticated(...)`, och `Responses.json(request, status, JsonValue)` i stället för
`Map.of(...)`.

Behovets krav som gäller 11c: `GET`, `HEAD`, `OPTIONS` och `TRACE` kontrolleras inte, och övriga
metoder kräver att huvudet matchar cookien, annars 403. Också `login` och `logout` kontrolleras,
som i Spring. Cookien `XSRF-TOKEN` är inte `HttpOnly`, har `Path=/` och sätts på första svaret,
så att Angular har den före första `POST`. `Secure` måste kunna sättas uttryckligen (TLS-proxy).
`login` ger en ny CSRF-token. Säkerhetskritiskt: konstruerad indata utifrån räknas som
realistisk.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **`Csrf`** (final klass, oföränderlig, trådsäker) i `spola.security.web`: `Csrf.cookie()` och
  `withCookie(UnaryOperator<ResponseCookie.Builder>)`, till exempel
  `Csrf.cookie().withCookie(c -> c.secure(true))`. Namnen är fasta: cookien `XSRF-TOKEN` och
  huvudet `X-XSRF-TOKEN` (Springs och Angulars standard). De finns som konstanter
  `Csrf.COOKIE_NAME` och `Csrf.HEADER_NAME`. `WebSecurity.Builder.csrf(Csrf)` slår på skyddet.
  Två anrop blir en rad i `build()`s `IllegalArgumentException`. Skyddet fungerar med och utan
  `sessions(...)`.
- **Dubbelinskickad cookie, tillståndslöst.** Servern sparar inga token, inte heller i
  sessionen (som Springs `CookieCsrfTokenRepository`). Skyddet bygger på att en annan origin
  inte kan läsa cookien och inte kan sätta ett eget huvud på ett anrop över origin-gränsen utan
  CORS. ARCHITECTURE.md beskriver vad skyddet täcker och vad det inte täcker (XSS, en origin som
  CORS öppnar för).
- **Token.** 32 byte från en `SecureRandom` (en `new SecureRandom()` per `WebSecurity`, inte
  `getInstanceStrong()`), kodade som Base64url utan utfyllnad: exakt 43 tecken ur
  `[A-Za-z0-9_-]`. Ett cookie- eller huvudvärde med annat format räknas som saknat.
- **Cookien.** `ResponseCookie.of("XSRF-TOKEN", token).httpOnly(false)` och därefter
  `withCookie`-funktionen. Standard är `XSRF-TOKEN=<token>; Path=/; SameSite=Lax`, utan `HttpOnly`
  och utan `Secure`. En funktion som slår på `HttpOnly` (Angular kan då inte läsa cookien), som
  returnerar `null` eller vars cookie `build()` avvisar ger `IllegalArgumentException` när
  `WebSecurity` byggs. `build()` provbygger cookien.
- **Kontrollen.** Metoderna `GET`, `HEAD`, `OPTIONS` och `TRACE` kontrolleras inte. Metoden
  jämförs exakt, som i routern, så `get` med gemener kontrolleras. Alla andra metoder kräver
  exakt ett `X-XSRF-TOKEN`-fält med ett välformat värde. Värdet ska vara lika med ett av de
  välformade `XSRF-TOKEN`-värdena i förfrågans cookies, jämfört i konstant tid
  (`MessageDigest.isEqual`). Annars blir svaret 403, samma svar som en nekad åtkomstregel
  (`Problem.of(403)`, ingen `detail`, `Cache-Control: no-store`), och handlern och filtren
  innanför anropas aldrig. Kontrollen gäller alla vägar: `permitAll`-vägar, `login`, `logout`
  och förfrågningar med Basic-uppgifter.
- **Ordningen i filtret.**
  1. Sessionsfiltret (ytterst, från 11b).
  2. 400 för flera `Authorization`-fält (10b).
  3. CSRF-kontrollen.
  4. Identiteten: sessionen och sedan mekanismerna.
  5. Reglerna.

  En förfalskad `POST` når alltså aldrig `Authenticator` (ingen PBKDF2-kostnad) och får 403
  också anonymt på en skyddad väg, som i Spring, där `CsrfFilter` körs före autentiseringen.
- **Cookien sätts på första svaret.** Saknar förfrågan en välformad `XSRF-TOKEN`-cookie får
  svaret ett `Set-Cookie` med en ny token. Det gäller vilket svar som helst som går ut genom
  säkerhetsfiltret: handlerns svar, 401, 403 (också CSRF-kontrollens eget), 404 och mappade
  fel. Det ersätter Springs `CsrfCookieFilter`. En förfrågan som har en välformad cookie får
  ingen ny, så token byts inte vid varje förfrågan.
- **Ny token vid inloggning och utloggning.** Med `csrf(...)` ger `WebSecurity.login` och
  `WebSecurity.logout` svaret en ny token (Springs `CsrfAuthenticationStrategy` och
  `CsrfLogoutHandler`). Klientens gamla token fungerar då inte längre, eftersom cookien som den
  ska matcha har bytts ut. Utan `csrf(...)` rör `login` och `logout` inga token. Ett svar får
  högst ett `Set-Cookie` för `XSRF-TOKEN`.
- **`Cache-Control`.** Ett svar som sätter `XSRF-TOKEN` får `Cache-Control: no-store` om det
  saknar `Cache-Control`, som sessionscookien i 11a.

## Krav
- `Csrf` och `WebSecurity.Builder.csrf(Csrf)` enligt besluten ovan, med Javadoc som beskriver
  mönstret och dess förutsättningar, metoderna som inte kontrolleras, ordningen i filtret, när
  cookien sätts och byts, och att servern inte sparar några token.
- Klass-Javadoc för `WebSecurity` (filtrets steg) och Javadoc i `module-info.java` nämner
  CSRF-skyddet. `requires` och `exports` är oförändrade.
- `README.md` i `modules/security-web/` får ett avsnitt "CSRF-skydd för en SPA". Det visar
  behovets Spring-kod (`csrf(...)` med `CookieCsrfTokenRepository.withHttpOnlyFalse()` och
  `CsrfTokenRequestAttributeHandler`, plus `CsrfCookieFilter`) bredvid Spola-koden och visar
  Angulars `withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })`.
  Spola-delen är behovets fullständiga kod som en testkälla som kompileras, och ett test
  kontrollerar att README visar den oförändrad. Avsnittet har avvikelserader: fasta namn, ingen
  `_csrf`-formulärparameter, ingen maskering av token (`XorCsrfTokenRequestAttributeHandler`),
  inga undantag per väg (`ignoringRequestMatchers`), ingen token i sessionen, kontrollen före
  autentiseringen, och att cookien sätts på första svaret utan ett eget filter.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i
   `spola.security.web.SecurityWebTests`.
2. `requires` och `exports` i `modules/security-web/src/module-info.java` är oförändrade.
   Huvudkoden importerar inget från `java.lang.reflect` eller `java.lang.invoke`. `git diff`
   visar inga ändringar i `Build.java` och inga under andra modulers `src/` eller `test/`, utom
   ett litet tillägg som motiveras i ARCHITECTURE.md. Befintliga testfiler är oförändrade utom
   registreringen i `SecurityWebTests` och README-kontrollen, om den behöver känna till den nya
   testkällan.
3. Behovets acceptans: behovets fullständiga kod (med `.csrf(Csrf.cookie())` och avvikelserna
   från 11b) är en testkälla. Genom en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient`
   och en `CookieManager`, där huvudet `X-XSRF-TOKEN` sätts från cookien i `CookieManager` som
   Angular gör, och med en räknande `UserStore`, visar ett test att:
   - `GET /actuator/health` ger 200 och en `XSRF-TOKEN`-cookie men ingen sessionscookie;
   - `POST /api/auth/login` utan huvudet ger 403, att handlern och `Authenticator` inte
     anropas och att ingen session skapas;
   - inloggning med huvudet ger 200, en ny `SESSION`-cookie och en ny `XSRF-TOKEN` som skiljer
     sig från den före inloggningen;
   - `GET /api/auth/me` ger `{"username":"anna"}`;
   - `POST /api/auth/logout` med token från före inloggningen ger 403, och med den aktuella
     token ger den 200;
   - `GET /api/auth/me` därefter ger 401 utan `WWW-Authenticate`.
4. Över rå socket visar ett test exakt `Set-Cookie: XSRF-TOKEN=<token>; Path=/; SameSite=Lax`
   (utan `HttpOnly`, `<token>` matchar `[A-Za-z0-9_-]{43}`), att
   `Csrf.cookie().withCookie(c -> c.secure(true))` ger `; Secure`, och att cookien sätts på
   första svaret när det är 200, 401, 403 från en regel, 403 från CSRF-kontrollen och 404.
   Testet visar också att en förfrågan med en välformad cookie inte får något nytt
   `Set-Cookie` för `XSRF-TOKEN`, att en förfrågan med en felaktig cookie får ett, att svaret
   har högst ett `Set-Cookie` för `XSRF-TOKEN`, och att `Cache-Control: no-store` läggs till
   bara när svaret saknar `Cache-Control`.
5. Ett tabelldrivet test med minst 20 fall för `POST`, `PUT`, `PATCH` och `DELETE` visar att
   varje fall ger 403 utan att handlern eller `Authenticator` anropas. Fallen ska bland annat
   vara: inget huvud, ingen cookie, tomt huvud, huvud och cookie som skiljer sig i sista
   tecknet, 42 och 44 tecken, tecken ur standard-Base64 (`+`, `/`, `=`), token inom
   citattecken, token med ändrat skiftläge, två `X-XSRF-TOKEN`-fält, ett huvud som matchar en
   felaktig cookie, en egen metod (`PURGE`) och `post` med gemener. Testet visar också att
   `GET`, `HEAD`, `OPTIONS` och `TRACE` passerar kontrollen utan huvud (och får det svar
   routern och reglerna ger) och att ett huvud som matchar det andra av två
   `XSRF-TOKEN`-värden godtas.
6. Ett test visar att skyddet fungerar utan sessioner: med bara `HttpBasic` ger en `POST` med
   rätt Basic-uppgifter men utan huvud 403 utan att `Authenticator` anropas, och med huvudet
   ger den handlerns svar. Ett test visar att `login` och `logout` utan `csrf(...)` inte sätter
   någon `XSRF-TOKEN`.
7. Ett test visar att `csrf(...)` två gånger ger `IllegalArgumentException` från `build()` med
   en rad för det, och att en `withCookie`-funktion som slår på `HttpOnly`, returnerar `null`
   eller ger `SameSite=None` utan `Secure` ger `IllegalArgumentException` när `WebSecurity`
   byggs.
8. Ett test med 200 virtuella trådar, var och en med en egen `HttpClient` och `CookieManager`,
   kör flödet i kriterium 3 (inloggning som en av fem användare). Varje tråd får exakt de
   förväntade svaren, och alla token och sessions-id är olika mellan trådarna.
9. Ett test visar med markörer att inget lösenord, ingen CSRF-token och inget sessions-id finns
   i något svar (utom i det egna `Set-Cookie`), i någon loggrad som routern skriver under
   testerna eller i något undantagsmeddelande.
10. `README.md` i `modules/security-web/` har avsnittet "CSRF-skydd för en SPA" enligt kraven,
    med Spola-exemplet som kompilerad testkälla som ett test jämför med README.
11. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
12. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md beskriver det levererade,
    säger att behovet `2026-10-13-inloggning-sessioner-csrf` är levererat i sin helhet (11a–11c)
    och att koden är säkerhetskritisk och bör granskas av en människa. Det listar kända
    begränsningar (skyddet faller vid XSS och för origins som CORS öppnar för, fasta namn, ingen
    formulärparameter) och rättar meningen om CSRF under kända problem för
    `spola.security.web`. ARCHITECTURE.md får ett avsnitt "CSRF-skydd (`spola.security.web`)"
    som beskriver:
    - mönstret med dubbelinskickad cookie och varför servern inte sparar token;
    - token och jämförelsen i konstant tid;
    - metoderna som inte kontrolleras;
    - ordningen i filtret och varför kontrollen kommer före autentiseringen;
    - när cookien sätts och byts.

    Raden för `spola.security.web` i modulkatalogen i `framework/MONSTER.md` (CSRF, `Csrf`) och
    i modultabellen i `README.md` är uppdaterade.

## Utanför scope
- CORS och förfrågningar från andra origins.
- En `_csrf`-parameter i formulärkroppar, maskerade token (BREACH-skydd), egna namn på cookie
  och huvud, undantag per väg (`ignoringRequestMatchers`) och token som sparas i sessionen
  (`HttpSessionCsrfTokenRepository`).
- JWT, säkerhetshuvuden, hastighetsbegränsning och auditloggning.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion, ingen
`ThreadLocal` och inga namnkonventioner. Slumptal och jämförelser med JDK:ns primitiver
(`SecureRandom`, `MessageDigest.isEqual`, `java.util.Base64`). Inga nya filtyper i
`framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
