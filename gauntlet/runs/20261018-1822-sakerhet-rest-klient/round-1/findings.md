# Round 1 — verdict: PASS

Counts: 0 blocker, 0 major, 11 minor. Rejected: 2. Duplicates: 1.

How I checked:
- I compiled the current `modules/client/src` against the breaker's built dependency jars (in my scratchpad) and reran the breaker's Probe2. It reproduced exactly.
- I read `RestRequest.addHeaderSecrets`/`addUserPass`, `Uris.hasDotSegment`/`isDotSegment`/`pathProblem`/`baseProblems`, `Text.hide`, `RestClientResponseException`, the `RestClient` Javadoc, the README secrets section, and the PROGRESS/ARCHITECTURE diffs.
- I grepped the diff for annotations and reflection and found none. `module-info.java` is unchanged.
- I did not run `java Build.java` in `framework/` (CLAUDE.md says not to). The auditor built an independent copy (447/447, all 14 modules OK, exit 0), which matches build.md.

## Upheld findings (the builder must address these)
None (no upheld BLOCKER or MAJOR).

## Rejected
- AUD-1 — Informational, not a defect. D1 (a segment counts up to its first `;`) adds to the K4 rule but contradicts no criterion: `/a..b`, `/...` and `/a.b` are still accepted. As spec "Antaganden" asks, it is motivated in build.md and in ARCHITECTURE.md ("Utöver uppdraget: fram till första `;`"), and the fixed text says so.
- AUD-3 — `post`/`put`/`patch`/`delete` say "@param path the path; see {@link #get(String)}" and "@throws ... see {@link #get(String)}". `get` spells out the dot-segment rule. A Javadoc link is a reasonable way for "Javadoc för sökvägsparametrarna" to say it, and it matches how the other path rules were already documented. The auditor also calls it a reasonable reading.

## Duplicates
- MNT-9 → AUD-2

## MINOR (optional for the builder)
- BRK-1 [MINOR, downgraded from MAJOR] RestRequest.java `addUserPass` — With the key as the Basic user-id (`sk_live_…:` or `key:x`), a 401 that names the user-id on its own shows it in getMessage()/safeExcerpt(). With a one-character password such as `x`, every `x` in the message is hidden.
  - Reproduced: `{"error":"API key sk_live_51KEYMARK has expired"}` and `...has e<hidden>pired`.
  - Why it is downgraded: spec K1 is an explicit, binding design rule. For Basic it lists exactly what to hide: the decoded `user:lösenord` and the password after the first `:`. The user-id is not on that list. The builder implemented that rule exactly, and the auditor confirmed it.
  - The docs promise no more than that: the README, the RestClient and RestClientResponseException Javadoc and PROGRESS name exactly those parts, and they say that a part of a value sent back on its own shows.
  - Making the user-id a secret would widen the contract (judge rule 7). It would also hide short user names such as `admin` or `api` everywhere in the message.
  - The over-hiding with `x` is the existing, documented short-value behaviour (it hides too much rather than too little).
  - fix_hint: Optional. Name this gap explicitly in PROGRESS "Kända begränsningar" and in the README/Javadoc sentence ("the Basic user-id on its own is not hidden; APIs that put the key there, such as Stripe-style `key:`, are not covered"). Hiding the user-id would be a deviation that needs a motivation.
- BRK-2 [MINOR] RestClient.java "Never turn on the JDK's logging" paragraph and README — `-Djavax.net.debug=all` (or `ssl:record:plaintext`) also dumps the plaintext request with `Authorization`. The claim is true, but the docs do not say the two named properties are the only ones. The paragraph is about the logging of `HttpClient`, and K3 names exactly those two properties.
  - fix_hint: Optional. Add `javax.net.debug` (`all`, `ssl:record:plaintext`) as one more example, or say "among others".
- AUD-2 [MINOR] RestClientResponseException.java `safeExcerpt()` — The Javadoc says "never null", but an exception serialized by an older version deserializes with `null` (`serialVersionUID` unchanged; only ARCHITECTURE admits it). Serialized exceptions crossing versions are rare in 0.x.
  - fix_hint: `return safeExcerpt == null ? "" : safeExcerpt;` (as `headers()` does), raise `serialVersionUID`, or add the caveat to the Javadoc.
- MNT-1 [MINOR] Uris.java `hasDotSegment` — The cached-`;` bookkeeping is correct and linear, but hard to follow, and the `dots < 3` guard has no comment. (I checked it: the re-find only happens when the cached `;` lies before `start`.)
  - fix_hint: A comment, or split the path into segments and test each one cut at its first `;`.
- MNT-2 [MINOR] The credential rule is spelled out in about ten places in Javadoc, README, PROGRESS and ARCHITECTURE.
  - fix_hint: Describe it once (the RestClientResponseException class Javadoc) and `{@link}` to it elsewhere.
- MNT-3 [MINOR] RestRequest.java — The "UTF-8 and, when it differs, ISO-8859-1" decoding is duplicated for the body and for Basic.
  - fix_hint: Extract one helper.
- MNT-4 [MINOR] Failures.java — `namesCredentials`/`NAMES_CREDENTIALS`/`namedCredentials` are near-identical names with different roles. The test server repeats the production parse `substring(indexOf(' ') + 1).strip()`.
  - fix_hint: Rename the three by role, and add a comment saying the server mirrors the parse on purpose.
- MNT-5 [MINOR] SecretTests.java — The kind names are repeated as string literals, and the echo switch has a catch-all `default`.
  - fix_hint: Use named constants, or table rows of (kind, expected markers).
- MNT-6 [MINOR] Failures.java:401 — The "bearer token on its own" kind relies on the SECRET setup's default header, while the other two build their own.
  - fix_hint: Send its own `Bearer <token>`, or add a comment.
- MNT-7 [MINOR] Uris.java — `path.substring(0, pathEnd)` is computed three times, and `DOT_SEGMENT`/`BASE_DOT_SEGMENT` repeat the same tail.
  - fix_hint: Use one local variable and a shared tail constant.
- MNT-8 [MINOR] ReadmeTests.java 15d tests — The tests pin prose, the `log.warn(` heuristic is loose, and the K3 `||` alternatives accept any backticked `all`.
  - fix_hint: Pin the property names exactly, and rely on the compiled-source comparison for the examples.
