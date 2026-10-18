# spola.security.web

Säkerhet i Spola, del 2: bryggmodulen mellan `spola.security` och `spola.web`. Modulen ersätter
kärnan i Spring Securitys webbdel: `SecurityFilterChain` med `authorizeHttpRequests`
(`requestMatchers(...).hasRole(...)`, `permitAll()`, `authenticated()`, `denyAll()`,
`anyRequest()`), `httpBasic()`, den inloggade användaren per förfrågan (`SecurityContext`,
`@AuthenticationPrincipal`), `@PreAuthorize("hasRole('ADMIN')")` som ett filter på en grupp och
svaren 401 (`AuthenticationEntryPoint`) och 403 (`AccessDeniedHandler`), och inloggning som
bärs av en sessionscookie (`HttpSessionSecurityContextRepository`, `changeSessionId()`,
`invalidate()`; se [Inloggning med session](#inloggning-med-session)), och CSRF-skydd för en SPA
(`csrf()` med `CookieCsrfTokenRepository.withHttpOnlyFalse()`; se
[CSRF-skydd för en SPA](#csrf-skydd-för-en-spa)). Allt är vanlig Java-kod utan annoteringar,
utan reflektion och utan `ThreadLocal`.

> **Säkerhetskritisk kod.** Låt en människa granska modulen innan den används på riktigt.

Modulen kräver `spola.web` och `spola.security` (båda transitivt). Ingen av dem beror på den
här modulen eller på varandra: hashning och autentisering fungerar utan HTTP, och webblagret
fungerar utan säkerhet.

- `WebSecurity.builder()` med `authentication(...)` (en eller flera mekanismer) och
  `rule(matcher, accessRule)`, sedan `build()`. `security.filter()` registreras på routern,
  **först**, så att filtret ligger ytterst och ser varje förfrågan (även routerns 400, 404, 405
  och automatiska `OPTIONS`). Bakom en omvänd proxy kommer bara `ForwardedHeaders` före det, så
  att filtret ser klientens schema och adress (se [Bakom Caddy](#bakom-caddy)).
- `HttpBasic.of(authenticator, realm)`: HTTP Basic enligt RFC 7617 med strikt tolkning.
- `RequestMatcher.under(prefix)`, `exactly(path)`, `method(method, inner)`, `anyRequest()` eller
  en lambda.
- `AccessRule.permitAll()`, `denyAll()`, `authenticated()`, `hasRole(role)`,
  `hasAnyRole(roles...)` eller en lambda.
- `WebSecurity.identity(request)` (`Optional<Identity>`) och
  `WebSecurity.requireIdentity(request)` (`Identity` eller 401) i en handler.
- `security.require(rule)`: ett filter för en grupp (`RouteGroup.filter(...)`).
- `WebSecurity.builder().sessions(sessions)` med `Sessions` från `spola.web`: identiteten läses ur
  förfrågans session, före mekanismerna. Säkerhetsfiltret kör sessionsfiltret självt, så bara
  `security.filter()` registreras.
- `WebSecurity.login(request, identity)` (nytt sessions-id, identiteten i sessionen) och
  `WebSecurity.logout(request)` (sessionen tas bort) i en handler. En användare har högst
  `maximumSessions` sessioner (standard 10), och en inloggning som en annan användare får en ny,
  tom session.
- `WebSecurity.builder().csrf(Csrf.cookie())`: CSRF-skydd med cookien `XSRF-TOKEN` och huvudet
  `X-XSRF-TOKEN` (Angulars standard). Ändrande anrop utan matchande huvud får 403, och
  `login`/`logout` ger en ny token.
- Sessions- och CSRF-cookien får `Secure` när förfrågans schema är `https`, som i Spring: över
  TLS, och bakom en TLS-proxy när `ForwardedHeaders` registreras före `security.filter()`.
- Svar till en inloggad användare får `Cache-Control: no-store` om handlern inte satt något
  (eller satt `public` eller `s-maxage`), och ett svar som sätter sessions- eller CSRF-cookien kan
  aldrig sparas av en delad cache (se [Cachar](#cachar)).

Den första regeln vars matcher matchar avgör, och **en förfrågan som ingen regel matchar nekas**.
Nekas en anonym förfrågan, eller är uppgifterna fel, blir svaret 401 med
`WWW-Authenticate: Basic realm="app", charset="UTF-8"`; nekas en inloggad förfrågan blir svaret
403. Båda är problem-svar (`application/problem+json`) utan `detail` och med
`Cache-Control: no-store`, och de är exakt lika oavsett orsak.

## Ett komplett exempel

Två användare, en öppen väg, en grupp som bara administratörer når och en handler som läser den
inloggade användaren.

### Spring Security

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/public/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.realmName("app"));
        return http.build();
    }

    @Bean
    UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("ada").password(encoder.encode("correct horse battery staple"))
                        .roles("USER", "ADMIN").build(),
                User.withUsername("bob").password(encoder.encode("hunter2hunter2")).roles("USER").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}

@RestController
class HelloController {

    @GetMapping("/public/hello")
    String hello() {
        return "hello";
    }

    @GetMapping("/me")
    String me(@AuthenticationPrincipal UserDetails user) {
        return "you are " + user.getUsername();
    }
}

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminController {

    @GetMapping("/users")
    String users() {
        return "ada, bob";
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    String create() {
        return "created";
    }
}
```

### Spola

```java
import java.util.Set;
import spola.http.Response;
import spola.security.Authenticator;
import spola.security.InMemoryUserStore;
import spola.security.PasswordHasher;
import spola.security.User;
import spola.web.Router;

/** The README example: HTTP Basic, access rules, a protected group and the identity in a handler. */
public final class SecurityExample {

    private SecurityExample() {
    }

    /**
     * Two users in memory.
     *
     * @param hasher {@code Pbkdf2PasswordHasher.defaults()} in production
     * @return the authenticator
     */
    public static Authenticator authenticator(PasswordHasher hasher) {
        var users = new InMemoryUserStore();
        users.add(new User("ada", hasher.hash("correct horse battery staple"), Set.of("USER", "ADMIN"), true));
        users.add(new User("bob", hasher.hash("hunter2hunter2"), Set.of("USER"), true));
        return Authenticator.of(users, hasher);
    }

    /**
     * The rules: {@code /public} for everyone, {@code POST} below {@code /admin} for ADMIN,
     * everything else for any logged-in user.
     *
     * @param authenticator checks usernames and passwords
     * @return the security
     */
    public static WebSecurity security(Authenticator authenticator) {
        return WebSecurity.builder()
                .authentication(HttpBasic.of(authenticator, "app"))
                .rule(RequestMatcher.under("/public"), AccessRule.permitAll())
                .rule(RequestMatcher.method("POST", RequestMatcher.under("/admin")), AccessRule.hasRole("ADMIN"))
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes, with the security filter first and the admin group protected as a whole.
     *
     * @param security the security
     * @return the router
     */
    public static Router router(WebSecurity security) {
        return Router.builder()
                .filter(security.filter())
                .get("/public/hello", request -> Response.text(200, "hello"))
                .get("/me", request -> Response.text(200, "you are " + WebSecurity.requireIdentity(request).name()))
                .group("/admin", admin -> admin
                        .filter(security.require(AccessRule.hasRole("ADMIN")))
                        .get("/users", request -> Response.text(200, "ada, bob"))
                        .post("/users", request -> Response.text(201, "created")))
                .build();
    }
}
```

I produktion skickas `Pbkdf2PasswordHasher.defaults()` in som `hasher`, och routern startas med
`HttpServer.start(options, router)`. Filtret från `security.filter()` är det första som
registreras; `security.require(...)` på gruppen motsvarar `@PreAuthorize` på controllern och
svarar med samma 401 och 403 som det centrala filtret. `requireIdentity` motsvarar
`@AuthenticationPrincipal`: för en anonym förfrågan ger den samma 401 som filtret.

## Spring Security och Spola

| Spring Security | Spola |
|---|---|
| `SecurityFilterChain`-böna med `HttpSecurity` | `WebSecurity.builder()...build()` och `Router.builder().filter(security.filter())`, först |
| `authorizeHttpRequests(auth -> auth...)` | `rule(matcher, accessRule)` i ordning; första matchande regel avgör |
| `httpBasic()` / `BasicAuthenticationFilter` | `authentication(HttpBasic.of(authenticator, realm))` |
| `requestMatchers("/admin/**")` | `RequestMatcher.under("/admin")` |
| `requestMatchers("/health")` | `RequestMatcher.exactly("/health")` |
| `requestMatchers(HttpMethod.POST, "/admin/**")` | `RequestMatcher.method("POST", RequestMatcher.under("/admin"))` |
| `permitAll()`, `denyAll()`, `authenticated()` | `AccessRule.permitAll()`, `denyAll()`, `authenticated()` |
| `hasRole("ADMIN")`, `hasAnyRole(...)` | `AccessRule.hasRole("ADMIN")`, `hasAnyRole(...)` (utan `ROLE_`-prefix) |
| `anyRequest()` | `RequestMatcher.anyRequest()`; regler efter den ger fel vid `build()` |
| `access(AuthorizationManager)` | en lambda `(identity, request) -> ...` som `AccessRule` |
| `@PreAuthorize("hasRole('ADMIN')")` på en controller | `group(..., g -> g.filter(security.require(AccessRule.hasRole("ADMIN")))...)` |
| `@AuthenticationPrincipal` / `SecurityContextHolder.getContext()` | `WebSecurity.identity(request)` och `WebSecurity.requireIdentity(request)` |
| `SecurityContext` | `SecurityContext(Optional<Identity>)` som attribut på förfrågan |
| `AuthenticationProvider` / `AuthenticationFilter` | `RequestAuthentication` med `AuthOutcome` (`None`, `Success`, `Rejected`) |
| `AuthenticationEntryPoint` (401 med `WWW-Authenticate`) | fast 401-problem-svar med `WWW-Authenticate` per `HttpBasic` |
| `AccessDeniedHandler` (403) | fast 403-problem-svar |

## Avvikelser från Spring Security

| Avvikelse | Varför |
|---|---|
| Ingen formulärinloggning (`formLogin()`) | Inloggningen är en vanlig handler som kontrollerar lösenordet med `Authenticator` och anropar `WebSecurity.login`, och utloggningen anropar `WebSecurity.logout` (se [Inloggning med session](#inloggning-med-session)). HTML-formulär med omdirigeringar finns inte. CSRF-skydd för en SPA finns (se [CSRF-skydd för en SPA](#csrf-skydd-för-en-spa)); JWT och CORS byggs när ett behov kommer. |
| Nekat som standard i stället för krav på `anyRequest()` | En förfrågan som ingen regel matchar nekas (401 eller 403). En glömd regel stänger alltså en väg i stället för att öppna den. |
| `GET`-regler gäller även `HEAD` | Routern svarar på `HEAD` med `GET`-routen; annars skulle en regel för `GET` gå att kringgå med `HEAD`. |
| Segmentvisa prefix i stället för Ant-mönster (`/**`, `*`, `?`) | `under("/admin")` jämför hela, avkodade segment precis som routern: `/admin/x` och `/%61dmin` matchar, `/administrator` gör det inte. Det som en regel skyddar är exakt det som routern serverar under samma prefix. |
| Ingen `SecurityContextHolder` | Identiteten är ett attribut på förfrågan, inte ett `ThreadLocal`; den läses med `WebSecurity.identity(request)`. Saknas filtret ger läsningen 500 i stället för en anonym användare. |
| Strikt Base64 i Basic | Bara standard-Base64 med utfyllnad och kanonisk form (inget `-_`, ingen saknad utfyllnad, inga satta oanvända bitar), strikt UTF-8 och inga styrtecken. Allt annat ger 401. |
| Fel uppgifter ger 401 även på `permitAll`-vägar | Som i Spring ignoreras felaktiga uppgifter aldrig tyst; här är det uttryckligen så för alla mekanismer (`Rejected`). |
| Två `Authorization`-fält ger 400 | Fältet är ett singelfält (RFC 9110 §11.6.2); två fält är tvetydiga. |
| Samma svar oavsett orsak, utan `detail` | 401 och 403 skiljer inte på fel lösenord, okänd användare, avstängt konto eller trasig kodning, så att svaret inte hjälper någon att gissa konton. |
| Ingen cache av lyckade Basic-inloggningar och ingen hastighetsbegränsning | Varje förfrågan med Basic-uppgifter kostar en PBKDF2-beräkning. Använd Basic för tjänst-till-tjänst eller bakom en hastighetsbegränsning. |
| Ingen loggning av inloggningar | Auditloggning hör till uppdrag 18; modulen skriver inga loggrader alls. |
| `Cache-Control` på inloggade svar | Spring Security skriver `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache` och `Expires: 0` på varje svar som saknar `Cache-Control`. Här får bara svar till en inloggad förfrågan (session eller mekanism) `Cache-Control: no-store`, och ett anonymt svar utan cookie lämnas som handlern gjorde det. Ett värde med `public` eller `s-maxage` ersätts också på inloggade svar, vilket Spring inte gör. Inget `Pragma` och inget `Expires`. |

## Inloggning med session

Ett JSON-API där användaren loggar in med `POST /api/auth/login` och en sessionscookie sedan bär
inloggningen: motsvarigheten till Spring Securitys `HttpSessionSecurityContextRepository` med
`SessionCreationPolicy.IF_REQUIRED`, `request.changeSessionId()` vid inloggning och
`session.invalidate()` vid utloggning. Sessionerna kommer från `spola.web` (`Sessions`, se
avsnittet "Sessioner" i `modules/web/README.md`). Ett anonymt anrop till en skyddad väg får 401
utan `WWW-Authenticate`, så att webbläsaren inte visar Basic-rutan.

- `WebSecurity.builder().sessions(sessions)`: säkerhetsfiltret kör sessionsfiltret självt,
  ytterst, och läser identiteten ur förfrågans session **före** mekanismerna. Registrera bara
  `security.filter()`; registreras `sessions.filter()` också ger förfrågan 500.
- `WebSecurity.login(request, identity)` i inloggningens handler: sessionen får ett nytt id
  (`Sessions.renewFor`, skydd mot session fixation) och identiteten sparas i den. Utan session
  skapas en. Svaret sätter cookien. Vid sessionsgränsen ger anropet 503.
- **Högst `maximumSessions` sessioner per användare** (standard 10), motsvarigheten till
  `sessionManagement(s -> s.maximumSessions(10))`. Skulle en inloggning ge användaren fler
  avslutas användarens minst nyligen använda session, aldrig den nya inloggningen; nästa förfrågan
  med den avslutade sessionens cookie är anonym (401 på en skyddad väg). Användare jämförs på
  `Identity.name()`. Ett konto som loggar in om och om igen utan cookie (varje sådan inloggning är
  en ny session) kan alltså bara ha 10 av `maxSessions` och kan inte stänga ute andra användare.
  En inloggning med användarens egen session förnyar den och räknas inte som en till. Samtidiga
  inloggningar av samma användare avslutar aldrig varandras sessioner medan de hanteras; när
  svaren är byggda har användaren högst gränsen.
- **En annan användare får en ny session.** Har förfrågans session redan en annan användares
  identitet (en delad dator där ingen loggade ut) avslutas den sessionen, och inloggningen får en
  ny, tom session: den nya användaren ser inget av det den förra sparade, och den förras id slutar
  gälla. Samma användare, eller en session utan identitet, behåller sina attribut.
- **Inloggade svar sparas inte i en delad cache.** Svaret till en förfrågan med en inloggad
  identitet får `Cache-Control: no-store` om handlern inte satt något; se [Cachar](#cachar).
- `WebSecurity.logout(request)`: sessionen tas bort med alla attribut och svaret tar bort
  cookien. Utan session händer ingenting.
- Säkerhetsfiltret skapar aldrig en session: anonyma anrop, 401, 403 och `/actuator/health` får
  ingen sessionscookie (och utan `csrf(...)` inget `Set-Cookie` alls).
- Utan `sessions(...)`, eller utan säkerhetsfiltret, ger `login` och `logout` 500
  (`IllegalStateException`).

Exemplet här har inget CSRF-skydd, så att inloggningen syns för sig. Med `csrf(Csrf.cookie())`
skyddas också ändrande anrop mot CSRF (se [CSRF-skydd för en SPA](#csrf-skydd-för-en-spa)); utan
det skyddar bara `SameSite=Lax` på sessionscookien mot förfalskade `POST` från andra webbplatser,
men inte från andra underdomäner på samma webbplats.

### Spring Security

```java
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(r -> r
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(h -> h.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())   // formLogin().disable() in the older DSL
                .httpBasic(b -> b.disable())   // httpBasic().disable()
                .logout(l -> l.disable());
        return http.build();
    }
}

record LoginRequest(String username, String password) {
}

@RestController
@RequestMapping("/api/auth")
class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    AuthController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
        }
        if (request.getSession(false) != null) {
            request.changeSessionId();                         // protection against session fixation
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);       // creates the session if needed
        return ResponseEntity.ok(Map.of("success", true, "username", authentication.getName()));
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();                               // the session is gone, the cookie stays
        }
        SecurityContextHolder.clearContext();
        return Map.of("success", true);
    }

    @GetMapping("/me")
    Map<String, Object> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }
}
```

```properties
# application.properties
server.servlet.session.timeout=30m
server.servlet.session.cookie.secure=${COOKIE_SECURE:true}
```

### Spola

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.STRING;

import java.time.Duration;
import java.time.InstantSource;
import spola.json.JsonBoolean;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.codec.JsonCodec;
import spola.security.AuthResult;
import spola.security.Authenticator;
import spola.security.Identity;
import spola.web.Responses;
import spola.web.Router;
import spola.web.RouterBuilder;
import spola.web.SessionOptions;
import spola.web.Sessions;

/** The README example "Inloggning med session": a JSON API whose login is kept in a session cookie. */
public final class LoginExample {

    private LoginExample() {
    }

    /**
     * The body of {@code POST /api/auth/login}.
     *
     * @param username the username
     * @param password the password
     */
    public record Login(String username, String password) {

        /**
         * Without the password, so that it never ends up in a log line.
         *
         * @return {@code Login[username=...]}
         */
        @Override
        public String toString() {
            return "Login[username=" + username + "]";
        }
    }

    /** {@code {"username":"...","password":"..."}}, mapped explicitly (no reflection). */
    public static final JsonCodec<Login> LOGIN = JsonCodec.object(Login::new,
            field("username", STRING, Login::username),
            field("password", STRING, Login::password));

    /**
     * The sessions: 30 minutes idle, the cookie {@code SESSION} with {@code Path=/},
     * {@code HttpOnly} and {@code SameSite=Lax}.
     *
     * @param secure {@code config.booleanValue("cookie.secure")}: {@code true} sets {@code Secure}
     *               on every response, also over {@code http}; with {@code false} the cookie is
     *               {@code Secure} when the request's scheme is {@code https} (over TLS, or behind
     *               a TLS proxy with {@code ForwardedHeaders} registered first)
     * @param clock  {@code InstantSource.system()} in production; the tests move their own
     * @return the sessions
     */
    public static Sessions sessions(boolean secure, InstantSource clock) {
        return Sessions.inMemory(SessionOptions.DEFAULT
                .withIdleTimeout(Duration.ofMinutes(30))
                .withCookie(cookie -> cookie.secure(secure)), clock); // HttpOnly, SameSite=Lax
    }

    /**
     * The rules: login, logout and the health check are open, everything else needs a login.
     *
     * @param sessions the sessions that keep the login
     * @return the security
     */
    public static WebSecurity security(Sessions sessions) {
        return WebSecurity.builder()
                .sessions(sessions) // the identity is read from the session
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes, behind the security filter alone.
     *
     * @param routes        {@code Router.builder()}; the tests pass one that logs to them
     * @param security      the security
     * @param authenticator checks usernames and passwords
     * @return the router
     */
    public static Router router(RouterBuilder routes, WebSecurity security, Authenticator authenticator) {
        return routes
                .filter(security.filter()) // only this one: it runs sessions.filter() itself
                .post("/api/auth/login", request -> {
                    var body = request.readJson(LOGIN);
                    return switch (authenticator.authenticate(body.username(), body.password())) {
                        case AuthResult.Authenticated(Identity identity) -> {
                            WebSecurity.login(request, identity); // a new session id with the identity
                            yield Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE,
                                    "username", JsonString.of(identity.name())));
                        }
                        case AuthResult.Failed failed -> Responses.json(request, 401,
                                JsonObject.of("success", JsonBoolean.FALSE));
                    };
                })
                .post("/api/auth/logout", request -> {
                    WebSecurity.logout(request); // the session is removed, the cookie expires
                    return Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE));
                })
                .get("/api/auth/me", request -> Responses.json(request, 200,
                        JsonObject.of("username", JsonString.of(WebSecurity.requireIdentity(request).name()))))
                .get("/actuator/health", request -> Responses.json(request, 200,
                        JsonObject.of("status", JsonString.of("UP"))))
                .build();
    }
}
```

I produktion startas det så här (`users` är appens `UserStore`):

```java
var sessions = LoginExample.sessions(config.booleanValue("cookie.secure"), InstantSource.system());
var router = LoginExample.router(Router.builder(), LoginExample.security(sessions),
        Authenticator.of(users, Pbkdf2PasswordHasher.defaults()));
var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), router);
```

`router` tar en `RouterBuilder` bara för att testerna ska kunna ge en som loggar till dem och
har fler vägar; i en app står `Router.builder()` direkt i metoden. Testerna kör exemplet genom en
riktig server med `HttpClient` och `CookieManager`: `/actuator/health` ger 200 utan
`Set-Cookie`, `/api/auth/me` ger 401 utan `WWW-Authenticate`, fel lösenord ger
`{"success":false}`, rätt lösenord ger `{"success":true,"username":"anna"}` och ett
`Set-Cookie`, `/api/auth/me` ger sedan `{"username":"anna"}`, och efter utloggningen ger både
cookien och det gamla id:t 401.

Gränsen per användare är 10 utan att något anges. Ett annat värde ges till byggaren, som
`maximumSessions(n)` i Spring Security:

```java
// Spring Security
http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED).maximumSessions(3));

// Spola
WebSecurity.builder()
        .sessions(sessions)
        .maximumSessions(3) // a fourth login ends the user's least recently used session
        ...
```

### Cachar

Ett svar till en inloggad användare, och ett svar som bär sessions-id:t eller en CSRF-token, får
aldrig sparas av en delad cache (en proxy eller ett CDN) och lämnas ut till någon annan.

- **Svar till en inloggad förfrågan** (identiteten från sessionen eller en mekanism som
  `HttpBasic`) får `Cache-Control: no-store` när handlern inte satt något `Cache-Control`. Ett
  värde som handlern satt behålls, till exempel `private, no-cache` eller `max-age=60`, utom ett
  med `public` eller `s-maxage` (eller ett som inte går att tolka): då ersätts alla
  `Cache-Control`-fält med ett `Cache-Control: no-store`. Det gäller också routerns 404 och
  mappade fel.
- **Svar som sätter sessionscookien eller `XSRF-TOKEN`** (och svaret som tar bort
  sessionscookien vid utloggning) behåller handlerns värde bara när det redan håller delade cachar
  borta: `no-store` eller `private` (utan fältlista), och varken `public` eller `s-maxage`. Allt
  annat, också `max-age=60` och `no-cache`, och inget alls, blir `Cache-Control: no-store`.
- 401 och 403 har alltid `Cache-Control: no-store`. **Ett anonymt svar utan cookie** lämnas som
  handlern gjorde det, så öppna sidor kan cachas som förut.
- Direktivens namn jämförs utan hänsyn till skiftläge, och alla `Cache-Control`-fält räknas.
  `Expires`, `Pragma` och riktade fält som `CDN-Cache-Control` och `Surrogate-Control` ändras inte;
  ett CDN som följer ett sådant fält i stället för `Cache-Control` kan fortfarande spara svaret,
  så sätt inga sådana på svar som kan bära en cookie eller en inloggad användares data.

| Handlern satte | Svar som sätter en cookie | Inloggat svar utan cookie | Anonymt svar utan cookie |
|---|---|---|---|
| inget | `no-store` | `no-store` | inget |
| `public, max-age=60` | `no-store` | `no-store` | `public, max-age=60` |
| `s-maxage=60` | `no-store` | `no-store` | `s-maxage=60` |
| `max-age=60` | `no-store` | `max-age=60` | `max-age=60` |
| `private, max-age=60` | `private, max-age=60` | `private, max-age=60` | `private, max-age=60` |
| `private, no-cache` | `private, no-cache` | `private, no-cache` | `private, no-cache` |
| `no-store` | `no-store` | `no-store` | `no-store` |

### Spring Security och Spola, inloggning med session

| Spring Security | Spola |
|---|---|
| `sessionManagement(s -> s.sessionCreationPolicy(IF_REQUIRED))` | `WebSecurity.builder().sessions(sessions)`; säkerhetsfiltret skapar ingen session, bara `login` gör det |
| `HttpSessionSecurityContextRepository` (`SecurityContext` i sessionen) | identiteten i sessionen under en nyckel som bara `spola.security.web` har; filtret läser den före mekanismerna |
| `server.servlet.session.timeout`, `server.servlet.session.cookie.*` | `SessionOptions.DEFAULT.withIdleTimeout(...).withCookie(c -> c.secure(...))` |
| `@RequestBody LoginRequest` (Jackson) | `request.readJson(LOGIN)` med en `JsonCodec` som skrivs explicit |
| `authenticationManager.authenticate(...)` | `authenticator.authenticate(username, password)` med `AuthResult` |
| `request.changeSessionId()` och `saveContext(...)` | `WebSecurity.login(request, identity)` (`Sessions.renewFor` och identiteten i sessionen) |
| `sessionManagement(s -> s.maximumSessions(3))` | `WebSecurity.builder().maximumSessions(3)`; standard 10 |
| `HeaderWriterFilter` med `CacheControlHeadersWriter` | säkerhetsfiltret sätter `Cache-Control: no-store` på inloggade svar och svar med en cookie |
| `session.invalidate()` och `SecurityContextHolder.clearContext()` | `WebSecurity.logout(request)` (`Sessions.invalidate`) |
| `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` | inget att ställa in: utan `HttpBasic` har 401 inget `WWW-Authenticate` |
| `httpBasic(b -> b.disable())` | ingen `authentication(HttpBasic.of(...))` |
| `authentication.getName()` | `WebSecurity.requireIdentity(request).name()` |

### Avvikelser från Spring Security, inloggning med session

| Avvikelse | Varför |
|---|---|
| Ingen `formLogin()` | Inloggningen är en vanlig handler som kontrollerar lösenordet med `Authenticator` och anropar `WebSecurity.login`, och den svarar med JSON. Det finns inget `UsernamePasswordAuthenticationFilter`, inget HTML-formulär och inga omdirigeringar, så det finns inget att stänga av. |
| Ingen `unauthenticated(...)` (`HttpStatusEntryPoint`) | 401 har bara `WWW-Authenticate` för `HttpBasic`-mekanismer. Utan `HttpBasic` är svaret redan det som `HttpStatusEntryPoint(UNAUTHORIZED)` ger, så en inställning som bara upprepar standardbeteendet tillför inget. Med `HttpBasic` registrerad har 401 `WWW-Authenticate`, som i Spring. |
| Identiteten i sessionen är en ögonblicksbild | Sessionen bär den `Identity` som `login` fick. Ändrade roller, ett avstängt konto eller en borttagen användare i `UserStore` påverkar inte en inloggad session förrän utloggning eller utgång, som i Springs standardinställning. Användaren läses inte om per förfrågan. |
| Sessionen kommer före Basic | Har förfrågans session en identitet provas inga mekanismer, inte heller `HttpBasic`: fel Basic-uppgifter bredvid en giltig session ger inte 401. Utan session fungerar Basic som förut. En okänd eller utgången sessionscookie ger aldrig 401 i sig, så att inloggningen på en `permitAll`-väg fungerar med en gammal cookie. |
| `login` ändrar inte förfrågans egen identitet | I inloggningsförfrågan svarar `WebSecurity.identity(request)` som när filtret körde (oftast anonym), och reglerna prövas inte om; nästa förfrågan ser identiteten. Identiteten är ett värde på förfrågan, inte ett `SecurityContextHolder` som går att skriva om mitt i. Svara med den `Identity` som skickades in. Samma sak gäller `logout`. |
| Bara `security.filter()` registreras | Säkerhetsfiltret kör sessionsfiltret självt, ytterst, så att det aldrig hamnar på fel plats. Registreras `sessions.filter()` också når sessionsfiltret förfrågan två gånger och svaret blir 500. |
| `Responses.json(request, 200, JsonObject.of(...))` i stället för `Map.of(...)` | JSON mappas explicit och utan reflektion (`spola.json`). |
| CSRF-skyddet slås på uttryckligen | Spring har CSRF-skydd påslaget som standard; här krävs `csrf(Csrf.cookie())` (se [CSRF-skydd för en SPA](#csrf-skydd-för-en-spa)). Exemplet ovan har det inte, så att det visar inloggningen för sig. |
| Inloggningar loggas inte | Varken lyckade eller misslyckade inloggningar ger en loggrad (auditloggning är ett eget behov). |
| Högst 10 sessioner per användare som standard | Spring har ingen gräns utan `maximumSessions(n)`. Här är gränsen 10 utan att något anges, så att ett konto som loggar in om och om igen inte kan fylla `maxSessions` och ge alla andras inloggningar 503. En klient som loggar in för varje anrop utan att spara cookien förlorar sina äldsta sessioner. |
| En avslutad session tas bort direkt | Spring markerar den minst nyligen använda sessionen som utgången, och `ConcurrentSessionFilter` avslutar den vid nästa förfrågan (med `expiredUrl` eller ett meddelande). Här tas den bort vid inloggningen, och nästa förfrågan med dess cookie är anonym (401 på en skyddad väg). Ingen `maxSessionsPreventsLogin(true)` (den nya inloggningen nekas aldrig), ingen `SessionRegistry` för att lista eller avsluta en användares sessioner, och bara inloggningar räknas. |
| Ny, tom session när en annan användare loggar in | Springs `changeSessionId()` behåller attributen också när en annan användare loggar in över en inloggad session. Här avslutas den förra användarens session och inloggningen får en tom; samma användare, eller en session utan identitet, behåller attributen som i Spring. |

## CSRF-skydd för en SPA

En Angular-app som pratar med sitt JSON-API på samma origin skyddas mot CSRF med Angulars
standardmönster, en dubbelinskickad cookie: motsvarigheten till Spring Securitys `csrf(...)` med
`CookieCsrfTokenRepository.withHttpOnlyFalse()` och `CsrfTokenRequestAttributeHandler`, plus ett
`CsrfCookieFilter` som sätter cookien redan på första svaret. Servern sätter cookien `XSRF-TOKEN`,
som appens JavaScript kan läsa, och Angular skickar värdet i huvudet `X-XSRF-TOKEN` på varje
anrop som kan ändra något. Ett sådant anrop utan matchande huvud får 403.

- `WebSecurity.builder().csrf(Csrf.cookie())` slår på skyddet, med och utan `sessions(...)`.
  Cookien är `XSRF-TOKEN=<token>; Path=/; SameSite=Lax`, utan `HttpOnly` (Angular måste kunna
  läsa den).
- Cookien får `Secure`, som i Spring, när förfrågans schema är `https`:
  `XSRF-TOKEN=<token>; Path=/; Secure; SameSite=Lax`. Samma regel gäller sessionscookien. Över
  TLS är schemat `https`, och bakom en TLS-proxy blir det `https` när `ForwardedHeaders`
  registreras före `security.filter()` (se [Bakom Caddy](#bakom-caddy) nedan). Schemat är det som
  förfrågan har när den når säkerhetsfiltret; `login` och `logout` i handlern ändrar det inte.
  `Csrf.cookie().withCookie(c -> c.secure(true))` ger `Secure` på varje svar, också över `http`.
  Regeln går inte att stänga av.
- `GET`, `HEAD`, `OPTIONS` och `TRACE` kontrolleras inte. Alla andra metoder, också `PURGE` och
  `post` med gemener, kräver exakt ett `X-XSRF-TOKEN`-fält med en välformad token (43 tecken
  Base64url) som är lika med en av förfrågans `XSRF-TOKEN`-cookies, jämförd i konstant tid.
  Annars blir svaret 403, samma svar som en nekad regel, och varken handlern eller filtren
  innanför säkerhetsfiltret körs. Kontrollen gäller varje väg: `permitAll`-vägar, inloggningen,
  utloggningen och förfrågningar med Basic-uppgifter.
- Ordningen i säkerhetsfiltret: sessionsfiltret, 400 för flera `Authorization`-fält,
  CSRF-kontrollen, identiteten (sessionen och sedan mekanismerna), reglerna. En förfalskad
  förfrågan når alltså aldrig `Authenticator` och kostar ingen PBKDF2-beräkning.
- Saknar förfrågan en välformad `XSRF-TOKEN`-cookie sätter svaret en ny token, vilket svar det än
  är (handlerns, 401, 403, 404, mappade fel), så att Angular har cookien före sitt första
  `POST`. En förfrågan som har en får ingen ny, så token byts inte vid varje anrop.
- `WebSecurity.login` och `WebSecurity.logout` ger svaret en ny token. Webbläsaren byter ut
  cookien, och den gamla token slutar fungera.
- Servern sparar inga token, varken i minnet eller i sessionen: huvudet jämförs med cookien i
  samma förfrågan.
- Ett svar som sätter `XSRF-TOKEN` kan aldrig sparas av en delad cache: det får
  `Cache-Control: no-store` om handlerns värde inte redan har `no-store` eller `private` (och
  varken `public` eller `s-maxage`), så `public, max-age=60`, `s-maxage=60` och `max-age=60`
  ersätts. Se [Cachar](#cachar).
- Skyddet bygger på att en annan origin inte kan läsa cookien och inte kan sätta ett eget huvud
  utan CORS. Det skyddar inte mot XSS (skript på appens egna sidor kan läsa cookien) och inte mot
  en origin som CORS släpper igenom med huvudet.

### Angular

```ts
// app.config.ts
export const appConfig: ApplicationConfig = {
  providers: [
    provideHttpClient(withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })),
  ],
};
```

Namnen är Angulars standard, så `withXsrfConfiguration` kan också utelämnas. Angular skickar
huvudet på anrop som inte är `GET` eller `HEAD` till relativa URL:er (samma origin), och läser
cookien inför varje anrop, så den nya token efter inloggningen används direkt.

### Spring Security

```java
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .authorizeHttpRequests(r -> r
                        .requestMatchers("/api/auth/login", "/api/auth/logout").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .exceptionHandling(h -> h.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable());
        return http.build();
    }
}

/** Loads the deferred token on every request, so that the repository sets XSRF-TOKEN on the first response. */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        token.getToken();                                       // renders the cookie
        chain.doFilter(request, response);
    }
}
```

`AuthController` är densamma som i [Inloggning med session](#inloggning-med-session).

### Spola

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.STRING;

import java.time.Duration;
import java.time.InstantSource;
import spola.json.JsonBoolean;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.codec.JsonCodec;
import spola.security.AuthResult;
import spola.security.Authenticator;
import spola.security.Identity;
import spola.web.Responses;
import spola.web.Router;
import spola.web.RouterBuilder;
import spola.web.SessionOptions;
import spola.web.Sessions;

/** The README example "CSRF-skydd för en SPA": a JSON API for Angular with a session login and CSRF protection. */
public final class CsrfExample {

    private CsrfExample() {
    }

    /**
     * The body of {@code POST /api/auth/login}.
     *
     * @param username the username
     * @param password the password
     */
    public record Login(String username, String password) {

        /**
         * Without the password, so that it never ends up in a log line.
         *
         * @return {@code Login[username=...]}
         */
        @Override
        public String toString() {
            return "Login[username=" + username + "]";
        }
    }

    /** {@code {"username":"...","password":"..."}}, mapped explicitly (no reflection). */
    public static final JsonCodec<Login> LOGIN = JsonCodec.object(Login::new,
            field("username", STRING, Login::username),
            field("password", STRING, Login::password));

    /**
     * The sessions: 30 minutes idle, the cookie {@code SESSION} with {@code Path=/},
     * {@code HttpOnly} and {@code SameSite=Lax}.
     *
     * @param secure {@code config.booleanValue("cookie.secure")}: {@code true} sets {@code Secure}
     *               on every response, also over {@code http}; with {@code false} the cookie is
     *               {@code Secure} when the request's scheme is {@code https} (over TLS, or behind
     *               a TLS proxy with {@code ForwardedHeaders} registered first)
     * @param clock  {@code InstantSource.system()} in production; the tests move their own
     * @return the sessions
     */
    public static Sessions sessions(boolean secure, InstantSource clock) {
        return Sessions.inMemory(SessionOptions.DEFAULT
                .withIdleTimeout(Duration.ofMinutes(30))
                .withCookie(cookie -> cookie.secure(secure)), clock); // HttpOnly, SameSite=Lax
    }

    /**
     * The rules: login, logout and the health check are open, everything else needs a login, and
     * every request that changes something needs the CSRF header.
     *
     * @param sessions the sessions that keep the login
     * @return the security
     */
    public static WebSecurity security(Sessions sessions) {
        return WebSecurity.builder()
                .sessions(sessions) // the identity is read from the session
                .csrf(Csrf.cookie()) // XSRF-TOKEN / X-XSRF-TOKEN, 403 on a mismatch
                .rule(RequestMatcher.exactly("/api/auth/login"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/api/auth/logout"), AccessRule.permitAll())
                .rule(RequestMatcher.exactly("/actuator/health"), AccessRule.permitAll())
                .rule(RequestMatcher.anyRequest(), AccessRule.authenticated())
                .build();
    }

    /**
     * The routes, behind the security filter alone.
     *
     * @param routes        {@code Router.builder()}; the tests pass one that logs to them
     * @param security      the security
     * @param authenticator checks usernames and passwords
     * @return the router
     */
    public static Router router(RouterBuilder routes, WebSecurity security, Authenticator authenticator) {
        return routes
                .filter(security.filter()) // only this one: it runs sessions.filter() itself
                .post("/api/auth/login", request -> {
                    var body = request.readJson(LOGIN);
                    return switch (authenticator.authenticate(body.username(), body.password())) {
                        case AuthResult.Authenticated(Identity identity) -> {
                            WebSecurity.login(request, identity); // a new session id and a new CSRF token
                            yield Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE,
                                    "username", JsonString.of(identity.name())));
                        }
                        case AuthResult.Failed failed -> Responses.json(request, 401,
                                JsonObject.of("success", JsonBoolean.FALSE));
                    };
                })
                .post("/api/auth/logout", request -> {
                    WebSecurity.logout(request); // the session is removed, the cookie expires, a new CSRF token
                    return Responses.json(request, 200, JsonObject.of("success", JsonBoolean.TRUE));
                })
                .get("/api/auth/me", request -> Responses.json(request, 200,
                        JsonObject.of("username", JsonString.of(WebSecurity.requireIdentity(request).name()))))
                .get("/actuator/health", request -> Responses.json(request, 200,
                        JsonObject.of("status", JsonString.of("UP"))))
                .build();
    }
}
```

I produktion startas det som `LoginExample` i [Inloggning med session](#inloggning-med-session),
med `CsrfExample` i stället, och bakom en proxy som i [Bakom Caddy](#bakom-caddy) nedan. Testerna
kör exemplet genom en riktig server med `HttpClient` och `CookieManager`, där huvudet
`X-XSRF-TOKEN` sätts från cookien i `CookieManager` som Angular gör: `/actuator/health` ger 200
och en `XSRF-TOKEN` men ingen session, inloggning utan huvudet ger 403 utan att handlern eller
`Authenticator` anropas, inloggning med huvudet ger 200, en `SESSION`-cookie och en ny
`XSRF-TOKEN`, `/api/auth/me` ger `{"username":"anna"}`, utloggning med token från före
inloggningen ger 403 och med den aktuella 200, och därefter ger `/api/auth/me` 401.

### Bakom Caddy

Bakom en omvänd proxy som avslutar TLS (Caddy, nginx) kommer förfrågningarna över `http` från
proxyn. Registrera då `ForwardedHeaders` från `spola.web` först, före `security.filter()` som
`CsrfExample.router` lägger till. Då ser säkerhetsfiltret, och sessionsfiltret som det kör, schemat
`https` ur proxyns `X-Forwarded-Proto`, och båda cookierna blir `Secure` utan `secure(true)`
(`users` är appens `UserStore`):

```java
var sessions = CsrfExample.sessions(false, InstantSource.system()); // no secure(true)
var router = CsrfExample.router(
        Router.builder().filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks())), // first
        CsrfExample.security(sessions), Authenticator.of(users, Pbkdf2PasswordHasher.defaults()));
var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), router);
// behind Caddy, which sends X-Forwarded-Proto: https:
//   Set-Cookie: XSRF-TOKEN=<token>; Path=/; Secure; SameSite=Lax
//   Set-Cookie: SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax
//   Set-Cookie: SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax   (logout)
// directly over http://localhost:8080: the same lines without Secure
```

- **Ordningen.** Schemat som gäller är det som förfrågan har när den når `security.filter()`.
  Registreras `ForwardedHeaders` efter säkerhetsfiltret ser handlern `https`, men cookierna får
  inget `Secure`.
- **Bara från en betrodd proxy.** `X-Forwarded-Proto` läses bara när den direkta avsändaren är
  betrodd. Från en annan avsändare är schemat anslutningens, `http`, och cookierna får inget
  `Secure`. Lita helst bara på proxyns adress (`TrustedProxies.of("172.18.0.5")`, se "Bakom en
  omvänd proxy" i `spola.web`).
- **Utveckling** över `http://localhost` fungerar som förut: utan proxy och utan TLS är schemat
  `http`, cookierna saknar `Secure` och webbläsaren sparar dem.
- **`secure(true)`** behövs bara när cookierna ska vara `Secure` också över `http`, till exempel
  bakom en proxy utan `ForwardedHeaders`.
- **Andra cookies** som appen sätter med `Responses.withCookie` får inget automatiskt `Secure`.
  Appen väljer själv efter `request.scheme()`.

Ett test kör flödet ovan med `Router.builder().filter(ForwardedHeaders.trusting(...))`, sessioner
utan `secure(true)` och `X-Forwarded-Proto: https` på varje förfrågan, och jämför varje
`Set-Cookie` tecken för tecken med raden som `withCookie(c -> c.secure(true))` ger.

### Spring Security och Spola, CSRF

| Spring Security | Spola |
|---|---|
| `csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))` | `WebSecurity.builder().csrf(Csrf.cookie())` |
| `CsrfTokenRequestAttributeHandler` (token utan maskering) | huvudet jämförs med cookiens värde som det är |
| `CsrfCookieFilter` efter `CsrfFilter` | inget eget filter: säkerhetsfiltret sätter cookien på första svaret |
| `CookieCsrfTokenRepository.setCookieCustomizer(c -> c.secure(true))` | `Csrf.cookie().withCookie(c -> c.secure(true))` |
| `CookieCsrfTokenRepository` sätter `secure` efter `request.isSecure()` | `XSRF-TOKEN` får `Secure` när `request.scheme()` är `https` |
| `server.forward-headers-strategy=native` bakom en TLS-proxy | `Router.builder().filter(ForwardedHeaders.trusting(...))` före `security.filter()` |
| `CsrfFilter` med 403 från `AccessDeniedHandler` | kontrollen i `security.filter()`, samma 403 som en nekad regel |
| `CsrfFilter.DEFAULT_CSRF_MATCHER` (`GET`, `HEAD`, `TRACE`, `OPTIONS` kontrolleras inte) | samma metoder, jämförda exakt |
| `CsrfAuthenticationStrategy` och `CsrfLogoutHandler` | `WebSecurity.login` och `WebSecurity.logout` ger en ny token |

### Avvikelser från Spring Security, CSRF

| Avvikelse | Varför |
|---|---|
| Fasta namn | Cookien heter alltid `XSRF-TOKEN` och huvudet `X-XSRF-TOKEN` (`Csrf.COOKIE_NAME` och `Csrf.HEADER_NAME`), Angulars och Springs standard. Spring har `setCookieName` och `setHeaderName`; egna namn byggs när ett behov kommer. |
| Ingen `_csrf`-formulärparameter | Token läses bara ur huvudet, aldrig ur en formulärkropp eller en query. Spring godtar också parametern `_csrf` för HTML-formulär; en SPA skickar huvudet. |
| Ingen maskering av token | Huvudet ska vara exakt cookiens värde, som med `CsrfTokenRequestAttributeHandler`. Springs standard sedan 6.0, `XorCsrfTokenRequestAttributeHandler`, maskerar token per svar mot BREACH, men Angular skickar cookiens råa värde; därför väljer behovet bort maskeringen också i Spring. |
| Inga undantag per väg | Ingen `ignoringRequestMatchers(...)`: kontrollen gäller varje väg och varje metod utom `GET`, `HEAD`, `OPTIONS` och `TRACE`, också `permitAll`-vägar, inloggningen och utloggningen. En klient utan webbläsare (tjänst-till-tjänst) skickar samma värde i cookien och huvudet. |
| Ingen token i sessionen | Ingen `HttpSessionCsrfTokenRepository`: servern sparar inga token alls, bara cookien bär dem (som `CookieCsrfTokenRepository`). Skyddet fungerar utan sessioner och tar inget minne. |
| Kontrollen före autentiseringen | Kontrollen kommer före både sessionens identitet och mekanismerna. I Spring ligger `CsrfFilter` också före autentiseringsfiltren (`BasicAuthenticationFilter`, inloggningsfiltren), men sessionens `SecurityContext` laddas av ett filter före det. Här når en förfalskad förfrågan aldrig `Authenticator` och får 403 också anonymt på en skyddad väg. |
| Cookien sätts på första svaret utan ett eget filter | Spring laddar token lat sedan 6.0, så cookien sätts bara när något läser token, och behovet behöver ett `CsrfCookieFilter`. Här sätter säkerhetsfiltret cookien på varje svar till en förfrågan som saknar en välformad cookie, också 401, 403 och 404. |
| Ny token också med en egen inloggningshandler | Springs `CsrfAuthenticationStrategy` och `CsrfLogoutHandler` körs av Springs egna inloggnings- och utloggningsfilter; behovets `AuthController` (med `logout(l -> l.disable())`) byter alltså inte token. Här gör `WebSecurity.login` och `WebSecurity.logout` det alltid. |
| Strikt format | Ett huvud- eller cookievärde som inte är exakt 43 tecken ur `[A-Za-z0-9_-]` räknas som saknat, och två `X-XSRF-TOKEN`-fält ger 403. Spring jämför vilka värden som helst. |
| `Secure` efter schemat kan inte stängas av | Spring sätter `secure` efter `request.isSecure()` om inget annat sägs, och en cookie customizer (`c -> c.secure(false)`) kan ta bort det över HTTPS. Här finns inget sådant val: en `XSRF-TOKEN` som sätts över `https` har alltid `Secure`, eftersom en app på samma origin aldrig behöver en utan `Secure` från `https`. `withCookie(c -> c.secure(true))` ger `Secure` också över `http`. |
| `Cache-Control` när cookien sätts | Spring Securitys `CacheControlHeadersWriter` lämnar ett `Cache-Control` som handlern satt orört, också `public` på ett svar som sätter `XSRF-TOKEN`. Här ersätts allt som låter en delad cache spara svaret med `no-store`, så att ingen annan besökare får samma token. |
| `ForwardedHeaders` före säkerhetsfiltret | Tomcats `RemoteIpValve` körs före alla filter, så `request.isSecure()` är klientens schema överallt. Här är `ForwardedHeaders` ett filter som ska registreras före `security.filter()`. Registreras det efter får sessions- och CSRF-cookien inget `Secure` bakom en proxy, fast handlern ser `https`. |
