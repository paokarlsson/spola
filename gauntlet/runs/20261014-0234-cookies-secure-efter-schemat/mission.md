Source: uppdrag/13b-cookies-secure-efter-schemat.md

# Bakom en omvänd proxy del 2: `Secure` efter schemat på sessions- och CSRF-cookien

## Mål
Sessionscookien (`spola.web`) och `XSRF-TOKEN` (`spola.security.web`) ska få `Secure` när
förfrågan är `https`, som i Spring. Tomcat sätter `Secure` på `JSESSIONID` när
`request.isSecure()`, och `CookieCsrfTokenRepository` sätter `secure` efter `request.isSecure()`
när inget annat är sagt. Med `ForwardedHeaders` från 13a är schemat `https` också bakom Caddy, så
cookierna blir `Secure` utan att appen konfigurerar något. 11a och 11c beslutade att `Secure`
"aldrig sätts efter schemat, eftersom schemat är `http` bakom en TLS-proxy", och skälet för det
beslutet försvinner med 13a. Det här är **13b**, sista delen av behovet "Rätt schema och
klientadress bakom en omvänd proxy" från appen psykologen. Koden är säkerhetskritisk.
PROGRESS.md ska säga att en människa bör granska den innan ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–12 och 13a är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-proxyhuvuden.md`. Det här uppdraget gäller meningen
"Filter som körs efter, till exempel säkerhetsfiltret och en CSRF-cookie med `Secure` efter
schemat, ska se klientens schema och adress".

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar. Läs särskilt "Sessioner" (cookien ut och
borttagningen), "Inloggning med session", "CSRF-skydd" och "Bakom en omvänd proxy" (13a). Alla
tester skrivs med `spola.test`.

Det som finns och som uppdraget bygger på:
- `Sessions.inMemory(SessionOptions)` (11a) kör cookie-funktionen en gång. Mallen ger attributen
  för varje sessionscookie och för borttagningen (`Max-Age=0`, med samma `Path`, `Domain` och
  `Secure`). `Secure` sätts bara när funktionen säger det. `sessions.filter()` skriver ett
  `Set-Cookie` med det slutliga utfallet för förfrågan. `WebSecurity` kör sessionsfiltret självt,
  inne i `security.filter()` (11b).
- `Csrf` (11c) bygger en mall av `withCookie`-funktionen när `WebSecurity` byggs.
  `Protection.begin(WebRequest)` körs för varje förfrågan och `newCookie()` ger en ny
  `XSRF-TOKEN`. Cookien sätts på första svaret och vid `login` och `logout`.
- `WebRequest.scheme()` och `ForwardedHeaders.trusting(TrustedProxies)` kommer från 13a. Bara
  `ForwardedHeaders` kan ändra schemat, och det ska registreras först.
- `CsrfExample.router(RouterBuilder, WebSecurity, Authenticator)` tar en `RouterBuilder`. Ett test
  kan alltså ge den `Router.builder().filter(ForwardedHeaders.trusting(...))`.
- Meningarna som säger att `Secure` aldrig sätts efter schemat finns i:
  - `Sessions.java`, `SessionOptions.java` och `Csrf.java`;
  - `modules/web/README.md` (exemplet `CartApp` och avvikelseraden "`Secure` på sessionscookien");
  - `modules/security-web/README.md` med exemplen `LoginExample` och `CsrfExample`;
  - ARCHITECTURE.md (avsnitten för 11a och 11c) och PROGRESS.md (kända problem för sessioner);
  - avvikelseraden från 13a om att `Secure` ännu inte följer schemat.
- Har tidigare uppdrag levererat andra namn än de som står här gäller de levererade.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Regeln.** En cookie får `Secure` när cookie-funktionen sätter det, som i dag, **eller** när
  förfrågans schema är `https`. Regeln gäller:
  - sessionscookien när en session skapas eller förnyas;
  - borttagningen av sessionscookien. En cookie som sattes över `https` tas då bort med `Secure`
    över `https`;
  - `XSRF-TOKEN` på första svaret, vid `login` och vid `logout`, och på alla svar som 11c ger
    cookien (200, 401, 403 från en regel, 403 från CSRF-kontrollen, 404).
- **Vilket schema som gäller.** Schemat är `scheme()` för den `WebRequest` som filtret fick:
  `sessions.filter()` för sessionscookien och `security.filter()` för `XSRF-TOKEN`. När
  `WebSecurity` kör sessionsfiltret självt gäller förfrågan som säkerhetsfiltret fick. `login` och
  `logout` i handlern ändrar inte vilket schema som gäller. `ForwardedHeaders` ska därför
  registreras före `sessions.filter()` och `security.filter()`. Registreras det efter blir
  cookierna inte `Secure` bakom en proxy. Det står i Javadoc och README.
- **Över direkt TLS** är `scheme()` redan `https`, så samma regel gäller där.
- **Det finns inget sätt att stänga av regeln.** Spring kan sätta `setSecure(false)`, men Spola
  har inget val för att låta bli `Secure` över `https`. En app på samma origin behöver inte en
  cookie utan `Secure` som sattes över `https`. README har en avvikelserad om det.
- `secure(true)` i funktionen gäller som förut, också över `http`.
- Kontrollerna vid uppstart är oförändrade. Till exempel kräver `SameSite=None` fortfarande
  `secure(true)` i funktionen, eftersom en förfrågan över `http` annars skulle ge en cookie som
  webbläsaren avvisar.
- **Inget nytt API.** Inga publika typer eller metoder tillkommer. Bara beteendet och
  dokumentationen ändras. Mallarna ändras inte efter uppstart. Beslutet fattas för varje
  förfrågan utan delat föränderligt tillstånd.

## Krav
- Regeln enligt besluten i `Sessions` och i `Csrf`/`WebSecurity`.
- Javadoc för `Sessions`, `SessionOptions` och `Csrf` beskriver regeln och ordningen med
  `ForwardedHeaders`. Javadoc i `WebSecurity` gör det också, om den nämner `Secure`.
- Varje mening som säger att `Secure` aldrig sätts efter schemat ersätts. Det gäller Javadoc,
  README-filerna, kommentarerna i de exempel som README visar (`CartApp`, `LoginExample`,
  `CsrfExample`) och avvikelserader. Exemplens kod ändras inte.
- `README.md` i `modules/security-web/` visar i avsnittet "CSRF-skydd för en SPA" hur appen kör
  bakom Caddy: `ForwardedHeaders` registreras först och båda cookierna blir `Secure` utan
  `secure(true)`.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0. De nya testerna körs i
   `spola.web.WebTests` och `spola.security.web.SecurityWebTests`.
2. Modulernas gränser:
   - `requires` och `exports` i `modules/web/src/module-info.java` och
     `modules/security-web/src/module-info.java` är oförändrade.
   - Inga publika typer eller metoder tillkommer.
   - `git diff` visar inga ändringar i `Build.java`. Under `src/` och `test/` ändras bara `web` och
     `security-web`.
   - Befintliga testfiler är oförändrade utom i tre avseenden: registreringen av nya tester,
     README-testerna för de rader och meningar som ändras, och kommentarerna i exemplen. Varje
     befintlig förväntan på en exakt `Set-Cookie`-rad över `http` gäller oförändrad.
3. Behovets fall: ett test kör flödet från 11c genom en riktig `HttpServer` (127.0.0.1, port 0)
   och `HttpClient`. Routern är `CsrfExample.router` med
   `Router.builder().filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))` och
   sessioner utan `secure(true)`. Varje förfrågan har `X-Forwarded-Proto: https`. Cookierna
   skickas för hand, eftersom `CookieManager`, precis som en webbläsare, inte skickar
   `Secure`-cookies över `http://`. Testet visar att:
   - `GET /actuator/health` ger en `XSRF-TOKEN` med `Secure`;
   - inloggningen ger en `SESSION` och en ny `XSRF-TOKEN`, båda med `Secure`;
   - `/api/auth/me` ger namnet;
   - utloggningen ger borttagningen av `SESSION` och en ny `XSRF-TOKEN`, båda med `Secure`;
   - `/api/auth/me` därefter ger 401;
   - varje `Set-Cookie`-rad tecken för tecken är den rad som `withCookie(c -> c.secure(true))` ger,
     utom id och token.
4. Samma flöde ger exakt raderna från 11a och 11c, utan `Secure` i något `Set-Cookie`, i tre fall:
   - med `X-Forwarded-Proto: http`;
   - utan huvudet;
   - med `TrustedProxies.of("10.0.0.0/8")` och `X-Forwarded-Proto: https`, alltså från en
     obetrodd avsändare.
5. Ett test visar att `X-Forwarded-Proto: https` inte ger något `Secure` när `ForwardedHeaders`
   registreras efter `security.filter()`, eller efter `sessions.filter()` utan `WebSecurity`. Det
   är den dokumenterade ordningen.
6. Ett test i `spola.web` använder bara `Sessions`, med `sessions.filter()` efter
   `ForwardedHeaders`, och visar att:
   - `getOrCreate`, `renew` och `invalidate` ger `Secure` på cookien och på borttagningen över
     vidarebefordrad `https`, och inget `Secure` över `http`;
   - `withCookie(c -> c.secure(true))` ger `Secure` över `http` som förut.
7. Ett test visar att `XSRF-TOKEN` får `Secure` över vidarebefordrad `https` på första svaret
   när svaret är 200, 401, 403 från en regel, 403 från CSRF-kontrollen och 404.
8. Ett test med 200 virtuella trådar blandar `X-Forwarded-Proto: https` och `http`. Det visar att
   varje svars `Set-Cookie` har `Secure` exakt när förfrågan var `https`, och att inga andra
   attribut skiljer sig.
9. Ett test visar att kontrollerna vid uppstart är oförändrade. `SameSite=None` utan
   `secure(true)` ger fortfarande `IllegalArgumentException` för både `Sessions` och `Csrf`.
10. `grep -rn "never from the scheme\|never set from the scheme\|aldrig efter schemat" framework/modules`
    ger inga träffar. ARCHITECTURE.md och PROGRESS.md säger inte längre att `Secure` aldrig sätts
    efter schemat, utom i en uttrycklig anteckning om att 13b ersatte beslutet.
11. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
12. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.
    - PROGRESS.md beskriver det levererade och säger att behovet `2026-10-13-proxyhuvuden` är
      levererat i sin helhet (13a–13b). Det säger att koden är säkerhetskritisk och bör granskas
      av en människa. Det rättar meningen "Inget automatiskt `Secure` efter schemat eller
      `X-Forwarded-Proto` (eget behov)" och raden om `Secure` från 13a. Det listar kända
      begränsningar:
      - fel ordning med `ForwardedHeaders` ger inget `Secure`;
      - det finns inget sätt att stänga av regeln;
      - en borttagning över `http` av en cookie som sattes över `https` får inget `Secure`, och
        webbläsaren kan då ignorera den.
    - ARCHITECTURE.md får ett avsnitt "Bakom en omvänd proxy del 2: `Secure` efter schemat" som
      beskriver:
      - regeln;
      - vilket schema som gäller och ordningen i kedjan;
      - varför beslutet i 11a och 11c ändras;
      - varför regeln inte kan stängas av.

      Besluten i 11a och 11c får en hänvisning dit.

    Modulkatalogen i `framework/MONSTER.md` och modultabellen i `README.md` behöver inte ändras,
    eftersom ingen modul tillkommer och ingen moduls syfte ändras.

## Utanför scope
- Ett val för att aldrig sätta `Secure` och egna regler per cookie efter förfrågan.
- `Secure` på andra cookies än sessionscookien och `XSRF-TOKEN`. En app som sätter egna cookies
  med `Responses.withCookie` väljer själv utifrån `request.scheme()`.
- Ändringar i `ForwardedHeaders`, `TrustedProxies` och `spola.http`.
- Release och ny version, som görs för hand.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion, ingen
`ThreadLocal` och inga namnkonventioner. Inga nya filtyper i `framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
