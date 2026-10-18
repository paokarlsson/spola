# Säkerhetsgranskning del 4: hemligheter och sökvägar i `spola.client`

## Mål
Granskningen (`gauntlet/granskningar/2026-10-18-sakerhet.md`) bekräftade det öppna fyndet från
uppdrag 14: med `Authorization: Bearer <nyckel>` döljs bara hela huvudvärdet, så en server som svarar
med enbart nyckeln ("Invalid token: <nyckel>") lägger den i `getMessage()`, `toString()` och
stackspåret. Den hittade också att README:s loggexempel loggar det råa utdraget, att JDK:ns egen
loggning skriver ut nycklar, och att `..` i en sökväg når andra resurser på samma värd. Efter
uppdraget ska API-nycklar i `Authorization` aldrig synas i det modulen skapar, och exemplen ska visa
ett säkert sätt att logga. Det här är **15d**, sista delen av rättningarna efter granskningen.

## Bakgrund / kontext
Läs `framework/REGLER.md`, `PROGRESS.md` och `ARCHITECTURE.md`, särskilt "REST-klient
(`spola.client`)". Fynden är verifierade med prober; granskningen provbyggde K1-rättningen nedan i en
kopia (grönt, 330/330).

- **K1 (MEDIUM).** `RestRequest.secrets()` lägger bara till hela huvudvärden. För
  `Authorization`/`Proxy-Authorization` läcker texten efter schemat (`Bearer`, `bearer`, `Basic`) och,
  för Basic, det avkodade `user:lösenord` när servern skickar tillbaka just det.
- **K2 (LOW).** README:s exempel (`log.warn("Anthropic svarade " + e.status() + ": " +
  e.bodyExcerpt())`, rad ~197–203 och ~228) och `RestClientException`s Javadoc (rad ~17) loggar det
  råa `bodyExcerpt()`, där inget döljs, med kommentaren "aldrig nyckeln".
- **K3 (LOW).** Med `-Djdk.httpclient.HttpClient.log=headers|requests|all` eller
  `jdk.internal.httpclient.debug` skriver JDK:ns `HttpClient` ut alla huvudvärden och queryn. Modulen
  kan inte stänga av det, och dokumentationen säger inget.
- **K4 (LOW).** `Uris.pathProblem` och `baseProblems` godtar segmenten `.` och `..` (också `%2e`), och
  de skickas oförändrade. `URLEncoder.encode("..")` är `..`, så ett kodat id från användaren kan nå en
  överordnad resurs med appens nycklar (`/v1/threads/` + `..` + `/runs`).
- **K5 (dokumentation).** Dokumentationen säger att orsaken från `spola.json` kan citera "upp till 40
  tecken"; i själva verket upp till 40 tecken per värde i upp till 100 problem (och 200 tecken av ett
  undantagsmeddelande).

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

## Begränsningar (valfritt)
Se `framework/REGLER.md`. Säkerhetskritiskt: data utifrån räknas som realistisk.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
4
