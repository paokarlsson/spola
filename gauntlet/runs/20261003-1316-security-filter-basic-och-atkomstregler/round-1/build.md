# Round 1: build

## Changes
- `framework/modules/security-web/src/module-info.java`: new module `spola.security.web` (exactly `requires transitive spola.web`, `requires transitive spola.security`, `requires static java.net.http`; exports `spola.security.web`). Javadoc covers the module, its dependencies and the rule that the filter is registered first.
- `framework/modules/security-web/src/spola/security/web/`:
  - `WebSecurity` (+ `Builder`): the central filter (400 for more than one `Authorization` field, mechanisms in order, `Rejected` gives 401 at once, `SecurityContext` attribute, first matching rule, deny when no rule matches), `require(rule)`, static `identity`/`requireIdentity` (ISE without the filter; `requireIdentity` throws a `WebException` 401 whose response is exactly the filter's 401), 401/403 responses built once in the constructor, `build()` with one line per rule after `anyRequest()`. A second run of the filter on the same request throws ISE, so the Authenticator is never called twice.
  - `RequestAuthentication`, `AuthOutcome` (`None()`, `Success(Identity)`, `Rejected()`), `SecurityContext(Optional<Identity>)`.
  - `HttpBasic`: RFC 7617 parsing as the mission specifies (scheme case-insensitive, 1+ SP, token at most 8 192 chars checked before decoding, length % 4, strict decoder, canonical re-encode, UTF-8 with REPORT into a caller-owned `char[]`, split at the first `:`, no control characters, no empty username). The password goes to the Authenticator as a read-only `CharBuffer`. Bytes and chars are zeroed in `finally`. The realm is checked in `of`.
  - `RequestMatcher` (`under`, `exactly`, `method` where GET also matches HEAD, `anyRequest`) with `Matchers` (impl), and `AccessRule` (`permitAll`, `denyAll`, `authenticated`, `hasRole`, `hasAnyRole`) with `Rules` (impl, role check same as `Identity`). `Text` escapes and shortens values in messages.
- `framework/modules/security-web/test/spola/security/web/`: 13 test files plus `SecurityExample.java` (the README example), 46 tests in total. See Verification.
- `framework/modules/security-web/README.md`: summary, the Spring Security example next to Spola (the Spola block is `SecurityExample.java` verbatim, checked by a test), the comparison table and the deviation table (all six required rows plus a few more).
- `framework/Build.java`: only the new `MODULES` entry.
- `framework/modules/web/src/spola/web/RouterBuilder.java`: `log(System.Logger)` was package-private and is now public with Javadoc. Nothing else changed and behaviour is unchanged. Why: criterion 11 requires the router's log lines to be captured, but this module's tests sit in another package and cannot read `java.logging` because the requires are fixed. The reason is in ARCHITECTURE.md ("Säkerhet del 2", second decision) and in the web section line that used to say "paketprivat".
- `framework/ARCHITECTURE.md`: new section "Säkerhet del 2" covering every bullet from criterion 14, plus a catalogue line and the `log` note.
- `framework/PROGRESS.md`: 10b section (row 10 is done, form login/logout moved to 11, human review), "Återstår" updated, and a known-limitations entry (PBKDF2 per Basic request, no rate limiting, no security headers, no audit logging, and more).

## Verification
`cd framework && java Build.java` gives exit 0:
```
spola.web: OK - 730 tests, 730 ok, 0 failed, ...
spola.security: OK - 103 tests, 103 ok, 0 failed, ...
spola.security.web: OK - 46 tests, 46 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 780 ms
spola.demo: OK - 43 tests, 43 ok, ...
guardrails: source       414 .java files
guardrails: source       14 other files: 0 not allowed, 14 documents (0 parse as Java)
guardrails: no violations
BUILD OK in 98684 ms
```
All other module suites are green too (guardrails 17, test 77, di 48, config 68, util 80, codegen 41, json 417, http 283, validation 251).

How the tests map to the criteria:
- C2 `SourceRulesTests`: requires list pinned in the source and the descriptor. No reflect/invoke/Logger/ThreadLocal/ScopedValue in main code. The Build.java entry.
- C3 `BasicTests`: raw socket and `HttpClient`. Exact 401 (content type, exact `WWW-Authenticate`, `no-store`, body), byte-for-byte equal 401s (minus `Date`) for wrong password, unknown user, disabled account and anonymous, also on permitAll. 403 without challenge. Unmatched paths give 401/403. Realm validation. Zero or two challenges.
- C4 `MalformedTests`: 32 malformed values, each byte-equal to the wrong-password 401, with 0 extra Authenticator calls. The exact 8 192 boundary. The RFC examples decode to the recorded username and password. `basic`/`BASIC`/`bAsIc`, several spaces, a password with `:`, non-ASCII with a surrogate pair. Bearer/Digest/Negotiate/`Basicx` give None. Two fields give 400 (also with `HttpBasic` used alone).
- C5 `CountingTests`: exactly one call per credentialed request across 3 paths × 6 credentials, and none for anonymous GET/HEAD/OPTIONS. A throwing store gives 500 with no handler call, also on permitAll.
- C6 `MatcherTests`: a 22-path table (including `/%61dmin`, `/%41dmin` and paths the router rejects with 400), invalid prefixes, method and HEAD, and the server scenario with GET and HEAD giving 401.
- C7 `RuleTests`: first matching rule wins (both orders), two-line `build()` error, roles agree with `Identity`, matcher/rule/mechanism exception or `null` gives 500 with no handler call, lambda rule.
- C8 `RequireTests`: group require gives 401 (byte-equal to the central 401), 403 and 200. `requireIdentity` 401, including through a WebException mapper. Without the filter all three give 500 with the ISE in the log. A double filter gives 500.
- C9 `RouterTests`: 404/405/OPTIONS/`OPTIONS *` under protected or no rule give 401, and the router's answer with the role. 8 paths rejected with 400 never reach a handler (401/403 without fallback, 400 with `anyRequest().permitAll()`). Inner filters do not run for denied requests.
- C10 `ConcurrencyTests`: 200 virtual threads behind a latch, 13 request kinds (4 users, roles, wrong, disabled, unknown, malformed, anonymous). The handler answers a code for the identity it saw.
- C11 `LeakTests` (runs last): over 300 recorded replies and all router log lines are checked against the markers `zUsr`/`zPwd`, the RFC example strings and every sent Authorization value and token. Control characters from the query and path reach the log only escaped.
- C12 `ReadmeTests`. C13: guardrails are green and only `.java`/`.md` files were added.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- Spec deviations that I chose, which make the module stricter:
  - `under`/`exactly` also reject `\` in a prefix (it can never match, since the router rejects a decoded `\`).
  - `hasAnyRole()` with no roles is an IAE.
  - The scheme is read as the leading token characters, so `Basic\t...` and `Basic:...` give `Rejected` rather than `None`. This is fail-closed.
  - The 401/403 bodies carry no `instance`, so they are identical for every path.
- `requireIdentity` finds the 401 response through a second private attribute (`OWNER`) next to the `SecurityContext`.
- `build()` recognises "everything" only by the `anyRequest()` instance. A lambda that matches everything, followed by more rules, is not detected (documented).
- The filter cannot verify that it is registered first (documented in Javadoc, README and PROGRESS).
- A user store's exception message is logged by the router (documented: the store must not put secrets in it).
- Test output shows `spola.http` INFO lines on stderr (server start/stop), as in other modules' server tests.
