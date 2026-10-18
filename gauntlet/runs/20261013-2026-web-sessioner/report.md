# Report: 20261013-2026-web-sessioner

- Status: PASS
- Title: Sessions part 1: in-memory HTTP sessions with a session cookie
- Mission: uppdrag/11a-web-sessioner.md (from the need uppdrag/behov/klara/2026-10-13-inloggning-sessioner-csrf.md, psykologen)
- Rounds: 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 12 minor reported) | green | e7acba8 |

## Built
`Sessions`, `Session` and `SessionOptions` in `spola.web`: 256-bit ids from `SecureRandom`, lookup by
SHA-256, typed attributes, idle expiry with cleanup on requests (no background thread), `maxSessions`
with 503 instead of eviction, `renew` (session fixation) and `invalidate`, `Cache-Control: no-store`
when the cookie is set. README section "Sessioner", ARCHITECTURE, PROGRESS, MONSTER and root README updated.

## Remaining findings (MINOR)
See round-1/findings.md: in-flight old-id requests lose their view after `renew` (documented choice),
an overstated "memory dump" sentence, 400 on >200 cookies behind the filter (untested), test-order
dependence in SessionLeakTests, and maintainability notes (split out a SessionStore, query-named
methods with side effects, package-private mutable field, Javadoc gaps).

## Notes
- The judge was skipped because all three critics passed with no BLOCKER/MAJOR.
- The root README.md module table was edited because criterion 16 asks for it.
- Slices 11b (login with session) and 11c (CSRF) remain in uppdrag/.
- Stash: –
