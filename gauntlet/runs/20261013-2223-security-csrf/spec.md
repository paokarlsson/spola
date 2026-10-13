# Spec: Sessioner del 3 – CSRF-skydd med XSRF-TOKEN (spola.security.web)

## Uppgift
Bygg CSRF-skydd i `spola.security.web` med Angulars standardmönster (`Csrf.cookie()`, cookien `XSRF-TOKEN` och huvudet `X-XSRF-TOKEN`, 403 vid fel, ny token vid login och logout), och visa behovets hela flöde med `HttpClient` och `CookieManager`. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261013-2223-security-csrf/mission.md` (källa `uppdrag/11c-security-csrf.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
