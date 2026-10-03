# Round 1 findings

Verdict: PASS (0 blocker, 0 major, 6 minor upheld)

All three critics returned PASS with no BLOCKER or MAJOR findings, so the orchestrator skipped
the judge (per SKILL.md §3b) and records the verdict directly. The MINOR findings are carried
over as-is, not verified by a judge.

## MINOR (carried over)
- MNT-1: `ResponseCookie.Builder` repeats a nine-argument constructor in every setter; positional booleans are easy to swap.
- MNT-2: `build()` creates a Builder only to pass it to a constructor that re-reads its fields.
- MNT-3: `FormBody` depends on package-private helpers in `JsonBody` (`checkEncoding`, `single`, `LimitedInput`); could move to a neutral helper class.
- MNT-4: `FormData.equals` checks key order in a roundabout way; intent undocumented.
- MNT-5: `CookieState.parses` counter is a test seam in production code (same pattern as `QueryState`).
- AUD-1: The `CookieManager` round trip only works for a session cookie, since the JDK `CookieManager` treats `Max-Age`-only cookies as RFC 2965 and sends back quoted values. This follows from the binding decisions and is documented and pinned by a test.
