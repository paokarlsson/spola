# Spec: Web del 2: JSON-kroppar, mediatyper och innehållsförhandling

## Uppgift
Lägg till strikt `MediaType`- och `Accept`-tolkning, innehållsförhandling, JSON-kroppar in (`WebRequest.readJson`) och ut (`Responses`) med 415/406/400/413-semantik i `spola.web`. Fullständig bakgrund och designbeslut: `mission.md` (bindande, inkl. avsnittet "Designbeslut" och "Krav").

## Grundregler
Allt i `framework/REGLER.md` gäller. Ett brott är en BLOCKER.

## Arbetsprodukt
`framework/`

## Verifieringskommando
`java Build.java` i `framework/` (exitkod 0).

## Acceptanskriterier
Övertagna oförändrade från mission.md. Varje kriterium ska vara uppfyllt i sin helhet; designbesluten och kraven i mission.md är också bindande.

1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. `modules/web/src/module-info.java` har inga nya `requires`. Huvudkoden importerar inget från
   `java.lang.reflect` eller `java.lang.invoke`.
3. Ett tabelldrivet test av `MediaType.parse` har minst 20 giltiga fall och minst 20
   ogiltiga. Det täcker gemener för typ, subtyp och parameternamn, `quoted-string` med
   escape, `charset` utan hänsyn till skiftläge, `+json`-suffix, `*/*` och `type/*`, och för
   de ogiltiga alla fel som räknas upp i besluten (bl.a. `*/json`, en dubbel parameter,
   styrtecken, icke-ASCII, ett oavslutat citat, 65 parametrar och 1 025 tecken).
4. Ett tabelldrivet test av `Accept` och `negotiate` har minst 20 fall. Det visar:
   - att det mest specifika intervallet bestämmer kvaliteten oberoende av ordningen;
   - `q=0` som utesluter en typ trots `*/*`;
   - att serverns ordning vinner vid lika kvalitet;
   - ett saknat `Accept`;
   - flera `Accept`-fält;
   - ett kommatecken i ett citerat värde;
   - accept-ext efter `q`;
   - ogiltiga q-värden (`1.5`, `-1`, `0.0001`, `abc`) som ger 400;
   - att 101 intervall ger 400.
5. Ett test över en riktig `HttpServer` (port 0, `127.0.0.1`) med `HttpClient` visar
   `POST`, `GET`, `PUT` och `DELETE` med JSON enligt README-exemplet. `POST` ger 201 med
   `Location` och kroppen. Svaren har `Content-Type: application/json` och rätt
   `Content-Length`. Kroppen går att läsa tillbaka med samma kodek. `HEAD` ger samma huvuden
   utan kropp.
6. Ett test visar 415, med `Accept: application/json` i svaret, för:
   - ett saknat `Content-Type`;
   - `text/plain`;
   - `application/xml`;
   - ett felaktigt `Content-Type`;
   - `charset=iso-8859-1`;
   - `Content-Encoding: gzip`.

   Samma test visar att `application/json; charset=UTF-8`, `application/json;charset="utf-8"`
   och `application/vnd.example+json` godtas.
7. Ett test visar 400 med problem-svar för:
   - en tom kropp;
   - ett syntaxfel, där detaljen innehåller rad och kolumn men inte en unik markörsträng
     från kroppen;
   - en kropp över djupgränsen.
8. Ett test visar att en kropp med minst tre mappningsfel på olika nivåer ger 400, och att
   `errors` innehåller alla tre med `path` och `message` i dokumentordning. En kropp med
   över 100 fel ger högst 100 poster i `errors` och en detalj som anger antalet. Ett
   felvärde med `%0A`/`\u001b` hamnar inte oescapat i någon loggrad.
9. Ett test visar storleksgränserna:
   - en kropp över `RouterBuilder.jsonLimits(...).maxInputSize()` (men under
     `maxBodySize`) ger 413 utan att hela kroppen buffras; mät med en kropp som är många
     gånger större än gränsen och skickas strömmande (chunked) och visa att svaret kommer;
   - en kropp över `HttpLimits.maxBodySize` ger fortfarande kroppens status från
     `spola.http`;
   - `readJson(codec, limits)` med egna gränser går före routerns.
10. Ett test visar att ett andra `readJson` i samma förfrågan ger 500 (via
    `IllegalStateException`), och att en mapper för `WebException` kan ge en egen
    respons för både 415 och 400 från `readJson`.
11. Ett test visar 406 från `Responses.json` för `Accept: text/html` och för
    `Accept: application/json;q=0, */*`. Det visar också 200 för `Accept: */*`,
    `application/*` och `application/json;q=0.5, text/html;q=0.9`. Ett problem-svar skickas
    som `application/problem+json` även med `Accept: text/html`.
12. Ett test visar att ett `encode`-fel i `Responses.json` ger 500 utan detaljer, inte 400.
13. Ett test med 200 samtidiga klienter (virtuella trådar) som postar olika JSON-kroppar
    visar att varje klient får tillbaka sin egen kropp.
14. `README.md` i `modules/web/` har CRUD-exemplet bredvid Spring Boot, förhandlingsexemplet
    och de nya avvikelseraderna enligt kraven.
15. Inga annoteringar utöver tillåtelselistan och inga beroenden utöver JDK:n (guardrails är
    gröna). Inga nya filtyper finns under `framework/`, och ingen regel i
    `framework/REGLER.md` är bruten.
16. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade. De beskriver:
    - uppdelningen 08b/08c;
    - `MediaType`-syntaxen och gränserna;
    - specificitetsregeln och oavgjorda fall i `negotiate`;
    - ordningen i `readJson` och dess statuskoder;
    - `errors`-formatet som gemensamt felformat inför 09;
    - strömmande parsning och 413;
    - att problem-svar inte förhandlas;
    - att `encode`-fel ger 500.

## Utanför scope
- Cookies, `Set-Cookie` och formulärkroppar (08c).
- Multipart, filuppladdning, `Content-Encoding` (gzip) i någon riktning, och strömmande
  JSON-svar (`Responses.json` buffrar).
- `produces`/`consumes` som en del av routingen (förhandlingen görs i handlern).
- Validering av indata utöver det kodeken gör (uppdrag 09).
- `Accept-Charset`, `Accept-Language`, ETag och villkorliga förfrågningar.
- CORS och säkerhetshuvuden (uppdrag 10–11).

## Antaganden
- Designbesluten i mission.md ("Designbeslut") är fattade och ska följas; avvikelser måste motiveras i ARCHITECTURE.md och får inte försvaga något kriterium.
- Små tillägg i `spola.http`/`spola.json` är tillåtna endast om de motiveras i ARCHITECTURE.md och befintliga tester förblir oförändrade och gröna.
- Tester binder bara `127.0.0.1` på port 0.
- Rundgräns: 5 (från mission.md).
