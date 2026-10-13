# Spec: Sessioner del 1 – sessioner i minnet med sessionscookie (spola.web)

## Uppgift
Bygg HTTP-sessioner i `spola.web` (`Sessions`, `Session`, `SessionOptions`), motsvarigheten till servlet-API:ts `HttpSession` med Spring Boots `server.servlet.session.*`. Fullständigt uppdrag, designbeslut och krav: `gauntlet/runs/20261013-2026-web-sessioner/mission.md` (källa `uppdrag/11a-web-sessioner.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten under "Designbeslut" i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Inga ändringar utanför `framework/` görs av byggaren.
