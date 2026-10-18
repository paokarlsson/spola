# Report: 20261013-2223-security-csrf

- Status: PASS
- Title: Sessions part 3: CSRF protection with XSRF-TOKEN (spola.security.web)
- Mission: uppdrag/11c-security-csrf.md (last slice of psykologen's need inloggning-sessioner-csrf)
- Rounds: 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 11 minor reported) | green | b00240b |

## Built
`Csrf.cookie()` / `withCookie(...)` and `WebSecurity.Builder.csrf(Csrf)`: double-submit with a
readable `XSRF-TOKEN` cookie (256-bit, no server state) and the `X-XSRF-TOKEN` header, constant-time
comparison, 403 before authentication for any method except GET/HEAD/OPTIONS/TRACE, cookie on the first
response, new token on login and logout. README "CSRF-skydd för en SPA", ARCHITECTURE, PROGRESS, MONSTER,
root README. The need's whole flow (GET → 403 → login → me → logout → 401) is a test.

## Remaining findings (MINOR)
See round-1/findings.md: a late logout removes the session before throwing, SourceRulesTests count change,
duplicated cookie-template logic between Csrf and Sessions, repeated contract text, test-helper duplication,
and a ~1/1000 chance flaky LeakTests marker from 11b's unmasked ids.

## Notes
- The judge was skipped because all three critics passed with no BLOCKER/MAJOR.
- The need 2026-10-13-inloggning-sessioner-csrf is delivered in full (11a–11c).
- Stash: –
