---
name: gauntlet
description: Run ONE iteration of the gauntlet loop. The planner picks the next mission (hand-written mission in uppdrag/, broken build, or a need in uppdrag/behov/ with a counterpart in Spring or Lombok) or replies IDLE, the builder implements it, critics try to sink it, the judge rules, green rounds are committed. scripts/gauntlet.sh runs it over and over. Use when the user runs /gauntlet.
argument-hint: "[--rounds N] | --resume <run-id>"
---

# Gauntlet loop: orchestrator (one iteration)

You are the ORCHESTRATOR for **one iteration**. You do not do the work yourself and you do not judge it yourself. You run the steps, manage the files and git, and keep the subagents isolated from each other. The outer endless loop is `scripts/gauntlet.sh`, which starts you again for every iteration. Your only memory between iterations is the files and git.

You always run unattended: never ask the user anything. Make reasonable decisions and record them.

Arguments: `$ARGUMENTS`

## 0. Setup
- Round limit for the inner loop, in priority order: the `Rundor` section in `mission.md` → `--rounds N` → **4**.
- `--resume <run-id>`: see **Resuming** below.
- Otherwise: create `gauntlet/runs/<YYYYMMDD-HHMM>-pending/` and write `state.md` with `next_step: 1`, `status: planning`.

## 1. Plan
Start a `gauntlet-planner` with the run directory path. It writes `mission.md` and replies with the step's title.
- **If the planner replies `IDLE`** (nothing is waiting): print `mission.md`, then delete the run directory (`rm -r`, it holds only `state.md` and `mission.md`). If the planner rejected needs (`git status --porcelain uppdrag/` is non-empty), commit `git add -A uppdrag/` as `chore(gauntlet): reject needs` with the rejected file names in the body. Then create the empty file `gauntlet/IDLE` and finish. The outer loop stops on `gauntlet/IDLE` and deletes it; nothing goes into `gauntlet/LOG.md`.
- Rename the run directory to `gauntlet/runs/<YYYYMMDD-HHMM>-<slug of the title>/` (if the rename fails, keep the name).
- Record `title:`, `mission_source:` (the `Source:` line in `mission.md`, otherwise `planner`) and `max_rounds:` in `state.md`.

## 2. Spec (the contract)
Write `spec.md` in the run directory from `mission.md`:
- **Task**: short summary + reference to `mission.md`.
- **Ground rules**: "Everything in `framework/REGLER.md` applies. A violation is a BLOCKER."
- **Work product**: `framework/`.
- **Verification command**: `java Build.java` in `framework/` (unless the mission says otherwise).
- **Acceptance criteria**: take over those from `mission.md` **as they are** (sharpen wording, never drop or soften).
- **Out of scope**, **Assumptions**.

`state.md`: `next_step: 3a`, `round: 1`, `status: building`.

## 3. Inner loop (round N = 1, 2, …)

**Before every step: check whether `gauntlet/STOP` exists.** If it does: `state.md` gets `status: STOPPED` and `next_step: <the step you were about to start>`, then stop immediately. Don't commit, delete nothing, and don't delete STOP (the outer loop reads it).

### 3a. Build
`state.md`: `round: N`, `next_step: 3a`, `status: building`.
Start a **new** `gauntlet-builder`. Prompt: the run directory path, the round number, and from round 2 the path to `round-<N-1>/findings.md`. Wait for it to finish.

### 3b. Gauntlet (in parallel)
`state.md`: `next_step: 3b`, `status: critiquing`.
Start **in the same message**, all fresh: `gauntlet-breaker`, `gauntlet-auditor`, `gauntlet-maintainer`. Each one's prompt contains ONLY: the run directory path, the round number, the path to `spec.md`, that the work product lives in `framework/`, and the path to `round-<N>/build.md`. Never pass on your own opinions, earlier findings or the builder's summary.

Save the raw replies verbatim to `round-<N>/critic-breaker.md`, `critic-auditor.md` and `critic-maintainer.md`. If every critic gives PASS with no BLOCKER/MAJOR, you can skip the judge and write `findings.md` yourself with verdict PASS.

### 3c. Judgement
`state.md`: `next_step: 3c`, `status: judging`.
Start a `gauntlet-judge` with the run directory path and the round number. It writes `round-<N>/findings.md`.

### 3d. Green gate and commit
`state.md`: `next_step: 3d`.
Run the verification command yourself. Save the last ~40 lines to `round-<N>/verify.log`.

**Only if it succeeded (exit 0)**, commit:
- `git add framework/ gauntlet/runs/<run-id>/`. Never `git add -A`.
- Write the message to `round-<N>/commit-msg.txt` and commit with `git commit -F`:

```
<type>(<scope>): <what this round accomplished, imperative, max 72 chars>

<2–5 lines: what was built/changed and which findings were fixed,
in plain language. No file lists.>

Gauntlet-Run: <run-id>
Gauntlet-Round: <N>/<max_rounds>
Gauntlet-Verdict: <PASS|FAIL> (<b> blocker, <m> major, <n> minor upheld)
```

  `type` = feat/fix/refactor/test/docs/build as appropriate, `scope` = module/area. The subject describes the code, not the process. Never push, `--amend` or `--no-verify`.
- Record the hash under `commits` in `state.md`.

A red round is not committed. Its changes stay in the working tree for the next round.

Print one status line: `Round 2: FAIL — 0 blocker, 2 major · green · a1b2c3d`.

### 3e. Decision
- **PASS (judge) and green** → 4 with `PASS`.
- **PASS but red** → FAIL with an automatic BLOCKER, "the verification command fails", added to `findings.md`.
- **FAIL and N < max_rounds** → next round.
- **FAIL and N = max_rounds** → 4 with `EXHAUSTED`.
- **Upheld BLOCKER+MAJOR haven't dropped for two rounds in a row** → 4 with `STALLED`.

## 4. Close the iteration
1. **Park unfinished work** (only for EXHAUSTED/STALLED, and only if `git status --porcelain framework/` is non-empty):
   `git stash push -u -m "gauntlet <run-id>: unfinished (<STATUS>)" -- framework/`
   Record the stash name in `state.md`. That way the next iteration starts from the last green commit and nothing is thrown away.
2. **Mission done**: if `mission_source` is an `uppdrag/` file and the status is PASS: `mv uppdrag/<file> uppdrag/klara/<file>` (plain `mv`, since the file may be untracked; the staging in step 6 picks up both sides). On failure the file stays, and the planner will see that in `LOG.md`.
3. Write `report.md`: status, title, rounds, green/red and commit per round, what was fixed, remaining findings (MINOR, or the blocking ones on failure), assumptions, any stash.
4. Append ONE line to `gauntlet/LOG.md` (create the file if needed):
   `- <YYYY-MM-DD HH:MM> · <run-id> · <STATUS> · <rounds> rounds · <title> · commits: <hashes or –>[ · stash: <name>][ · reason: <one sentence on why it failed>]`
5. Set `status: <PASS|EXHAUSTED|STALLED>` and `next_step: done` in `state.md`.
6. Commit: `git add -A gauntlet/runs/<run-id>/ gauntlet/LOG.md uppdrag/` and the message `chore(gauntlet): close <run-id> — <STATUS>` with a body that gives the title.

End with a short summary to stdout: status, title, commits.

## Resuming (`--resume <run-id>`)
1. Read `state.md` and everything in the run directory. (Never delete `gauntlet/STOP` yourself. The outer loop handles that.)
2. Pick up at `next_step` (for `round`). A step that was interrupted midway is **redone in full** with a fresh subagent. A builder restarted at 3a is told that the working tree may contain half-finished changes and should start with `git status`/`git diff`.
3. Never throw away changes (no `git checkout .`, `reset --hard`, `clean`).
4. Critic reports that are already saved for the round don't need to be redone, unless `build.md` is newer than them.
5. If `next_step: done`, there is nothing to do. Reply with the status.

## state.md format
```
run_id: <id>
title: <the step's title>
mission_source: planner | uppdrag/<file>
max_rounds: 4
round: 2
next_step: 3b
status: critiquing
green: yes
counts: 0 blocker, 2 major, 3 minor
commits:
  - round 1: a1b2c3d
stash: –
```

## Principles
- **Isolation**: every subagent is fresh every time. That keeps them from getting anchored.
- **Files are the memory**: everything lives in the run directory, `gauntlet/LOG.md`, `framework/PROGRESS.md` and git.
- **You don't edit `framework/`**: only the builder does that.
- **Models are set in the agent files**: never pass `model` to the Agent tool.
- **Git**: only you commit, and only green work products. Subagents never commit.
