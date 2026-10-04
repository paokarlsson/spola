# Gauntlet loop

Riggen bygger ett Java 25-ramverk som ersätter Spring och Lombok. Det sker i en loop som
kör tills ramverket är komplett eller tills du stoppar den. Varje iteration väljer nästa
uppdrag. En byggare gör jobbet, oberoende kritiker försöker sänka det, en domare väger
fynden, och varje runda med grönt bygge committas.

## Namn
Ramverket heter **Spola**. JPMS-moduler heter `spola.<modul>` (t.ex. `spola.di`, `spola.guardrails`), och paketen börjar med `spola.<modul>`.

## Viktiga filer
| Fil | Vad |
|---|---|
| `framework/REGLER.md` | Grundreglerna. De är lag och går före allt annat. |
| `uppdrag/PLAN.md` | Färdplanen (01–20). |
| `uppdrag/NN-*.md` | Uppdrag. Lägsta numret körs först. Klara uppdrag flyttas till `uppdrag/klara/`. |
| `uppdrag/MALL.md` | Mall för uppdrag. |
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
- planeraren förklarar ramverket komplett (`COMPLETE`), eller
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
   uppdrag mellan två rader i planen.
2. Trasigt bygge eller blockerande kända problem.
3. Nästa rad i `PLAN.md`. Planeraren skriver då uppdraget i samma stil som 01–03 och
   sparar det i `uppdrag/`, så att du kan läsa och redigera.
4. När planen är klar: det största återstående glappet mot Spring och Lombok.
5. Inget kvar: `COMPLETE`.

En rad som är för stor delas upp i delar (`07a`, `07b`). Ett steg som misslyckats två gånger
delas alltid upp.

## Agenter (`.claude/agents/`)
| Agent | Modell | Roll |
|---|---|---|
| planner | opus | Väljer och skriver nästa uppdrag |
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

## Git
- Rundor med grönt bygge committas (Conventional Commits och `Gauntlet-*`-trailers).
- En iteration avslutas med en `chore(gauntlet)`-commit.
- Arbete som inte blev klart (EXHAUSTED/STALLED) parkeras med `git stash`. Inget kastas
  bort.
- Loopen pushar aldrig. Huvudgrenen är `main`, och push till remote görs för hand.

## Körningskatalog `gauntlet/runs/<id>/`
`mission.md`, `spec.md`, `state.md`, `round-N/{build,critic-*,findings}.md`,
`round-N/verify.log`, `round-N/commit-msg.txt`, `report.md`
