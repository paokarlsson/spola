# Spec: Säkerhetsgranskning del 2 – proxy, cookies och dokumentation

## Uppgift
Rätta granskningsfynden P1–P5: varning en gång när en obetrodd avsändare skickar X-Forwarded-*, produktionsinställning med __Host-SESSION och secure(true), Docker-exempel som går att nå, och rättade påståenden i dokumentationen. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261018-1553-sakerhet-proxy-och-cookies/mission.md` (källa `uppdrag/15b-sakerhet-proxy-och-cookies-dokumentation.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

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

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
