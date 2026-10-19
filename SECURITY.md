# Säkerhet

Spola är ett hobby- och labbprojekt i version 0.x. Det finns ingen support, inga utlovade tider
för rättningar och inga garantier (se [`LICENSE`](LICENSE)). Säkerhetsmodulerna
(`spola.security`, `spola.security.web`) är inte granskade av en människa. Använd dem inte för
att skydda något på riktigt.

En säkerhetsgranskning gjord av AI-agenter finns i
[`gauntlet/granskningar/`](gauntlet/granskningar/), och kända begränsningar står i
[`framework/PROGRESS.md`](framework/PROGRESS.md).

## Rapportera en sårbarhet

Skriv inte om sårbarheten i ett publikt issue. Använd GitHubs privata rapportering: fliken
**Security** i repot och knappen **Report a vulnerability**. Beskriv vad som går fel, vilken
modul och version det gäller, och gärna en förfrågan eller ett test som visar felet.

Rättningar görs i mån av tid och släpps i nästa version. Äldre versioner rättas inte.

## In English

Spola is a hobby and lab project at version 0.x, with no support, no promised response times and
no warranty (see [`LICENSE`](LICENSE)). The security modules have not been reviewed by a human;
do not use them to protect anything real.

Please report vulnerabilities privately with **Report a vulnerability** on the repository's
**Security** tab, not in a public issue. Include the module, the version and, if you can, a
request or a test that shows the problem. Fixes are made when time allows and ship in the next
version only.
