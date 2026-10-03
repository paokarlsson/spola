# Spec: Säkerhet del 2 — säkerhetsfilter, HTTP Basic och åtkomstregler (`spola.security.web`)

## Uppgift
Bygg bryggmodulen `spola.security.web` (`framework/modules/security-web/`) som kopplar ihop
`spola.security` med `spola.web`: ett säkerhetsfilter med utbytbara autentiseringsmekanismer,
HTTP Basic (RFC 7617), segmentvisa matchers, åtkomstregler (första matchande avgör, nekat som
standard), identiteten som attribut per förfrågan, gruppskydd via `security.require(rule)` och
fasta 401/403-svar. Alla designbeslut, krav och detaljer står i `mission.md` i den här
katalogen och är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott mot den är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

## Acceptanskriterier
Tas över oförändrade från `mission.md` (alla designbeslut i avsnittet "Designbeslut" och
"Krav" i `mission.md` gäller också som krav):

1. `java Build.java` i `framework/` avslutas med exitkod 0, och
   `spola.security.web.SecurityWebTests` körs som modulens svit.
2. `modules/security-web/src/module-info.java` har exakt `requires transitive spola.web`,
   `requires transitive spola.security` och `requires static java.net.http`; ett test fäster
   listan. Huvudkoden importerar inget från `java.lang.reflect` eller `java.lang.invoke`.
   `git diff` visar i `Build.java` bara den nya raden i `MODULES` och inga ändringar under
   andra modulers `src/` eller `test/`, utom ett tillägg som motiveras i ARCHITECTURE.md.
3. Tester genom en riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` eller rå socket
   och en `InMemoryUserStore` med `Pbkdf2PasswordHasher.of(10_000, 16, 32)` visar att:
   - rätt Basic-uppgifter ger handlerns svar, och handlern ser exakt rätt `Identity` via
     `WebSecurity.identity` och `requireIdentity`;
   - en anonym förfrågan till en `permitAll`-väg når handlern med tom identitet;
   - en anonym förfrågan till en skyddad väg ger 401 med `application/problem+json`, exakt
     `WWW-Authenticate: Basic realm="app", charset="UTF-8"` och `Cache-Control: no-store`, och
     handlern anropas inte;
   - fel lösenord, okänd användare och avstängt konto (med rätt lösenord) ger 401 med
     byte för byte samma svar, även på en `permitAll`-väg;
   - en inloggad användare utan rätt roll ger 403 utan `WWW-Authenticate`, och med rätt roll
     handlerns svar;
   - en förfrågan som ingen regel matchar nekas (401 anonym, 403 inloggad).
4. Ett tabelldrivet test med minst 20 felaktiga `Authorization`-värden visar att varje ger
   401 (samma svar som fel lösenord) och att `Authenticator` inte anropas: saknad token,
   ogiltiga Base64-tecken, `-_`-alfabetet, saknad och felaktig utfyllnad, icke-kanonisk
   Base64, token över 8 192 tecken, ogiltig UTF-8, saknat `:`, styrtecken i namn och i
   lösenord (bland annat `\0`, `\n`, U+007F), tomt användarnamn med lösenord. Testet visar
   också att RFC 7617:s exempel (`Aladdin:open sesame` som `QWxhZGRpbjpvcGVuIHNlc2FtZQ==` och
   `test:123£` som `dGVzdDoxMjPCow==`) avkodas till rätt namn och lösenord, att `basic` och `BASIC` godtas, att flera mellanslag efter schemat godtas, att ett
   lösenord med `:` och med tecken utanför ASCII (inklusive surrogatpar) fungerar, att ett
   `Bearer`-värde ger `None` (anonym) och att två `Authorization`-fält ger 400.
5. Ett test visar att `Authenticator.authenticate` (med en räknande `UserStore` eller
   hasher) anropas exakt en gång per förfrågan med Basic-uppgifter och aldrig för en anonym
   förfrågan, och att ett `RuntimeException` från lagringen ger 500 och aldrig tillgång.
6. Ett test av matchers visar `under` och `exactly` mot `/admin`, `/admin/`, `/admin/x`,
   `/administrator`, `/Admin` och procentkodade varianter (`/%61dmin` matchar `under("/admin")`
   eftersom matchningen sker på avkodade segment), att ogiltiga prefix ger
   `IllegalArgumentException`, att `method("GET", ...)` matchar `HEAD` men inte `POST`, och
   genom servern att en regel `method("GET", under("/admin"))` med `hasRole("ADMIN")` före
   `anyRequest().permitAll()` ger 401 för både `GET` och `HEAD` mot `/admin`.
7. Ett test visar att den första matchande regeln avgör (en `permitAll` före en
   `denyAll` för samma väg och tvärtom), att en regel efter `anyRequest()` ger
   `IllegalArgumentException` vid `build()` med en rad per problem, att ogiltiga roller ger
   `IllegalArgumentException`, och att ett undantag från en egen matcher eller regel ger 500
   utan att handlern anropas.
8. Ett test visar att `security.require(AccessRule.hasRole("ADMIN"))` på en grupp ger 401
   (med samma `WWW-Authenticate`) för anonyma, 403 för inloggade utan rollen och handlerns
   svar med rollen, och att `require`, `identity` och `requireIdentity` utan det centrala
   filtret ger 500 (`IllegalStateException`), inte anonym tillgång.
9. Ett test visar att filtret ytterst ser routerns 404, 405 och automatiska `OPTIONS` och
   nekar dem enligt reglerna (en anonym förfrågan till en okänd väg under en skyddad regel
   ger 401, inte 404), och att en sökväg som routern avvisar med 400 (t.ex. `/%2e%2e/admin`)
   aldrig når en handler.
10. Ett test med 200 virtuella trådar som samtidigt skickar förfrågningar (blandat rätt och
    fel uppgifter, flera användare och roller) visar att varje förfrågan får exakt sitt
    förväntade svar och att handlern ser rätt identitet.
11. Ett test visar med markörsträngar att varken lösenord, användarnamn eller
    `Authorization`-värdet finns i något svar (status, huvuden, kropp) från kriterium 3–9 eller
    i någon loggrad som modulen eller routern skriver under testerna, och att inga styrtecken
    från förfrågan når en loggrad.
12. `README.md` i `modules/security-web/` har exemplet bredvid Spring Security (som kompilerad
    testkälla som ett test jämför med README), jämförelsetabellen och avvikelseraderna enligt
    kraven.
13. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
14. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md beskriver det levererade,
    anger att rad 10 i `PLAN.md` är klar och att formulärinloggning och utloggning flyttats
    till uppdrag 11, listar kända begränsningar (en PBKDF2-beräkning per Basic-förfrågan och
    därmed CPU-kostnad, ingen hastighetsbegränsning, inga säkerhetshuvuden som
    `X-Content-Type-Options`/HSTS, ingen auditloggning) och säger att modulen är
    säkerhetskritisk och bör granskas av en människa. ARCHITECTURE.md får ett avsnitt
    "Säkerhet del 2" som beskriver:
    - varför en bryggmodul och inte ett beroende mellan `spola.web` och `spola.security`;
    - mekanismerna och `AuthOutcome`, och varför `Rejected` ger 401 även på öppna vägar;
    - Basic-tolkningen och dess gränser;
    - matchning på avkodade segment, `HEAD`-regeln och varför ingen Ant-syntax;
    - första matchande regel och nekat som standard;
    - identiteten som attribut i stället för `ThreadLocal`, och varför ett saknat filter ger
      500;
    - 401 kontra 403 och varför svaren inte skiljer på orsaker.


## Utanför scope
- Formulärinloggning, utloggning, sessioner, "kom ihåg mig", JWT, CSRF och CORS (uppdrag 11).
- Säkerhetshuvuden (`X-Content-Type-Options`, `X-Frame-Options`, HSTS), hastighetsbegränsning,
  låsning av konton och cache av lyckade Basic-inloggningar.
- Auditloggning och händelser för inloggningar (uppdrag 17–18).
- `SecurityContextHolder`/`ScopedValue` för identiteten utanför webblagret, och
  uttrycksspråk som i `@PreAuthorize`.
- Integration i `spola.demo` och exempelappen (uppdrag 20).


## Antaganden
- Inga ändringar i andra modulers `src/`/`test/` behövs; om ett litet tillägg ändå krävs ska
  det inte ändra beteende och motiveras i ARCHITECTURE.md (kriterium 2).
- Modulens svit körs av `Build.java` via en ny rad i `MODULES`.
- Max 5 rundor (från `mission.md`).
