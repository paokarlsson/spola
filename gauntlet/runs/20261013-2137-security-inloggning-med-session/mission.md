Source: uppdrag/11b-security-inloggning-med-session.md
# Sessioner del 2: inloggning och utloggning med session (`spola.security.web`)

## Mål
`spola.security.web` ska kunna bära inloggningen i sessionen från 11a. Det motsvarar Spring
Securitys `HttpSessionSecurityContextRepository` med `SessionCreationPolicy.IF_REQUIRED`, ett
nytt sessions-id vid inloggning (`request.changeSessionId()`, skydd mot session fixation) och
`session.invalidate()` vid utloggning. En JSON-inloggning (`POST /api/auth/login`) blir en
vanlig handler som anropar `Authenticator` och `WebSecurity.login`. Svaret 401 för en anonym
förfrågan har inget `WWW-Authenticate`, så att webbläsaren inte visar Basic-rutan. Det här är
**11b**, andra delen av behovet "Inloggning med sessioner och CSRF-skydd för ett JSON-API" från
appen psykologen. **11c** (CSRF-skydd med `XSRF-TOKEN`) följer. Koden är säkerhetskritisk:
PROGRESS.md ska säga att en människa bör granska den innan ramverket används på riktigt.

## Bakgrund / kontext
Uppdrag 01–10 och 11a är klara (se `uppdrag/klara/` och `framework/PROGRESS.md`).
Behov: `uppdrag/behov/klara/2026-10-13-inloggning-sessioner-csrf.md`.

Läs `framework/REGLER.md`, `framework/MONSTER.md`, `framework/PROGRESS.md` och
`framework/ARCHITECTURE.md` innan du börjar, särskilt avsnitten "Säkerhet del 1"
(`Authenticator`, `AuthResult`, `Identity`), "Säkerhet del 2" (filtret, mekanismerna och
`AuthOutcome`, att 401 bara har `WWW-Authenticate` för `HttpBasic`-mekanismer, identiteten som
attribut, varför ett saknat filter ger 500) och "Sessioner" (11a). Alla tester skrivs med
`spola.test`.

Det som finns och som 11b bygger på:
- `spola.web` (11a): `Sessions.inMemory(SessionOptions[, InstantSource])`, `sessions.filter()`,
  `sessions.size()`, de statiska `Sessions.find`, `getOrCreate`, `renew` och `invalidate`, och
  `Session.get`/`put`/`remove` med `AttributeKey<T>`. Filtret skapar aldrig en session själv och
  ger 500 om det når samma förfrågan två gånger. `renew` ger ett nytt id atomärt (det gamla
  slutar gälla direkt) eller en ny session. Gränsen ger 503. Har 11a levererat andra namn än
  dessa gäller de levererade (se PROGRESS.md och ARCHITECTURE.md).
- `spola.security.web` (10b): `WebSecurity.builder()` med `authentication(...)` och
  `rule(...)`, `filter()`, `require(rule)`, de statiska `identity(request)` och
  `requireIdentity(request)`, `RequestAuthentication`/`AuthOutcome`, `HttpBasic`,
  `RequestMatcher` och `AccessRule`. 401-svaret har ett `WWW-Authenticate` per `HttpBasic` och
  inget utan `HttpBasic`. Både 401 och 403 har `Cache-Control: no-store`.
- `spola.security` (10a): `Authenticator.authenticate(username, password)` med `AuthResult`
  (`Authenticated(Identity identity)`, `Failed(Failure reason)`), `InMemoryUserStore` och
  `Pbkdf2PasswordHasher`.

Behovets användningsfall (raden `.csrf(Csrf.cookie())` kommer i 11c):

```java
var sessions = Sessions.inMemory(SessionOptions.DEFAULT
        .withIdleTimeout(Duration.ofMinutes(30))
        .withCookie(cookie -> cookie.secure(config.booleanValue("cookie.secure"))));   // HttpOnly, SameSite=Lax

var security = WebSecurity.builder()
        .sessions(sessions)                                   // identiteten läses ur sessionen
        .unauthenticated(Unauthenticated.status401())         // 401 utan WWW-Authenticate
        .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
        .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
        .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
        .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
        .build();

var router = Router.builder()
        .filter(security.filter())
        .post("/api/auth/login", request -> {
            var body = request.readJson(LoginCodec.INSTANCE);
            return switch (authenticator.authenticate(body.username(), body.password())) {
                case AuthResult.Authenticated(Identity identity) -> {
                    WebSecurity.login(request, identity);     // nytt sessions-id, ny CSRF-token
                    yield Responses.json(200, Map.of("success", true, "username", identity.name()));
                }
                case AuthResult.Failed failed -> Responses.json(401, Map.of("success", false));
            };
        })
        .post("/api/auth/logout", request -> {
            WebSecurity.logout(request);                      // sessionen tas bort, cookien går ut
            return Responses.json(200, Map.of("success", true));
        })
        .get("/api/auth/me", request ->
            Responses.json(200, Map.of("username", WebSecurity.requireIdentity(request).name())))
        .build();
```

**Avvikelser från behovets kod (beslutade här, motiveras i README och ARCHITECTURE.md):**
- **Ingen `.unauthenticated(Unauthenticated.status401())`.** Filtrets 401 har redan bara
  `WWW-Authenticate` för `HttpBasic`-mekanismer (10b). Utan `HttpBasic` blir svaret alltså 401
  utan `WWW-Authenticate`, vilket är exakt vad Springs `HttpStatusEntryPoint(UNAUTHORIZED)` ger.
  En inställning som bara upprepar standardbeteendet tillför inget. Testet visar att svaret
  saknar `WWW-Authenticate`.
- **`Responses.json(200, Map.of(...))` finns inte.** Spola mappar JSON explicit och utan
  reflektion (MONSTER.md), så handlern skriver till exempel
  `Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE, "username", JsonString.of(identity.name())))`.
  `LoginCodec` är en `JsonCodec` för ett record med `username` och `password` som testet
  skriver med `spola.json.codec`.

Erfarenhet från tidigare iterationer: hemligheter (lösenord, sessions-id) får aldrig ekas i
svar, loggrader eller undantag. Säkerhetsbeslut ska vara fail-closed, och ett glömt eller
felplacerat filter ska märkas (500) i stället för att ge anonym tillgång eller tyst
ignoreras.

### Designbeslut (fattade av planeraren, dokumenteras i ARCHITECTURE.md)
- **`WebSecurity.Builder.sessions(Sessions sessions)`.** Med sessioner kör säkerhetsfiltret
  sessionsfiltret från 11a självt, ytterst, så att applikationen bara registrerar
  `security.filter()`. Registrerar applikationen också `sessions.filter()` når sessionsfiltret
  samma förfrågan två gånger och ger 500 (11a). Det står i Javadoc och README. Anropas
  `sessions(...)` två gånger blir det en rad i `build()`s `IllegalArgumentException`.
- **Identiteten ur sessionen kommer först.** Innan de registrerade mekanismerna provas läser
  filtret förfrågans session. Finns en identitet där är förfrågan autentiserad med den, och
  mekanismerna (också `HttpBasic`) provas inte. Utan session, utan identitet i sessionen eller
  med en okänd eller utgången sessionscookie provas mekanismerna som i 10b. En sessionscookie
  ger aldrig `Rejected`: en gammal cookie gör förfrågan anonym, så att inloggningen på en
  `permitAll`-väg fungerar.
- **`WebSecurity.login(WebRequest request, Identity identity)`** (statisk) gör `Sessions.renew(
  request)` och sparar identiteten i sessionen under en `AttributeKey` som bara modulen har.
  Förfrågan måste ha passerat ett `WebSecurity`-filter med `sessions(...)`, annars kastas
  `IllegalStateException` (500). Förfrågans eget `SecurityContext` ändras inte:
  `identity(request)` svarar som när filtret körde, och nästa förfrågan ser identiteten. Det
  står i Javadoc. Vid sessionsgränsen ger `login` 503 från 11a.
- **`WebSecurity.logout(WebRequest request)`** (statisk) gör `Sessions.invalidate(request)`:
  hela sessionen tas bort, som `session.invalidate()`, och svaret tar bort cookien. Utan session
  gör den ingenting. Samma `IllegalStateException` som `login` utan filter med sessioner.
- **Ögonblicksbild.** Sessionen bär den `Identity` som `login` fick. Ändrade roller, ett avstängt
  konto eller en borttagen användare i `UserStore` påverkar inte en pågående session förrän
  utloggning eller utgång, som i Springs standardinställning. Det står i Javadoc och PROGRESS.
- **Sessioner skapas bara vid inloggning.** Säkerhetsfiltret skapar ingen session, så anonyma
  förfrågningar, 401, 403 och `/actuator/health` ger inget `Set-Cookie`.
- **Ingen loggning**, som i 10b (auditloggning är ett eget behov när det kommer).

## Krav
- `WebSecurity.Builder.sessions(Sessions)`, `WebSecurity.login(WebRequest, Identity)` och
  `WebSecurity.logout(WebRequest)` enligt besluten ovan, med Javadoc som beskriver ordningen
  (sessionen före mekanismerna), att bara `security.filter()` ska registreras, ögonblicksbilden,
  att förfrågans eget `SecurityContext` inte ändras av `login`, och felen utan sessioner.
- Klass-Javadoc för `WebSecurity` och Javadoc i `module-info.java` för `spola.security.web`
  nämner sessionerna. `requires` och `exports` är oförändrade (`Sessions` finns i `spola.web`,
  som redan krävs transitivt).
- `README.md` i `modules/security-web/` får ett avsnitt "Inloggning med session". Det visar
  behovets Spring-kod (förkortad `SecurityConfig` med `sessionManagement`,
  `HttpStatusEntryPoint`, `formLogin().disable()`, `httpBasic().disable()`, och en
  `AuthController` med `changeSessionId()` och `invalidate()`) bredvid Spola-koden. Spola-delen
  är en testkälla som kompileras, och ett test kontrollerar att README visar den oförändrad.
  Avsnittet har jämförelserader (`HttpSessionSecurityContextRepository`, `changeSessionId`,
  `invalidate`, `HttpStatusEntryPoint`) och avvikelserader: ingen `formLogin()` eftersom
  inloggningen är en handler, ingen `unauthenticated(...)`, identiteten är en ögonblicksbild,
  sessionen kommer före Basic, och `login` ändrar inte förfrågans egen identitet.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i
   `spola.security.web.SecurityWebTests`.
2. `requires` och `exports` i `modules/security-web/src/module-info.java` är oförändrade, och
   testet som fäster dem är oförändrat. Huvudkoden importerar inget från `java.lang.reflect`
   eller `java.lang.invoke`. `git diff` visar inga ändringar i `Build.java` och inga under
   andra modulers `src/` eller `test/`, utom ett litet tillägg i `spola.web` som motiveras i
   ARCHITECTURE.md. Befintliga testfiler i `spola.security.web` är oförändrade utom
   registreringen i `SecurityWebTests` och README-kontrollen, om den behöver känna till den nya
   testkällan.
3. Behovets användningsfall, med avvikelserna ovan, är en testkälla som kompileras. Genom en
   riktig `HttpServer` (127.0.0.1, port 0) med `HttpClient` och `CookieManager`, en
   `InMemoryUserStore` med `Pbkdf2PasswordHasher.of(10_000, 16, 32)` och `cookie.secure` satt
   till `false` (vanlig HTTP i testet) visar ett test att:
   - `GET /actuator/health` ger 200 utan `Set-Cookie` och att `sessions.size()` är 0;
   - `GET /api/auth/me` anonymt ger 401 `application/problem+json` med
     `Cache-Control: no-store` och **utan** `WWW-Authenticate`, och att handlern inte anropas;
   - inloggning med fel lösenord och med okänd användare ger handlerns 401
     `{"success":false}` utan `Set-Cookie`, och `size()` är fortfarande 0;
   - inloggning med rätt uppgifter ger 200 `{"success":true,"username":"anna"}` och exakt ett
     `Set-Cookie` för `SESSION`, och att `GET /api/auth/me` sedan ger `{"username":"anna"}`;
   - `POST /api/auth/logout` ger 200 `{"success":true}` med `Set-Cookie` som tar bort cookien,
     att `/api/auth/me` sedan ger 401, och att det gamla sessions-id:t skickat igen över rå
     socket ger 401.
4. Ett test av session fixation visar att en session som fanns före inloggningen (skapad av en
   `permitAll`-väg som sparar ett värde med `getOrCreate`) får ett nytt id vid inloggningen,
   att det gamla id:t skickat igen ger anonym förfrågan (401 på `/api/auth/me`, och den öppna
   vägen ser ingen session), att det nya id:t ger `anna`, och att värdet som sparades före
   inloggningen finns kvar. Testet visar också att ett välformat id som klienten själv hittat på
   inte blir sessionen: inloggningen ger ett annat id, och det påhittade fortsätter att vara
   okänt.
5. Ett test visar att en identitet ur sessionen följer reglerna: `hasRole("ADMIN")` på
   `/api/admin` ger 403 för en inloggad användare utan rollen och handlerns svar med rollen.
   Med en `HttpBasic`-mekanism registrerad bredvid sessionerna används sessionens identitet
   först, en förfrågan utan session med Basic-uppgifter fungerar som i 10b, och 401 har då
   `WWW-Authenticate` (bara när `HttpBasic` är registrerad).
6. Ett test med en styrd `InstantSource` visar att `/api/auth/me` fungerar 30 minuter minus en
   sekund efter senaste förfrågan och ger 401 vid 30 minuter, och att en inloggning med en
   utgången sessionscookie (på `permitAll`-vägen) fungerar och ger en ny session, inte 401.
7. Ett test visar att `login` och `logout` med en `WebSecurity` utan `sessions(...)`, och utan
   säkerhetsfiltret, ger 500 (`IllegalStateException`), att `sessions.filter()` registrerat
   bredvid `security.filter()` ger 500, och att `sessions(...)` två gånger ger
   `IllegalArgumentException` från `build()` med en rad för det. `login` vid sessionsgränsen
   ger 503 och ingen inloggning.
8. Ett test med 200 virtuella trådar, var och en med en egen `HttpClient` och `CookieManager`,
   loggar in som en av fem användare, anropar `/api/auth/me`, loggar ut och anropar
   `/api/auth/me` igen. Varje tråd ser först sitt eget namn och sedan 401, och `size()` är 0
   efteråt.
9. Ett test visar med markörer att inget lösenord och inget sessions-id finns i något svar
   (utom sessions-id:t i det egna `Set-Cookie`), i någon loggrad som routern skriver under
   testerna eller i något undantagsmeddelande.
10. `README.md` i `modules/security-web/` har avsnittet "Inloggning med session" enligt kraven,
    med Spola-exemplet som kompilerad testkälla som ett test jämför med README.
11. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i `framework/REGLER.md`
    är bruten.
12. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. PROGRESS.md beskriver det levererade,
    säger att 11c återstår för behovet, säger att koden är säkerhetskritisk och bör granskas av
    en människa, och listar kända begränsningar: identiteten i sessionen är en ögonblicksbild,
    `login` ändrar inte förfrågans egen identitet, och inloggningen loggas inte. Meningen om
    "Ingen formulärinloggning, utloggning, sessioner ..." under kända problem för
    `spola.security.web` rättas. ARCHITECTURE.md får ett avsnitt "Inloggning med session
    (`spola.security.web`)" som beskriver:
    - varför säkerhetsfiltret kör sessionsfiltret självt;
    - varför sessionen kommer före mekanismerna och aldrig ger `Rejected`;
    - `login` med `renew` som skydd mot session fixation;
    - `logout` med `invalidate`;
    - ögonblicksbilden;
    - varför det inte finns någon `unauthenticated(...)`.

    Raden för `spola.security.web` i modulkatalogen i `framework/MONSTER.md` (motsvarar också
    sessionsinloggning, `WebSecurity.login`/`logout`) och i modultabellen i `README.md` är
    uppdaterade.

## Utanför scope
- CSRF och `XSRF-TOKEN`, också att `login` och `logout` byter CSRF-token (11c).
- `formLogin()` med HTML-formulär och omdirigeringar, "kom ihåg mig", gräns för samtidiga
  sessioner per användare, att läsa om användaren ur `UserStore` per förfrågan.
- JWT, CORS, säkerhetshuvuden, hastighetsbegränsning och låsning av konton.
- Auditloggning av inloggningar.
- `SecurityContextHolder`/`ScopedValue` för identiteten utanför webblagret.

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Inga annoteringar utöver tillåtelselistan, ingen reflektion, ingen
`ThreadLocal` och inga namnkonventioner. Inga nya filtyper i `framework/`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
