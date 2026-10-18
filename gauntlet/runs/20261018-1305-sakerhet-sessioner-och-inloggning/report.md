# Report: 20261018-1305-sakerhet-sessioner-och-inloggning

- Status: PASS
- Title: Security review part 1: per-user session limit and cache headers
- Mission: uppdrag/15a-sakerhet-sessioner-och-inloggning.md (security review 2026-10-18, G1–G5)
- Rounds: 2 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 1 major, 9 minor) | green | e392fec |
| 2 | PASS (0 blocker, 0 major, 11 minor reported) | green | b4d95f7 |

## Built
`WebSecurity.Builder.maximumSessions(n)` (default 10, LRU session ended at login) via `Sessions.renewFor`;
a fresh session when another user logs in; no shared-cache storage of session/XSRF-cookie responses and
`no-store` on logged-in responses; one cleanup at a time at the limit.

## Fixed in round 2
- BRK-1 (MAJOR): a login racing `Sessions.renew` left a logged-in session outside the index (G1/G2 escape).
- MNT-1…MNT-8 from round 1 (parser copy tested against the same table, top-level CacheControl, names, lock order).

## Remaining findings (MINOR)
See round-2/findings.md: wording about the brief excess during concurrent logins, Javadoc precision, names
and test-helper structure.

## Notes
- Round 2's judge was skipped because all critics passed with no BLOCKER/MAJOR.
- Stash: –
