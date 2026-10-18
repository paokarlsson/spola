# Inloggning med sessioner och CSRF-skydd för ett JSON-API

Projekt: psykologen (paokarlsson/psykologen), backend i Spring Boot som ska kunna gå över till Spola
Spola-version: 0.1.0

## Behov
Psykologen är en Angular-app som pratar med ett JSON-API på samma origin. Användaren loggar in
med `POST /api/auth/login` (JSON med användarnamn och lösenord), och därefter bär en
sessionscookie inloggningen. Alla andra `/api/*`-vägar kräver inloggning och svarar 401 utan
`WWW-Authenticate`, så att webbläsaren inte visar Basic-rutan. Utloggning tar bort sessionen.
Varje ändrande anrop (`POST`, `PUT`, `PATCH`, `DELETE`) skyddas mot CSRF med Angulars
standardmönster: servern sätter en cookie `XSRF-TOKEN` som JavaScript kan läsa, och Angular
skickar värdet i huvudet `X-XSRF-TOKEN`. `spola.security.web` har bara HTTP Basic i dag, och
`PROGRESS.md` säger att sessioner och CSRF väntar på "uppdrag 11".

## Motsvarighet i Spring eller Lombok
Spring Security och servlet-API:t:
- `HttpSession`, `server.servlet.session.timeout`, `server.servlet.session.cookie.{http-only,same-site,secure}`
- `HttpSessionSecurityContextRepository` och `SecurityContextHolder` (inloggningen sparas i sessionen)
- `request.changeSessionId()` (skydd mot session fixation) och `session.invalidate()`
- `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` (401 utan `WWW-Authenticate`)
- `CookieCsrfTokenRepository.withHttpOnlyFalse()` med `CsrfTokenRequestAttributeHandler`,
  plus ett filter som ser till att cookien sätts redan på första svaret

## Användningsfall
Så här ser det ut i Spring i dag (förkortat från `auth/SecurityConfig.java` och
`auth/AuthController.java`):

```java
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
    .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable());

// login: authenticate, request.changeSessionId(), spara SecurityContext i sessionen
// logout: request.getSession(false).invalidate()
// me:     authentication.getName()
```

Koden appen vill kunna skriva med Spola (namnen är förslag, planeraren bestämmer):

```java
var sessions = Sessions.inMemory(SessionOptions.DEFAULT
        .withIdleTimeout(Duration.ofMinutes(30))
        .withCookie(cookie -> cookie.secure(config.booleanValue("cookie.secure"))));   // HttpOnly, SameSite=Lax

var security = WebSecurity.builder()
        .sessions(sessions)                                   // identiteten läses ur sessionen
        .csrf(Csrf.cookie())                                  // XSRF-TOKEN / X-XSRF-TOKEN, 403 vid fel
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

Acceptans för appen: Angulars `HttpClient` med
`withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })` fungerar mot
servern utan ändringar, och samma flöde går att köra med `java.net.http.HttpClient` och en
`CookieManager` i ett test: `GET` ger `XSRF-TOKEN`, login utan huvudet ger 403, login med
huvudet ger 200 och en ny sessionscookie, `/api/auth/me` ger namnet, logout gör att `/me` ger 401.

## Krav som inte syns i koden (valfritt)
- Sessions-id från en kryptografiskt säker slumpkälla, minst 128 bitar, och jämförelser som
  inte läcker tid.
- Session fixation: ett sessions-id som fanns före inloggningen gäller inte efter den.
- Sessioner går ut efter inaktivitet (30 minuter i appen). Utgångna sessioner städas bort, och
  antalet sessioner i minnet har en övre gräns så att anonyma förfrågningar inte kan fylla
  minnet.
- Sessioner skapas bara när något sparas i dem. Ett anrop till `/actuator/health` skapar ingen.
- CSRF: `GET`, `HEAD`, `OPTIONS` och `TRACE` kontrolleras inte; övriga metoder kräver att
  huvudet matchar cookien, annars 403. Också `login` och `logout` kontrolleras, som i Spring.
- Cookien `XSRF-TOKEN` är inte `HttpOnly` och har `Path=/`. Den sätts på första svaret så att
  Angular har den före första `POST`.
- Appen kör bakom en TLS-proxy, så `Secure` på cookies måste kunna sättas uttryckligen.
- Säkerhetskritiskt: konstruerad indata utifrån räknas som realistisk.

## Lokal lösning i appen (valfritt)
Ingen än. Appen kör Spring Security tills Spola har detta.
