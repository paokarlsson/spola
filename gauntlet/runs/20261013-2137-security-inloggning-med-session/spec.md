# Spec: Sessioner del 2 – inloggning och utloggning med session (spola.security.web)

## Uppgift
Koppla sessionerna från 11a till `spola.security.web`: `WebSecurity.Builder.sessions(...)`, `WebSecurity.login` (nytt sessions-id) och `WebSecurity.logout`, så att ett JSON-API kan logga in med en sessionscookie och få 401 utan `WWW-Authenticate`. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261013-2137-security-inloggning-med-session/mission.md` (källa `uppdrag/11b-security-inloggning-med-session.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
