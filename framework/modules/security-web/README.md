# spola.security.web

Säkerhet i Spola, del 2: bryggmodulen mellan `spola.security` och `spola.web`. Modulen ersätter
kärnan i Spring Securitys webbdel: `SecurityFilterChain` med `authorizeHttpRequests`
(`requestMatchers(...).hasRole(...)`, `permitAll()`, `authenticated()`, `denyAll()`,
`anyRequest()`), `httpBasic()`, den inloggade användaren per förfrågan (`SecurityContext`,
`@AuthenticationPrincipal`), `@PreAuthorize("hasRole('ADMIN')")` som ett filter på en grupp och
svaren 401 (`AuthenticationEntryPoint`) och 403 (`AccessDeniedHandler`). Allt är vanlig
Java-kod utan annoteringar, utan reflektion och utan `ThreadLocal`.

> **Säkerhetskritisk kod.** Låt en människa granska modulen innan den används på riktigt.

Modulen kräver `spola.web` och `spola.security` (båda transitivt). Ingen av dem beror på den
här modulen eller på varandra: hashning och autentisering fungerar utan HTTP, och webblagret
fungerar utan säkerhet.

- `WebSecurity.builder()` med `authentication(...)` (en eller flera mekanismer) och
  `rule(matcher, accessRule)`, sedan `build()`. `security.filter()` registreras på routern,
  **först**, så att filtret ligger ytterst och ser varje förfrågan (även routerns 400, 404, 405
  och automatiska `OPTIONS`).
- `HttpBasic.of(authenticator, realm)`: HTTP Basic enligt RFC 7617 med strikt tolkning.
- `RequestMatcher.under(prefix)`, `exactly(path)`, `method(method, inner)`, `anyRequest()` eller
  en lambda.
- `AccessRule.permitAll()`, `denyAll()`, `authenticated()`, `hasRole(role)`,
  `hasAnyRole(roles...)` eller en lambda.
- `WebSecurity.identity(request)` (`Optional<Identity>`) och
  `WebSecurity.requireIdentity(request)` (`Identity` eller 401) i en handler.
- `security.require(rule)`: ett filter för en grupp (`RouteGroup.filter(...)`).

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
| Ingen formulärinloggning eller utloggning ännu | De kräver sessioner och kommer i uppdrag 11 tillsammans med sessioner, JWT, CSRF och CORS. |
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
