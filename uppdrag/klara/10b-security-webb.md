# Säkerhet del 2: säkerhetsfilter, HTTP Basic och åtkomstregler (`spola.security.web`)

## Mål
En ny bryggmodul `spola.security.web` ska koppla ihop `spola.security` (10a) med `spola.web`
och ersätta kärnan i Spring Securitys webbdel: `SecurityFilterChain` med
`authorizeHttpRequests` (`requestMatchers(...).hasRole(...)`, `permitAll()`, `authenticated()`,
`denyAll()`, `anyRequest()`), `httpBasic()` (`BasicAuthenticationFilter`), den inloggade
användaren per förfrågan (`SecurityContext`/`@AuthenticationPrincipal`), metodnivåskydd
(motsvarigheten till `@PreAuthorize("hasRole('ADMIN')")` som ett filter på en grupp) och
svaren 401 (`AuthenticationEntryPoint` med `WWW-Authenticate`) och 403
(`AccessDeniedHandler`). Allt är vanlig Java-kod utan annoteringar och utan reflektion. Det
här är **10b**, sista delen av rad 10 i `PLAN.md`. Formulärinloggning och utloggning kräver
sessioner och flyttas därför till uppdrag 11 tillsammans med sessioner, JWT, CSRF och CORS.
Uppdraget rör säkerhetskritisk kod: PROGRESS.md ska säga att en människa bör granska det innan
ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–09 och 10a är klara. Läs `framework/REGLER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "Web del 1" (filtrens ordning
och omfång, att routingen avgörs före filtren, att undantag mappas där de uppstår, strikt
avkodning av sökvägen, `HEAD` via `GET`-routen, automatiskt `OPTIONS`), "Validering del 2"
(beroendets riktning för en integration mellan två moduler) och "Säkerhet del 1" (hela API:t
i `spola.security`). Alla tester skrivs med `spola.test`.

Det som finns och som 10b bygger på:
- `spola.web`: `Filter`/`Filter.Chain`, `RouterBuilder.filter(...)` och
  `RouteGroup.filter(...)`, `WebRequest.withAttribute`/`attribute` med `AttributeKey<T>`
  (identitetsbaserad), `WebRequest.pathSegments()` (avkodade segment; tom lista när routern
  svarar 400 på sökvägen), `WebRequest.method()`, `headers()`, `Problem`, `WebException`.
  Routern är skiftlägeskänslig och strikt med avslutande `/`, och den svarar på `HEAD` med
  `GET`-routen.
- `spola.security`: `Authenticator.authenticate(username, password)` med `AuthResult`
  (`Authenticated(Identity)`, `Failed(Failure)`), `Identity(name, roles)` med `hasRole`, och
  reglerna för roller (1–100 tecken utskrivbar ASCII utan mellanslag). Skyddet mot
  tidsskillnader för okända användare ligger redan i `Authenticator`.
- Båda modulerna loggar på `System.Logger` med loggernamnet lika med modulnamnet.
- `Build.java` har en uttrycklig modullista (`MODULES`) med en testingång per modul.

Erfarenhet från tidigare iterationer: text som kan hamna i en loggrad, ett undantag eller ett
problem-svar ska vara kort och fri från råa styrtecken, och hemligheter (lösenord,
`Authorization`-huvudet, hashar) får aldrig ekas. Gränser ska kontrolleras innan arbete eller
minne hinner växa. Beteendet för `null`, tomma värden, felaktiga data och fel i användarkod
ska vara bestämt och testat. Säkerhetsbeslut ska vara fail-closed: när något är oklart nekas
förfrågan.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **Bryggmodul:** `spola.security.web` i `modules/security-web/`, paketet `spola.security.web`,
  med `requires transitive spola.web` och `requires transitive spola.security` (båda finns i
  det publika API:t) och `requires static java.net.http` bara för testerna. Motivering:
  `spola.security` ska förbli fristående (hashning och autentisering behövs utan HTTP), och
  `spola.web` ska gå att använda utan säkerhet; till skillnad från valideringen i 09b är
  säkerhetsdelen inte liten och oberoende av resten, och Spring delar på samma sätt
  (`spring-security-web`). Modulen läggs till i `MODULES` med testingången
  `spola.security.web.SecurityWebTests`. Inga ändringar i andra modulers `src/` förväntas;
  behövs ändå något ska det vara ett litet tillägg (inget ändrat beteende) som motiveras i
  ARCHITECTURE.md.
- **Säkerhetsfiltret.** `WebSecurity.builder()...build()` ger ett oföränderligt, trådsäkert
  `WebSecurity` med `filter()` (ett vanligt `spola.web.Filter` som registreras på routern,
  först, så att det ligger ytterst; det står i Javadoc och README) och `require(rule)` (se
  nedan):
  ```java
  WebSecurity security = WebSecurity.builder()
          .authentication(HttpBasic.of(authenticator, "app"))
          .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
          .rule(RequestMatcher.method("POST", RequestMatcher.under("/admin")), AccessRule.hasRole("ADMIN"))
          .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
          .build();
  Router router = Router.builder()
          .filter(security.filter())
          .group("/admin", g -> g.filter(security.require(AccessRule.hasRole("ADMIN")))
                  .get("/users", listUsers))
          .build();
  ```
  (Exakt form på `group` följer `RouteGroup` i `spola.web`.) För varje förfrågan: (1) autentisering, (2) identiteten läggs som attribut, (3) den första
  regel vars matcher matchar avgör, (4) tillåts förfrågan går den vidare med `next.proceed`,
  annars blir svaret 401 eller 403.
- **Autentisering som utbytbara mekanismer.** `RequestAuthentication` är ett funktionellt
  gränssnitt `AuthOutcome authenticate(WebRequest request)` med det förseglade `AuthOutcome`:
  `None` (förfrågan har inga uppgifter för den här mekanismen), `Success(Identity identity)`
  och `Rejected` (uppgifter fanns men var fel eller ogiltiga). Builderns `authentication(...)`
  kan anropas flera gånger; mekanismerna provas i ordning och den första som inte ger `None`
  avgör. Ingen mekanism, eller bara `None`, betyder anonym. **`Rejected` ger alltid 401**,
  även på en väg som är `permitAll` (som Spring: felaktiga uppgifter ignoreras aldrig tyst).
  Uppdrag 11 lägger till JWT och sessioner som fler mekanismer.
- **HTTP Basic (RFC 7617)** via `HttpBasic.of(Authenticator authenticator, String realm)`:
  - Inget `Authorization`-fält, eller ett vars schema inte är `Basic`, ger `None` (så att
    andra mekanismer kan läsa t.ex. `Bearer`).
  - Fler än ett `Authorization`-fält ger 400 (problem-svar, `Authorization` är ett
    singelfält enligt RFC 9110 §11.6.2), före all autentisering.
  - Schemat jämförs skiftlägesokänsligt (`basic`, `BASIC`) och följs av ett eller flera
    mellanslag och en token68. Token68 längre än **8 192 tecken** ger `Rejected` utan
    avkodning. Avkodningen är strikt standard-Base64 **med** utfyllnad och kanonisk
    (avkodning och ny kodning ger samma text); annars `Rejected`.
  - Byten avkodas som UTF-8 med `CodingErrorAction.REPORT` (fel ger `Rejected`), delas vid
    det **första** `:` (saknas `:` ger `Rejected`), och resten (som får innehålla `:`) är
    lösenordet. Ett användarnamn eller lösenord med styrtecken (U+0000–U+001F, U+007F) ger
    `Rejected` (RFC 7617 §2). Ingen normalisering.
  - `Authenticator.authenticate` anropas exakt en gång per förfrågan med Basic-uppgifter som
    passerat kontrollerna; `Authenticated` ger `Success`, `Failed` (båda orsakerna) ger
    `Rejected`. Ett undantag från `Authenticator` släpps igenom (routerns mappers eller 500).
  - Realm är 1–100 tecken utskrivbar ASCII utan `"` och `\` (annars
    `IllegalArgumentException` i `of`).
  - De avkodade byten nollställs efter användning (bästa försök; lösenordet som `String` kan
    inte rensas, vilket står i Javadoc).
- **401 och 403.** Nekas en anonym förfrågan, eller ger en mekanism `Rejected`, blir svaret
  **401** som problem-svar (`Problem.of(401)`, ingen `detail`) med
  `WWW-Authenticate: Basic realm="<realm>", charset="UTF-8"` för varje `HttpBasic`-mekanism
  (i registreringsordning; utan sådan mekanism inget `WWW-Authenticate`). Nekas en
  autentiserad förfrågan blir svaret **403** (`Problem.of(403)`, ingen `detail`). Svaren är
  exakt lika oavsett varför (fel lösenord, okänd användare, avstängt konto, trasig Base64),
  och inget svar innehåller användarnamn, lösenord eller huvudets värde. Båda svaren får
  `Cache-Control: no-store`. Handlern och filtren innanför anropas aldrig för en nekad
  förfrågan.
- **Matchers.** `RequestMatcher` är ett funktionellt gränssnitt `boolean matches(WebRequest)`
  med fabrikerna:
  - `under(String prefix)`: segmentvis prefix mot de **avkodade** `pathSegments()`;
    `under("/admin")` matchar `/admin`, `/admin/` och `/admin/x` men inte `/administrator`.
    Prefixet börjar med `/`, har inga tomma segment, inga `{`, `}`, `*`, `?`, `%`, `.`/`..`
    och inga styrtecken (annars `IllegalArgumentException`); `under("/")` matchar alla
    sökvägar. Jämförelsen är skiftlägeskänslig, som routern. En förfrågan med tomma
    `pathSegments()` (sökvägen avvisad av routern) matchar inget `under`.
  - `exactly(String path)`: samma regler, men alla segment ska stämma och antalet vara lika.
  - `method(String method, RequestMatcher inner)`: exakt metod och `inner`. **`method("GET",
    ...)` matchar även `HEAD`**, eftersom routern svarar på `HEAD` med `GET`-routen; annars
    skulle en regel för `GET` gå att kringgå med `HEAD`.
  - `anyRequest()`.
  - Egna matchers är lambdor.
- **Regler.** `AccessRule` är ett funktionellt gränssnitt
  `boolean allows(Optional<Identity> identity, WebRequest request)` med fabrikerna
  `permitAll()`, `denyAll()`, `authenticated()`, `hasRole(String)` och
  `hasAnyRole(String...)` (rollerna kontrolleras med samma regler som i `spola.security` när
  regeln skapas). Den första regeln vars matcher matchar avgör; **matchar ingen regel nekas
  förfrågan** (fail-closed). `build()` samlar alla problem och kastar ett
  `IllegalArgumentException` med en rad per problem: en regel efter `anyRequest()`
  (onåbar). Ett undantag från en egen matcher eller regel släpps igenom (500 via routern), så
  att förfrågan aldrig tillåts av ett fel.
- **Identiteten per förfrågan.** Filtret lägger alltid ett `SecurityContext`-record
  (`Optional<Identity> identity()`) som attribut under en nyckel som bara modulen äger, och
  lämnar vidare den nya `WebRequest`. `WebSecurity.identity(WebRequest)` ger
  `Optional<Identity>` och `WebSecurity.requireIdentity(WebRequest)` ger `Identity` eller
  kastar `WebException` 401. Har filtret inte körts (attributet saknas) kastar båda
  `IllegalStateException` (programmeringsfel, 500), så att en glömd registrering märks i
  stället för att alla blir anonyma. Ingen `ThreadLocal` och ingen `ScopedValue` i 10b.
- **Skydd per grupp eller handler.** `WebSecurity.require(AccessRule rule)` ger ett `Filter`
  för `RouteGroup.filter(...)` (motsvarar `@PreAuthorize` på en controller). Det läser
  identiteten med samma regler (saknat `SecurityContext` ger `IllegalStateException`), och
  nekar med samma 401/403-svar. 401-svaret från ett gruppfilter har samma `WWW-Authenticate`
  som det centrala filtret; därför kommer `require` från den byggda `WebSecurity`
  (`security.require(rule)`), inte från en statisk metod.
- **Loggning.** Inga lyckade eller misslyckade inloggningar loggas i 10b (auditloggning hör
  till uppdrag 18). Inget `Authorization`-värde loggas någonsin.

## Krav
- Modulen `spola.security.web` med `module-info.java` (Javadoc som beskriver modulen, dess
  beroenden och var filtret ska registreras) och typerna `WebSecurity` (med builder),
  `RequestAuthentication`, `AuthOutcome` (`None`, `Success`, `Rejected`), `HttpBasic`,
  `RequestMatcher`, `AccessRule` och `SecurityContext` enligt besluten ovan.
- Publikt API dokumenteras med Javadoc, inklusive Basic-tolkningen och dess gränser,
  regelordningen och fail-closed-standardvalet, `HEAD`-regeln, skillnaden mellan 401 och 403
  och att filtret ska registreras först.
- `README.md` i `modules/security-web/` med ett exempel bredvid motsvarande Spring
  Security-kod (`SecurityFilterChain`-böna med `authorizeHttpRequests`, `httpBasic()` och
  `@PreAuthorize`). Spola-delen är en testkälla som kompileras, och ett test kontrollerar att
  README visar den oförändrad (som i `spola.security`). README har en jämförelsetabell och
  avvikelserader (ingen formulärinloggning ännu, nekat som standard i stället för krav på
  `anyRequest()`, `GET`-regler gäller även `HEAD`, segmentvisa prefix i stället för
  Ant-mönster, ingen `SecurityContextHolder`, strikt Base64).

## Acceptanskriterier
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

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion och inga
namnkonventioner. Base64 och UTF-8 avkodas med JDK:ns primitiver (`java.util.Base64`,
`CharsetDecoder`) och testas mot RFC 7617:s exempel (`Aladdin:open sesame` ger
`QWxhZGRpbjpvcGVuIHNlc2FtZQ==`, och exemplet med `test:123£` i §2.1). Inga nya filtyper i
`framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
5
