# <Uppdragets namn>

<!--
Mall för uppdrag. Uppdragsfiler heter NN-namn.md (t.ex. 04-config.md).
Loopen tar lägsta numret först; klara uppdrag flyttas till uppdrag/klara/.
Behov från appprojekt (uppdrag/behov/, se BEHOV-MALL.md) gör planeraren om till
uppdrag i den här formen och sparar dem här, så att du kan läsa och redigera dem.
-->

## Mål
Vad ska finnas när uppdraget är klart, och varför? 2–5 meningar.

## Bakgrund / kontext
Vad finns redan i `framework/`? Läs `framework/REGLER.md`, `PROGRESS.md` och `ARCHITECTURE.md`.

## Krav
- ...

## Acceptanskriterier
Numrerade och *kontrollerbara*: en kritiker ska kunna avgöra ja/nej genom att läsa koden och köra kommandon.
1. `java Build.java` i `framework/` avslutas med exitkod 0.
2. ...
N. `PROGRESS.md` och `ARCHITECTURE.md` är uppdaterade.

## Utanför scope
- ...

## Begränsningar (valfritt)
Se `framework/REGLER.md`.

## Verifieringskommando (valfritt)
`java Build.java` i `framework/`.

## Rundor (valfritt)
Standard 4.
