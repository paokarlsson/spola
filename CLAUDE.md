# Gauntlet loop

Riggen bygger ett Java 25-ramverk som ersätter Spring och Lombok **efter behov**. Ramverket
byggs inte mot en komplett ersättare i förväg. Det växer när ett riktigt projekt behöver något
som Spring eller Lombok hade gett det (se `framework/MONSTER.md`). Loopen kör så länge det finns
uppdrag eller behov att ta. Varje iteration väljer nästa uppdrag. En byggare gör jobbet,
oberoende kritiker försöker sänka det, en domare väger fynden, och varje runda med grönt bygge
committas.

## Namn
Ramverket heter **Spola**. JPMS-moduler heter `spola.<modul>` (t.ex. `spola.web`, `spola.guardrails`), och paketen börjar med `spola.<modul>`.

## Viktiga filer
| Fil | Vad |
|---|---|
| `framework/REGLER.md` | Grundreglerna. De är lag och går före allt annat. |
| `framework/MONSTER.md` | Hur en Spola-modul ser ut, modulkatalogen och vad som hör hemma i Spola. |
| `uppdrag/PLAN.md` | Levererade steg och en idélista. Idéerna byggs bara när ett behov kommer. |
| `uppdrag/NN-*.md` | Uppdrag. Lägsta numret körs först. Klara uppdrag flyttas till `uppdrag/klara/`. |
| `uppdrag/behov/*.md` | Behov från appprojekt. Planeraren gör om dem till uppdrag eller avvisar dem. |
| `uppdrag/MALL.md`, `uppdrag/BEHOV-MALL.md` | Mallar för uppdrag och behov. |
| `docs/app-claude.md` | Mall för `CLAUDE.md` i projekt som använder Spola. |
| `framework/PROGRESS.md`, `ARCHITECTURE.md` | Läget och designbesluten (underhålls av byggaren). |
| `gauntlet/LOG.md` | En rad per iteration. |

Vad ramverket är och hur det används står i `README.md`. Den här filen handlar om riggen.

## Kör (bara i devcontainern, gärna i `tmux`)
```
scripts/gauntlet.sh                  # oändlig loop
scripts/gauntlet.sh --iterations 4   # högst 4 iterationer
scripts/gauntlet.sh once             # en iteration
scripts/gauntlet.sh stop             # mjukt stopp efter pågående steg
scripts/gauntlet.sh status           # läget och senaste loggraderna
```
Loopen startar Claude Code med `--dangerously-skip-permissions` i devcontainern, som har en
brandvägg. Kör den inte direkt på din egen dator.

Hårt stopp: Ctrl+C. Startar du igen fortsätter en avbruten iteration automatiskt
(`--resume`). Ett steg som avbröts mitt i görs om från början.

Loopen stannar av sig själv när:
- inget uppdrag eller behov väntar (planeraren svarar `IDLE`), eller
- 3 iterationer i rad har misslyckats (`--max-fail N`).

Loopen hanterar själv:
- **Användningsgränsen.** Meddelandet `hit your … limit · resets …` räknas inte som ett
  misslyckande. Loopen väntar till återställningen och fortsätter med samma körning.
- **Långsamma kritiker.** `CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS=0` gör att `claude -p` väntar
  på bakgrundsagenterna i stället för att döda dem efter 10 minuter.

Att tänka på medan loopen kör:
- **Datorn får inte sova.** I WSL står processerna still när Windows går i viloläge.
- **Kör inte `java Build.java` själv.** Bygget raderar och bygger om `framework/out/` och
  kan ge byggaren ett falskt rött resultat.
- Följ läget med `scripts/gauntlet.sh status`. Loggen i `gauntlet/logs/` skrivs först när en
  iteration är klar.

## Ordningen planeraren väljer i
1. Handskrivna uppdrag i `uppdrag/NN-*.md`. Ett bokstavssuffix (`05c-...`) skjuter in ett
   uppdrag mellan två andra.
2. Trasigt bygge eller blockerande kända problem.
3. Behov i `uppdrag/behov/`, äldst först. Planeraren prövar varje behov mot regeln i
   `MONSTER.md`: **finns motsvarigheten i Spring eller Lombok, går det inom REGLER.md och
   täcks det inte redan, då byggs det i Spola.** Annars avvisas behovet med en motivering och
   flyttas till `uppdrag/behov/avvisade/`. Ett godkänt behov blir ett uppdrag i samma stil som
   01–03, sparas i `uppdrag/` och behovsfilen flyttas till `uppdrag/behov/klara/`. Appens
   användningsfall, alltså koden den vill kunna skriva, blir ett acceptanskriterium.
4. Inget väntar: `IDLE`. Loopen stannar utan att räknas som misslyckad. Planeraren hittar aldrig
   på arbete, varken från idélistan i `PLAN.md` eller från glapp mot Spring som ingen bett om.

Ett uppdrag som är för stort delas upp i delar (`11a`, `11b`). Ett steg som misslyckats två
gånger delas alltid upp.

## Behov från appprojekt
1. AI:n i appen behöver något. Den läser `framework/MONSTER.md` och använder en befintlig modul
   om det finns en.
2. Saknas det skriver den ett behov enligt `uppdrag/BEHOV-MALL.md` till
   `uppdrag/behov/<ÅÅÅÅ-MM-DD>-<namn>.md` i det här repot. Om repot inte finns lokalt skriver
   den behovet som ett GitHub-issue med samma innehåll, och du flyttar in det.
3. Appen bygger under tiden en minimal lösning lokalt, i Spolas stil, och fortsätter arbeta.
4. Loopen bygger modulen. Efter en release byter appen sin lokala lösning mot Spola-modulen.

Mallen för appens `CLAUDE.md` finns i `docs/app-claude.md`.

## Agenter (`.claude/agents/`)
| Agent | Modell | Roll |
|---|---|---|
| planner | opus | Väljer nästa uppdrag, prövar behov och skriver uppdrag |
| builder | opus | Gör jobbet. Den enda som ändrar i `framework/`. |
| breaker | opus | Försöker bevisa att det inte fungerar |
| auditor | sonnet | Bockar av kriterier och regler |
| maintainer | sonnet | Bedömer tydlighet, enkelhet och "magi" |
| judge | opus | Verifierar fynden och ger utslaget |

Orkestreraren (`.claude/skills/gauntlet/SKILL.md`) kör en iteration.
`scripts/gauntlet.sh` är den yttre loopen och startar en ny Claude-process per iteration.
Allt minne mellan iterationerna ligger i filer och git.

### Hur fynden graderas
Breaker och domare graderar efter hur troligt felet är vid verklig användning:
- **MAJOR:** det en utvecklare i god tro kan råka ut för, alltså vanlig indata och dess kantfall,
  vanlig samtidighet och dokumenterade löften som inte håller.
- **MINOR:** det som kräver avsiktligt elak eller patologisk indata, till exempel ett `toString`
  som kastar `Error`, kedjor tusentals nivåer djupa eller förklädda sätt runt guardrails.
- **Undantag:** säkerhetskritisk kod och data utifrån (HTTP, protokoll, autentisering, tokens,
  kryptografi). Där är konstruerad indata realistisk och fortfarande MAJOR eller BLOCKER.

Domaren håller sig till `spec.md` och `REGLER.md` och vidgar inte scope med egna utslag.
Utan den regeln jagade kritikerna specialfall i det oändliga.

## Bygge och verifiering
- `java Build.java` i `framework/`. Inga byggverktyg från tredje part (se REGLER.md §1).
- Verifieringskommandot ska gå grönt för att en runda ska committas.

## Versioner och releaser
- Versionen står i konstanten `VERSION` i `framework/Build.java` (semver), eftersom guardrails
  bara tillåter `.java`- och `.md`-filer i `framework/`. `java Build.java version` skriver ut den.
  Varje jar bär den som modulversion. Så länge Spola är `0.x` får API:er ändras:
  MINOR för en ny modul eller en ändring som bryter, PATCH för rättningar.
- `java Build.java release` bygger, testar och skriver `framework/out/release/` med
  `<modul>-<version>.jar`, `<modul>-<version>-sources.jar` och `SHA256SUMS`. `spola.demo`
  släpps inte.
- `scripts/release.sh 0.2.0` på `main` sätter versionen, bygger, committar
  `chore(release): v0.2.0` och skapar taggen. Lägg till `--push` eller pusha själv:
  `git push origin main v0.2.0`.
- En pushad tagg `v*` startar `.github/workflows/release.yml`. Det bygger om från taggen och
  publicerar en GitHub-release med jar-filerna och `SHA256SUMS`. Workflowet använder bara
  skalsteg och verktyg som redan finns i GitHubs Ubuntu-image, inga actions från marketplace.
- Loopen gör aldrig releaser. Kör inte `release.sh` medan loopen kör (skriptet vägrar).

## Git
- Rundor med grönt bygge committas (Conventional Commits och `Gauntlet-*`-trailers).
- En iteration avslutas med en `chore(gauntlet)`-commit.
- Arbete som inte blev klart (EXHAUSTED/STALLED) parkeras med `git stash`. Inget kastas
  bort.
- Loopen pushar aldrig. Huvudgrenen är `main`, och push till remote görs för hand.

## Körningskatalog `gauntlet/runs/<id>/`
`mission.md`, `spec.md`, `state.md`, `round-N/{build,critic-*,findings}.md`,
`round-N/verify.log`, `round-N/commit-msg.txt`, `report.md`
