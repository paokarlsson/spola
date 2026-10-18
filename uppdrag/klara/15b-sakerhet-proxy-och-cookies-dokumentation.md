# Säkerhetsgranskning del 2: proxy, cookies och dokumentation (`spola.web`, `spola.security.web`)

## Mål
Granskningen (`gauntlet/granskningar/2026-10-18-sakerhet.md`) hittade att ett fel i
proxykonfigurationen tyst tar bort `Secure` från sessions- och CSRF-cookien, att exemplen för
Docker/Caddy inte går att nå från en annan container, och några påståenden i dokumentationen som
lovar mer än koden håller. Efter uppdraget ska en felkonfiguration synas i loggen första gången den
händer, och dokumentationen ska ge en produktionsinställning som inte beror på att proxyadressen
stämmer. Det här är **15b**, andra delen av rättningarna efter granskningen.

## Bakgrund / kontext
Läs `framework/REGLER.md`, `PROGRESS.md` och `ARCHITECTURE.md`, särskilt "Bakom en omvänd proxy"
(del 1 och 2) och "Sessioner". Fynden är verifierade med prober.

- **P1 (LOW).** Skickar en avsändare som `ForwardedHeaders` inte litar på `X-Forwarded-Proto: https`
  går förfrågan vidare med schemat `http`, och sessions- och CSRF-cookien sätts utan `Secure`, utan
  någon loggrad. Det händer i praktiken när Caddys adress ändras (container återskapad, IPv6) och
  appen använder `TrustedProxies.of("172.18.0.5")` som README rekommenderar. README säger dessutom
  att `secure(true)` bara behövs "bakom en proxy utan ForwardedHeaders".
- **P2 (dokumentation).** Exemplen `WhoamiApp` (web-README, "Bakom en omvänd proxy") och "Bakom
  Caddy" (security-web-README) startar servern med `HttpServerOptions.DEFAULT`, som binder loopback.
  Caddy i en annan container kan inte ansluta. Kommentaren säger "Caddy och appen på Dockers nät".
- **P3 (LOW).** Sessionscookien heter `SESSION` som standard och det första levande värdet vinner.
  Från en syskondomän går det att plantera en egen inloggad session med `Domain=förälder; Path=/api`
  (cookie tossing), så att offret använder appen som angriparen. `__Host-SESSION` med
  `secure(true)` stoppar det och fungerar redan, men rekommenderas inte tydligt för produktion.
- **P4 (dokumentation).** Sessions Javadoc och ARCHITECTURE säger att "en minnesdump innehåller inga
  giltiga id". Id:t finns som `String` i förfrågans `Cookie`-huvud och i `Set-Cookie` medan
  förfrågan pågår. Påståendet ska vara att lagret bara innehåller hashar av id:n.
- **P5 (dokumentation).** `privateNetworks()` litar på varje container i nätet, och Dockers
  userland-proxy kan dölja klientens adress. Klientadressen får aldrig användas som behörighet bara
  för att den är privat.

## Krav
- **Varning vid fel konfiguration (P1).** `ForwardedHeaders` loggar en rad på `WARNING` (logger
  `spola.web`) första gången per filterinstans som en avsändare som filtret inte litar på skickar
  `X-Forwarded-Proto` eller `X-Forwarded-For`. Raden namnger bara avsändarens adress, aldrig
  huvudvärdena, och sägs bara en gång (inte per förfrågan). Beteendet i övrigt är oförändrat
  (huvudena ignoreras).
- **Produktionsinställning (P1, P3).** README-avsnitten för sessioner, CSRF och "Bakom Caddy"
  rekommenderar för en app som bara nås över https: `withCookieName("__Host-SESSION")` och
  `withCookie(c -> c.secure(true))` för sessionen, och `Csrf.withCookie(c -> c.secure(true))` för
  CSRF, så att `Secure` inte beror på att proxyadressen stämmer. Meningen om att `secure(true)` bara
  behövs utan `ForwardedHeaders` rättas.
- **Exempel som går att nå (P2).** Docker-exemplen binder en adress som nås från andra containrar
  (`0.0.0.0` eller `::`) och säger varför; de testade exempelkällorna ändras på samma sätt och
  README-testerna hålls gröna.
- **Rättade påståenden (P4, P5).** Javadoc, README, ARCHITECTURE och PROGRESS säger det koden
  håller.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. `requires`/`exports` oförändrade, ingen reflektion, inga nya filtyper, inga ändringar utanför
   `framework/modules/web`, `framework/modules/security-web` och dokumentationen.
3. P1: ett test visar att en förfrågan från en avsändare som inte är betrodd med
   `X-Forwarded-Proto: https` ger exakt en `WARNING`-rad med avsändarens adress och utan värdet,
   att tio sådana förfrågningar fortfarande ger en rad, och att en betrodd avsändare och en förfrågan
   utan huvudena inte ger någon rad. Ett markörtest visar att huvudvärdena aldrig loggas.
4. P1/P3: README visar produktionsinställningen med `__Host-SESSION` och `secure(true)` för båda
   cookies; ett kompilerat exempel (eller ett test) visar att den byggs och ger
   `Set-Cookie: __Host-SESSION=...; Path=/; Secure; HttpOnly; SameSite=Lax` och `XSRF-TOKEN` med
   `Secure` också över vanlig http.
5. P2: exemplen i README binder en adress som nås från andra containrar, och README-testerna jämför
   fortfarande exemplen med källorna.
6. P4/P5: `grep` efter "minnesdump" och "memory dump" i `framework/` hittar inget påstående om att
   inga giltiga id finns i minnet; README-avsnittet "Bakom en omvänd proxy" säger att en privat
   klientadress inte är en behörighet.
7. Guardrails gröna och ingen regel i `framework/REGLER.md` bruten.
8. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade (varningen och att P1–P5 är rättade).

## Utanför scope
- Att göra `Secure` obligatoriskt eller att byta standardnamnet på sessionscookien.
- Att behandla flera levande sessionscookies i samma förfrågan som anonym.
- RFC 7239 `Forwarded`.

## Begränsningar (valfritt)
Se `framework/REGLER.md`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
