#!/usr/bin/env bash
# Endless gauntlet loop. Every iteration is a fresh `claude -p "/gauntlet"`:
# plan the next mission -> build -> critics -> judge -> commit green rounds.
#
#   scripts/gauntlet.sh                 run until COMPLETE, stop, or too many failures
#   scripts/gauntlet.sh --max-fail 5    stop after 5 failed iterations in a row (default 3)
#   scripts/gauntlet.sh --iterations 2  at most 2 iterations, then stop
#   scripts/gauntlet.sh once            exactly one iteration
#   scripts/gauntlet.sh stop            soft stop: finish the current step, then stop
#   scripts/gauntlet.sh status          state of the latest iteration + the log
#
# Hard stop: Ctrl+C. Starting again resumes an unfinished iteration automatically.
# Inside the devcontainer (firewalled) permission prompts are skipped.
set -uo pipefail
cd "$(dirname "$0")/.."

FINAL='^(PASS|EXHAUSTED|STALLED|COMPLETE)$'
max_fail=3
max_iter=0   # 0 = no limit

latest_run() {
  local runs=() d
  for d in gauntlet/runs/*/; do [[ -f "$d/state.md" ]] && runs+=("${d%/}"); done
  (( ${#runs[@]} )) && printf '%s\n' "${runs[@]}" | sort | tail -1
}
run_status() { [[ -n "${1:-}" ]] && sed -n 's/^status:[[:space:]]*//p' "$1/state.md" | head -1; }

case "${1:-}" in
  stop)
    touch gauntlet/STOP
    echo "Stop requested. The current step finishes, then the loop stops."
    exit 0 ;;
  status)
    run=$(latest_run)
    if [[ -n "$run" ]]; then echo "== $run"; cat "$run/state.md"; else echo "No iterations yet."; fi
    [[ -f gauntlet/STOP ]] && echo "(stop requested)"
    [[ -f gauntlet/LOG.md ]] && { echo; echo "== gauntlet/LOG.md (last 10)"; tail -10 gauntlet/LOG.md; }
    exit 0 ;;
  once) max_iter=1; shift ;;
esac
while [[ $# -gt 0 ]]; do
  case "$1" in
    --max-fail)   max_fail=$2; shift 2 ;;
    --iterations) max_iter=$2; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

if [[ "${DEVCONTAINER:-}" == "true" ]]; then
  perm=(--dangerously-skip-permissions)
else
  echo "WARNING: not in the devcontainer. Commands outside the allowlist will stall the loop." >&2
  perm=(--permission-mode acceptEdits)
fi

mkdir -p gauntlet/logs
rm -f gauntlet/STOP
trap 'echo; echo "Interrupted. Run scripts/gauntlet.sh again to continue where it left off."; exit 130' INT TERM

fails=0
iter=0
while :; do
  if [[ -f gauntlet/STOP ]]; then
    rm -f gauntlet/STOP; echo "Stopped on request."; exit 0
  fi
  if (( max_iter > 0 && iter >= max_iter )); then
    echo "Done: $iter iteration(s)."; exit 0
  fi

  before=$(latest_run); before_status=$(run_status "$before")
  if [[ "$before_status" == "COMPLETE" ]]; then
    echo "The framework is declared complete ($before). Nothing more to do."; exit 0
  fi
  if [[ -n "$before" && ! "$before_status" =~ $FINAL ]]; then
    prompt="/gauntlet --resume $(basename "$before")"
  else
    prompt="/gauntlet"
  fi

  iter=$((iter + 1))
  log="gauntlet/logs/$(date +%Y%m%d-%H%M%S)-iter$iter.log"
  echo "=== Iteration $iter · $(date '+%F %T') · $prompt · log: $log"
  claude -p "$prompt" "${perm[@]}" 2>&1 | tee "$log"
  rc=${PIPESTATUS[0]}

  after=$(latest_run); after_status=$(run_status "$after")
  echo "=== Iteration $iter done: ${after_status:-?} ($(basename "${after:-–}")), exit code $rc"

  case "$after_status" in
    COMPLETE) echo "The framework is declared complete."; exit 0 ;;
    STOPPED)  rm -f gauntlet/STOP; echo "Stopped on request. Run again to continue."; exit 0 ;;
    PASS)
      if [[ "$after" == "$before" ]]; then fails=$((fails + 1)); else fails=0; fi ;;
    *) fails=$((fails + 1)) ;;   # EXHAUSTED, STALLED, crash or unfinished
  esac

  if (( fails >= max_fail )); then
    echo "Stopping: $fails failed iterations in a row. Check gauntlet/LOG.md and $log." >&2
    exit 2
  fi
  if (( fails > 0 )); then
    wait=$((fails * 120))
    echo "Failed ($fails/$max_fail). Waiting ${wait}s before the next attempt..."
    sleep "$wait"
  fi
done
