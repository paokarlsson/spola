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

## Kör (i devcontainern, gärna i `tmux`)
```
scripts/gauntlet.sh            # oändlig loop
scripts/gauntlet.sh once       # en iteration
scripts/gauntlet.sh stop       # mjukt stopp efter pågående steg
scripts/gauntlet.sh status     # läget och senaste loggraderna
```
Hårt stopp: Ctrl+C. Startar du igen fortsätter en avbruten iteration automatiskt.
Loopen stannar av sig själv när:
- planeraren förklarar ramverket komplett (`COMPLETE`), eller
- 3 iterationer i rad har misslyckats (`--max-fail N`).

## Ordningen planeraren väljer i
1. Handskrivna uppdrag i `uppdrag/NN-*.md`.
2. Trasigt bygge eller blockerande kända problem.
3. Nästa rad i `PLAN.md`. Planeraren skriver då uppdraget i samma stil som 01–03 och
   sparar det i `uppdrag/`, så att du kan läsa och redigera.
4. När planen är klar: det största återstående glappet mot Spring och Lombok.
5. Inget kvar: `COMPLETE`.

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

## Bygge och verifiering
- `java Build.java` i `framework/`. Inga byggverktyg från tredje part (se REGLER.md §1).
- Verifieringskommandot ska gå grönt för att en runda ska committas.

## Git
- Rundor med grönt bygge committas (Conventional Commits och `Gauntlet-*`-trailers).
- En iteration avslutas med en `chore(gauntlet)`-commit.
- Arbete som inte blev klart (EXHAUSTED/STALLED) parkeras med `git stash`. Inget kastas
  bort och inget pushas.

## Körningskatalog `gauntlet/runs/<id>/`
`mission.md`, `spec.md`, `state.md`, `round-N/{build,critic-*,findings}.md`,
`round-N/verify.log`, `round-N/commit-msg.txt`, `report.md`
