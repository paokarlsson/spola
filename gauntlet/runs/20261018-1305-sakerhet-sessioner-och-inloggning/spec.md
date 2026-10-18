# Spec: Säkerhetsgranskning del 1 – sessioner och inloggning

## Uppgift
Rätta granskningsfynden G1–G5 i `spola.web` och `spola.security.web`: gräns för sessioner per användare, ny session vid byte av användare, inga delade cachar för cookie-svar och inloggade svar, och skyddad städning vid gränsen. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261018-1305-sakerhet-sessioner-och-inloggning/mission.md` (källa `uppdrag/15a-sakerhet-sessioner-och-inloggning.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

## Krav
- **Gräns per användare (G1).** `WebSecurity.Builder` får en inställning för högsta antal samtidiga
  sessioner per identitet (motsvarar Springs `sessionManagement().maximumSessions(n)`), med ett
  säkert standardvärde som byggaren väljer och motiverar i ARCHITECTURE.md (t.ex. 10). När en
  inloggning skulle överskrida gränsen avslutas användarens äldsta session (den som användes längst
  sedan), inte den nya inloggningen. Identiteter jämförs på namn. Kopplingen mellan namn och
  sessioner får inte läcka minne när sessioner går ut, ogiltigförklaras eller tas bort vid städning.
  Om `spola.web` behöver ett litet tillägg för detta (t.ex. att kunna avsluta en annan session än
  förfrågans) ska det vara paketprivat eller minimalt och motiveras.
- **Ny session vid byte av användare (G2).** Har förfrågans session redan en inloggad identitet med
  ett annat namn ger `login` en ny, tom session (den gamla ogiltigförklaras). Samma användare eller en
  anonym session förnyas som i dag (attributen behålls).
- **Ingen delad cache för sessions- och CSRF-cookies (G3).** När filtret lägger till `Set-Cookie` för
  sessionen eller `XSRF-TOKEN` ska svaret inte kunna sparas av en delad cache: saknas `Cache-Control`
  blir det `no-store` som i dag; har handlern satt ett värde med `public` eller `s-maxage` ersätts det
  med `no-store` (ett värde som redan är `private` eller `no-store` behålls).
- **Cache-skydd för inloggade svar (G4).** Säkerhetsfiltret lägger `Cache-Control: no-store` på varje
  svar till en förfrågan med inloggad identitet (session eller mekanism) som saknar `Cache-Control`.
  Ett värde som handlern satt behålls, utom att `public` och `s-maxage` ersätts som i G3.
- **Skyddad städning (G5).** Bara en städning vid gränsen körs åt gången; en förfrågan som inte får
  köra den svarar 503 utan att gå igenom lagret.
- Javadoc, README-avsnitten "Sessioner", "Inloggning med session" och "CSRF-skydd för en SPA" och
  avvikelsetabellerna uppdateras. PROGRESS.md säger att granskningen inte ersätter en mänsklig
  granskning; den meningen om säkerhetskritisk kod står kvar.

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0, och de nya testerna körs i sviterna för
   `spola.web` och `spola.security.web`.
2. `requires` och `exports` är oförändrade i alla moduler. Ingen reflektion, inga nya filtyper. Inga
   ändringar utanför `framework/modules/web`, `framework/modules/security-web` och dokumentationen.
   Befintliga tester får ändras bara där ett beteende ändras med avsikt (t.ex. ett exakt
   `Cache-Control`), och varje sådan ändring nämns i build.md.
3. G1: ett test genom en riktig `HttpServer` loggar in samma användare fler gånger än gränsen per
   användare (utan cookie). Användarens sessioner blir aldrig fler än gränsen, den äldsta slutar
   fungera (401), den senaste fungerar, och en annan användare kan logga in efteråt. Med
   `maxSessions` 50 och gränsen 5 visar ett test att 100 inloggningar av ett konto inte hindrar en
   andra användare. Ett test visar att kopplingen namn → sessioner är tom när alla sessioner gått ut
   eller loggats ut.
4. G1: ett test med 200 virtuella trådar som loggar in fem användare samtidigt visar att ingen
   användare har fler sessioner än gränsen och att `sessions.size()` stämmer.
5. G2: ett test visar att anna sparar ett värde i sin session, att bertil sedan loggar in från samma
   klient (utan utloggning) och inte ser värdet, att annas gamla id slutar fungera, och att samma
   användare som loggar in igen behåller sina värden.
6. G3: ett test visar att ett svar som skapar sessionen eller sätter `XSRF-TOKEN` och där handlern
   satt `public, max-age=60` eller `s-maxage=60` får `Cache-Control: no-store`, och att `private,
   max-age=60` behålls.
7. G4: ett test visar att ett inloggat `GET` utan cookie i svaret får `Cache-Control: no-store`, att
   ett anonymt svar på en `permitAll`-väg inte ändras, och att handlerns `private, no-cache` behålls.
8. G5: koden visar ett compare-and-set eller lås runt städningen vid gränsen; ett test med 200
   samtidiga nekade förfrågningar vid gränsen visar att städningen körs högst ett fåtal gånger
   (räknaren som redan finns för tester).
9. Inga sessions-id, lösenord eller tokens i loggrader, undantag eller problem-svar från de nya
   vägarna (markörtest som i 11a–11c).
10. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails gröna), och
    ingen regel i `framework/REGLER.md` är bruten.
11. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade: gränsen per användare och dess standardvärde,
    ny session vid byte av användare, cache-reglerna, och att fynden G1–G5 är rättade.

## Utanför scope
- Hastighetsbegränsning och låsning av konton.
- Absolut maxlivstid för sessioner.
- Att binda CSRF-token till sessionen.
- Säkerhetshuvuden utöver `Cache-Control` (nosniff, X-Frame-Options, HSTS).

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
