VERDICT: PASS
FINDINGS: none

I couldn't find a real defect in round 2. The fix for BRK-1 works as documented over a real server, and the verification command passes.

- **Build:** I copied `framework/` to `round-2/scratch-breaker/fw` and ran `java Build.java` there. It ended with exit 0 and `BUILD OK in 84740 ms`. My first run in the copy went red, but only because I had written `build.log` inside the copy, which the guardrails rightly flag. The rerun with the log outside the copy was green.
- **The BRK-1 fix over HTTP:** `P2.java` runs `GET /items` (Responses.json) against a real `HttpServer` on port 0:
  - `application/json; charset=utf-8`, `charset=UTF-8`, `charset="utf-8"` → 200
  - `*/*;charset=utf-8`, `application/*;charset=UTF-8` → 200
  - `application/json;charset=utf-8, application/json;q=0` → 200 (the more specific range wins)
  - `application/json;charset=iso-8859-1` → 406
  - `application/json;charset=utf-8;q=0, */*` → 406
  - `application/problem+json;charset=utf-8` → 406
  - `text/plain;charset=utf-8` → 406 (documented limitation)
  - `charset=utf8` (no hyphen) → 406 (non-standard name, not a defect)
- **Code review:** `MediaType.includes` (155-164) only skips the check when the offered type has no `charset`, the range's charset is `utf-8`, and the offered type is JSON. Specificity is unchanged. `includes` is only called from `AcceptHeader.quality`, so the `readJson` Content-Type check is unaffected.
- **The `JsonBody` refactor:** the shared `single(...)` helper keeps the old behaviour; only the detail text "send it once" changed. `MAX_LISTED` replaced by `Problem.MAX_ERRORS` (same value, 100).
- **Docs:** README negotiation paragraph and the deviation rows (charset in Accept, legacy HttpURLConnection header) are present. PROGRESS.md lists the known limitations and 455 tests.
