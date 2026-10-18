# Round 1: build

## Changes
Everything is in `framework/modules/client/` plus `framework/PROGRESS.md` and `framework/ARCHITECTURE.md`. `module-info.java` and `ModuleTests` are unchanged, and there are no new files, no reflection and no annotations.

**Main code**
- `RestRequest.java`, K1. `secrets()` now calls `addHeaderSecrets` for every default header and request header. For `Authorization` and `Proxy-Authorization` (name in any case) it also adds the text after the first space, stripped. For scheme `Basic` (any case) it also adds the Base64-decoded `user:password` and the password after the first `:`. Both are decoded as UTF-8, and also as ISO-8859-1 when that differs. If Base64 decoding fails, nothing extra is added. `Text` and `Causes` are unchanged and read the same list. The class Javadoc and `header` Javadoc mention this, plus K4.
- `RestClientResponseException.java`, K2/K5. New `safeExcerpt()`: a stored field holding exactly the hidden excerpt that `of()` already computed for the message. `bodyExcerpt()` is documented as raw ("do not log it"). The class Javadoc describes the Authorization credentials, gives a log example with `safeExcerpt()`, and has the corrected spola.json limit.
- `RestClient.java`, K1/K3/K4/K5 Javadoc:
  - "Secrets" section: credentials, `safeExcerpt`, the corrected 40/100/200 text, and a new paragraph "Never turn on the JDK's logging…" naming `jdk.httpclient.HttpClient.log` (`headers`, `requests`, `all`) and `jdk.internal.httpclient.debug`.
  - Dot segments are covered in the URI bullet, the `get` `@param`/`@throws`, `Builder.baseUri`, `build()` `@throws` and the deviations list.
- `RestClientException.java`: the switch example uses `r.safeExcerpt()`, and the case list and secrets list are updated. `RestClientTransportException.java`: one sentence about credentials. `Text.java`: class doc mentions `safeExcerpt`.
- `Uris.java`, K4:
  - `hasDotSegment` / `isDotSegment` refuse a segment of one or two dots, where each dot is `.`, `%2e` or `%2E`. A segment counts up to its first `;` (see deviation D1 below).
  - It is checked in `pathProblem` (after the character check) and in `baseProblems`, with the fixed texts `DOT_SEGMENT` / `BASE_DOT_SEGMENT`, which quote nothing.
  - The scan is linear.

**Tests**
- `Failures.java`: three new kinds in `all()`:
  - "a 401 that names the bearer token on its own" uses SECRET's default `Bearer` header.
  - "…after a lower-case bearer" and "…the decoded user:password from Basic" send their own `Authorization` and drop the default one. Otherwise the default's credentials would hide the marker and the tests could not catch a broken lower-case or Basic path.
  - Helpers added: `namesCredentials(name)`, `namedCredentials`, `Setup.token()`, `Setup.withoutAuthorization()`, `headerValueOrNull`, `base64`.
- `SecretTests.java`:
  - The new kinds are in `RESPONSE_ECHOES` (marker `AUTH_MARKER`), and the echo test also checks `safeExcerpt()`.
  - Exact-message test for the three 401s.
  - Table `secretRows()` of 16 headers checked against `secrets()`, both as a default header and as a request header.
  - A non-Authorization header (`X-Auth: Bearer …`) is still hidden only whole.
  - `safeExcerpt()` hides an echoed header while `bodyExcerpt()` is raw.
  - `safeExcerpt()` for every response failure has no marker and is the excerpt shown in the message.
- `AnthropicTests.java`: the need's log line against an Anthropic that names the key shows `<hidden>`, and `bodyExcerpt()` has the key.
- `SetupTests.java`, K4 tables:
  - Refused at all five methods with the exact fixed text: the mission's paths, plus more and the `URLEncoder` case.
  - Accepted and sent as they stand: `/a..b`, `/...`, `/a.b` and more.
  - Base URIs, both refused and accepted.
  - Combined problems.
  - `/x/../y` in the "valid paths" test became `/x/.../y`.
- `RequestTests.java`: `/a/../b` "sent as it stands" became `/a..b/.../c`, since `/a/../b` is now refused on purpose.
- `SwitchTests.java`, `AiClients.java` (the README source): log or read `safeExcerpt()`. The comment no longer says "aldrig nyckeln".
- `ReadmeTests.java`: three new tests for K2 (no raw excerpt logged in README or Javadoc, no "aldrig nyckeln"), K3 (property names in README and Javadoc) and K5 (the new text present, the old one gone).

**Docs**
- `modules/client/README.md`:
  - Intro and comparison-table row (prefix kept).
  - The AiClients block is synced.
  - The switch uses `safeExcerpt()`, with a sentence on why.
  - Rewritten "Loggraden" and "Hela undantaget" bullets (K1, K2, K5) and a new K3 bullet.
  - Limits table: new `safeExcerpt()` row, `getMessage()` row updated.
  - `build()` paragraph and the deviation rows "Sökvägen", "Felkroppen", "Felmeddelandet", "Loggning" and the hierarchy row.
- `PROGRESS.md`:
  - New section "Uppdrag 15d".
  - "15d återstår" changed to done in 15a/15b/15c and "Återstår".
  - Known problems: dot-segment gaps, credentials, tab separator, `safeExcerpt`, JDK logging, K5 text.
- `ARCHITECTURE.md`: the REST-client section is updated (API table, URI, K5 text). New section "Säkerhetsgranskning del 4" with the decisions and the deviation.

## Verification
- `cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java` (final state, after the last edit):
  ```
  guardrails: no violations
  spola.client: OK - 447 tests, 447 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 13356 ms
  BUILD OK in 266093 ms
  exit 0
  ```
  All 14 modules OK (guardrails 17, test 77, di 48, config 68, util 80, codegen 41, json 417, http 290, validation 251, web 1156, security 346, security.web 287, client 447, demo 43).
- Baseline before the changes: 330/330 in `spola.client`, so 117 new test cases.
- Mutation runs against the final code. These used a scratch harness that compiles `modules/client` against copies of the built jars and runs `ClientTests`. Every mutation was caught:
  - no credential hiding: 22 failed
  - no Base64 decoding: 9 failed
  - no dot-segment check: 36 failed
  - no `;` boundary: 6 failed
- The reviewer's probes, run against the new classes:
  - P1 cases 1–3 (Bearer alone, lower-case bearer, decoded Basic) now show `Invalid token: <hidden>`, `bearer <hidden>` and `invalid credentials for <hidden>`, with the marker in neither the message, `toString()` nor the stack trace.
  - P1 cases 4 (query value alone, out of scope) and 5 (key JSON-escaped as `\/`, the documented "changed first" limitation) still show, as expected.
  - P3 (`/users/../..`) now throws `IllegalArgumentException: the path has a segment that is . or .. (also as %2e or %2E, or with ;parameters), which is not allowed: a server resolves it to another path`.
- K3 claims checked with a JDK 25 probe: plain `HttpClient`, a local server, `Authorization: Bearer LOGMARK` and `?token=QUERYMARK`.

  | Setting | Header value printed | Query printed |
  |---|---|---|
  | `log=headers` | yes | yes |
  | `log=all` | yes | yes |
  | `log=requests` | no | yes |
  | `jdk.internal.httpclient.debug=true` | no | yes |
  | `errors`, `content`, `trace` | no | no |

  The Javadoc and README say exactly this: header values with `headers`/`all`, the query with all four.

## Addressed
Round 1, so there are no findings yet. The spec criteria map as follows:
1. Build exit 0 (above).
2. Only `modules/client` and docs changed (`git status`). `module-info.java` and `ModuleTests` are unchanged, with no reflection and no new file types.
3. K1: three new `Failures.all()` kinds. They run in "no marker in getMessage, toString or the printed stack trace of any failure", which also checks `getLocalizedMessage`, and in the `SecretTests` echo test. A non-Authorization header is treated as before (test plus table rows).
4. K2: `safeExcerpt()` plus tests. README/Javadoc examples log it. "aldrig nyckeln" is gone, and `ReadmeTests` checks this.
5. K3: Javadoc and README warning with the property names; `ReadmeTests` checks it.
6. K4: table-driven tests in `SetupTests` with the exact paths from the criterion.
7. K5: README, `RestClient` and `RestClientResponseException` Javadoc, PROGRESS and ARCHITECTURE; `ReadmeTests` checks it.
8. Guardrails show no violations, and no rule in REGLER.md is broken.
9. PROGRESS.md and ARCHITECTURE.md are updated.

## Deviations (motivated in ARCHITECTURE.md, "Säkerhetsgranskning del 4")
- **D1, a segment counts up to its first `;`.** `/..;/x`, `/a/.;jsessionid=1/b` and `/a/%2e%2e;p=1` are refused too. Tomcat and Jetty strip path parameters before they resolve dot segments (the known `..;/` bypass), and RFC 3986 segment encoders such as Spring's `UriUtils.encodePathSegment` keep both `.` and `;`. This adds to the mission's rule and does not contradict any criterion: `/a..b`, `/...` and `/a.b` are still accepted, and so are `/a;../b`, `/a..;b` and `/...;x`. Because of this the fixed text says "or with ;parameters".
- **D2, Basic is decoded as both UTF-8 and ISO-8859-1** when the two differ, the same way the body is already handled. RFC 7617 leaves the charset open, so "det avkodade user:lösenord" depends on how the server decodes it.

## Rebuttals
None (round 1).

## Known gaps
- K4: these are not refused because RFC 3986 does not count them as dot segments: an encoded slash (`..%2F`, which `URLEncoder.encode("../x")` produces), double encoding (`%252e`), an encoded `;` (`..%3B`) and an encoded backslash (`..%5C`). A server that decodes these before it resolves dot segments can still be steered. This is noted in PROGRESS and ARCHITECTURE.
- K1: the scheme separator is a space only, per the spec and RFC 9110. `Bearer<tab>key` is hidden only whole, which is noted in PROGRESS.
- K1: partial or transformed echoes still show. This was already documented. Examples: one query parameter alone (out of scope), a key JSON-escaped as `\/`, and one parameter of `Digest`.
- A very short Basic password hides the same text everywhere in the message. This is the existing short-value behaviour: it errs toward hiding too much.
- `safeExcerpt` adds a serialized field and `serialVersionUID` is unchanged. An old serialized exception would deserialize with `null`. That is acceptable for 0.x and noted in ARCHITECTURE.
