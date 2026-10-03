# Round 2 — findings

VERDICT: PASS

Judge skipped: all three critics gave PASS with no BLOCKER or MAJOR (orchestrator rule 3b).

Counts: 0 blocker, 0 major, 6 minor (not verified by a judge)

## MINOR (remaining, optional)
- **BRK-1:** over TLS, a client that simply stalls sometimes loses its 408 (about 1–3% of runs at 100 ms timeouts). The guard and soTimeout fire at the same instant. Fix: give the guard some slack (deadline + margin).
- **AUD-1:** over TLS, a cut at `headerTimeout` gives no 408 (documented in README, ARCHITECTURE and PROGRESS; spec-ambiguity).
- **MNT-1:** the `| 1` sentinel is still used for `readDeadline` in ConnectionInput, which is inconsistent with the `handshaking` flag.
- **MNT-2:** the timeout and deadline calculation in `ConnectionInput.fill()` assigns its variables twice.
- **MNT-3:** a comment is missing at the `sendError(408)` branch saying the write does nothing over TLS once the guard has cut the socket.
- **MNT-4 (round 1):** the plain/TLS branching in `Connection` has not been pulled out into a helper.
