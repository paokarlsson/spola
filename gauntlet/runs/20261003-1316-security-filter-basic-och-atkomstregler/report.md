# Report: 20261003-1316-security-filter-basic-och-atkomstregler

**Status:** PASS
**Title:** Säkerhet del 2: säkerhetsfilter, HTTP Basic och åtkomstregler (`spola.security.web`, mission 10b)
**Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 7 minor; all critics PASS, judge skipped) | green | 39729fd |

The loop was stopped by hand (Ctrl+C) during step 3d. Verification and the round commit were
then run manually following the orchestrator's steps. `verify.log` holds the build output.

## Delivered
New module `spola.security.web` (requires `spola.web` and `spola.security`):
- `WebSecurity`: one filter that runs the authentication mechanisms in order and puts a
  `SecurityContext` on the request. It applies the first matching access rule and denies when
  no rule matches.
- `HttpBasic`: strict RFC 7617 parsing.
- `RequestMatcher` (`under`, `exactly`, `method`, `anyRequest`) and `AccessRule` (`permitAll`,
  `denyAll`, `authenticated`, `hasRole`, `hasAnyRole`).
- 46 tests and a README next to Spring Security. `RouterBuilder.log(System.Logger)` in
  `spola.web` is now public.

## Remaining MINOR
- **BRK-1, review before use; arguably MAJOR in security code:** `RequestMatcher.method`
  accepts lowercase method names but compares case-sensitively. `method("get", ...)` then
  never matches, so a rule can silently not apply and the request falls through to a later
  `permitAll` (fail-open). The critics graded it MINOR and no judge ran.
- MNT-1: the RFC 9110 token helpers live in `Matchers` but are used by `HttpBasic`.
- MNT-2: role limits and character rules are copied from `spola.security` and can drift.
- MNT-3: `requireIdentity` uses a hidden `OWNER` attribute and a private `WebException` subclass.
- MNT-4 / AUD-1: `RouterBuilder.log` was made public for test log capture.
- MNT-5: the duplicate-`Authorization` check exists in two call sites.

## Stash
None.
