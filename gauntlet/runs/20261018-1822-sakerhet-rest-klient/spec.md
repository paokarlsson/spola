# Spec: Säkerhetsgranskning del 4 – hemligheter och sökvägar i spola.client

## Uppgift
Rätta granskningsfynden K1–K5 i `spola.client`: dölj inloggningsuppgifter efter schemat i Authorization (och avkodad Basic), säkert loggexempel, varning för JDK:ns HTTP-loggning, inga punktsegment i sökvägar, och rättad text om orsaken från spola.json. Fullständigt uppdrag, bakgrund och designbeslut: `gauntlet/runs/20261018-1822-sakerhet-rest-klient/mission.md` (källa `uppdrag/15d-sakerhet-rest-klient.md`). Designbesluten i uppdraget är bindande.

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (med JDK 25: `export PATH=/opt/jdk-25/bin:$PATH`).

## Krav
- **Dolda inloggningsuppgifter (K1).** För `Authorization` och `Proxy-Authorization` (skiftlägesokänsligt
  namn) räknas också texten efter första mellanslaget (trimmad) som hemlig, och för schemat `Basic`
  (skiftlägesokänsligt) dessutom det Base64-avkodade `user:lösenord` och lösenordet efter första `:`.
  Avkodning som misslyckas ger inget extra. Allt som redan läser listan (meddelanden och orsaker)
  använder den utan andra ändringar.
- **Säkert loggexempel (K2).** `RestClientResponseException` får en metod som ger utdraget med
  samma döljning som `getMessage()` (namnet väljer byggaren, t.ex. `safeExcerpt()`), och README:s
  och Javadocens exempel loggar den eller `getMessage()`. `bodyExcerpt()` är rå som i dag och
  dokumenteras som det.
- **Varning för JDK:ns loggning (K3).** `RestClient`s Javadoc och README säger att
  `jdk.httpclient.HttpClient.log` (headers, requests, all) och `jdk.internal.httpclient.debug` aldrig
  ska slås på i produktion, eftersom JDK:n skriver ut huvudvärden och query.
- **Inga punktsegment (K4).** En sökväg eller bas-URI med ett segment som är `.` eller `..` (också
  procentkodat, `%2e`/`%2E`) ger `IllegalArgumentException` med en fast text. Javadoc för
  sökvägsparametrarna säger det.
- **Rättad text (K5).**

## Acceptanskriterier
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. Inga ändringar utanför `framework/modules/client` och dokumentationen; `module-info.java` och
   testet som fäster den oförändrade; ingen reflektion, inga nya filtyper.
3. K1: `Failures.all()` får fall för en 401 som nämner token efter `Bearer` ensam, en med `bearer`
   (gemener) och en som nämner det avkodade `user:lösenord` från Basic. De ingår i `SecretTests`
   ekotester, och ingen markör finns i `getMessage()`, `toString()`, `getLocalizedMessage()` eller
   det utskrivna stackspåret med orsaker. Ett huvud som inte är `Authorization` behandlas som i dag.
4. K2: ett test visar att den nya metoden döljer ett ekat huvudvärde och att `bodyExcerpt()` är rå;
   README-exemplen (kompilerade källor som jämförs med README) loggar den nya metoden eller
   `getMessage()`, och "aldrig nyckeln" står inte längre bredvid ett rått utdrag.
5. K3: Javadoc för `RestClient` och README innehåller varningen med egenskapernas namn.
6. K4: ett tabelldrivet test visar att `/a/../b`, `/..`, `/./x`, `/a/%2e%2e/b`, `/a/%2E/b` och en
   bas-URI med `..` ger `IllegalArgumentException` vid anropet respektive `build()`, och att `/a..b`,
   `/...` och `/a.b` godtas.
7. K5: dokumentationen beskriver gränsen för orsaken från `spola.json` som ovan.
8. Guardrails gröna och ingen regel i `framework/REGLER.md` bruten.
9. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade (K1–K5 rättade).

## Utanför scope
- Att dölja enskilda query-parametrar.
- Att vägra starta när JDK:ns loggning är påslagen.

## Antaganden
- Designbesluten i mission.md är en del av kontraktet; avvikelser ska motiveras i build.md och ARCHITECTURE.md.
- Maskinens standard-`java` är JDK 21; JDK 25 finns i `/opt/jdk-25`.
- Byggaren ändrar inget utanför `framework/`, utom modultabellen i rotens `README.md` när ett kriterium kräver det.
