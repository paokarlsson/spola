# Spec: Proxy del 2 – Secure på sessions- och CSRF-cookies efter schemat

## Uppgift
Låt sessionscookien (`spola.web`) och CSRF-cookien (`spola.security.web`) få `Secure` när förfrågans schema är `https` (också via `ForwardedHeaders` bakom en proxy), som i Spring. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261014-0234-cookies-secure-efter-schemat/mission.md` (källa `uppdrag/13b-cookies-secure-efter-schemat.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
