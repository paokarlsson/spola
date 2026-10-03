# Report: 20261003-1102-web-cookies-och-formular

- **Status:** PASS
- **Title:** Web part 3: cookies, Set-Cookie and form bodies (spola.web)
- **Mission source:** uppdrag/08c-web-cookies-och-formular.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 6 minor) | green | 6ec2828 |

## What was built
- Incoming cookies: `WebRequest.cookie`, `cookieAll`, `cookie(name, Param)` and `cookies()`. Parsing is lazy and runs at most once per request, shared across derived requests. Malformed pairs are skipped, and more than 200 cookies give 400.
- `ResponseCookie` with `SameSite`. Defaults are `Path=/`, `HttpOnly` and `SameSite=Lax`. It validates the name, value, path, domain and `maxAge`, plus the `__Secure-`/`__Host-` prefix rules, and writes `Max-Age` only. `Responses.withCookie` adds each cookie as a separate `Set-Cookie` field.
- `readForm`/`readForm(int)`, `FormData` and `RouterBuilder.maxFormSize` (2 MiB by default). Checks run in the order 415 (encoding, content type), 413 (streamed limit), then 400 (non-ASCII, decoding, pair limit). An empty body is an empty form. The body is read once, shared with `readJson` through `BodyState`.
- README example (`ProfileApp`, a compiled test source compared with the README), new deviation rows, and updates to ARCHITECTURE.md and PROGRESS.md. Row 08 is done.

## Fixed
Nothing. The round passed on the first attempt.

## Remaining findings (MINOR)
- MNT-1/MNT-2: `ResponseCookie.Builder` repeats a nine-argument constructor in every setter, and `build()` makes a Builder only to pass it on.
- MNT-3: `FormBody` reuses package-private helpers that live in `JsonBody`. A neutral helper class would be cleaner.
- MNT-4: `FormData.equals` checks key order in a roundabout way.
- MNT-5: the `CookieState` parse counter is a test seam in production code (same pattern as `QueryState`).
- AUD-1: the JDK `CookieManager` round trip only works for session cookies, because `Max-Age`-only cookies come back as RFC 2965 with quoted values. This follows from the binding decisions, and it is documented and pinned by a test.

## Assumptions
- The judge was skipped because all three critics returned PASS with no BLOCKER or MAJOR (SKILL.md §3b).
- The breaker's scratch code under `round-1/scratch-breaker/` is kept as part of the run record.

## Stash
None.
