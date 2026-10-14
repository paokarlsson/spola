# Round 1 — verdict: FAIL

Basis: the build is green (builder; full builds of an exact copy by the breaker and the auditor:
`guardrails: no violations`, `spola.client: OK - 295 tests`, `BUILD OK`, exit 0). The
`Build.java` diff is limited to the `MODULES` line and the test layer, no other module's `src/` or
`test/` changed, and the only annotations are `@Override`, `@Serial` and `@FunctionalInterface`
(the Spring annotations occur only inside string literals in `ReadmeTests`). No REGLER violation
found. The judge re-ran the breaker's probes 5, 7, 8 and 9 against a build of the current source
(the breaker's copy is byte-identical to `framework/modules/client` and `framework/Build.java`);
all four reproduce.

## Upheld findings (the builder must address these)

- BRK-1 [MAJOR] `modules/client/src/spola/client/Causes.java:47-76` (`isClean`),
  `RestClient.java:412-424` (`secrets`), `RestClient.java:435-447` (`transportFailure`) — The
  scrubbing of transport causes misses a request body with non-ASCII text. The JDK decodes the
  status line as ISO-8859-1, so the UTF-8 body string never matches; the JDK text then goes into
  `getMessage()` and the `ProtocolException` stays as the cause, body included. Judge's re-run
  (probe5), body `{"prompt":"Hej, hur mår du? QBODYMARKERQ"}`:
  `... the response is not valid HTTP/1.1 (java.net.ProtocolException: Invalid status line: "{"prompt":"Hej, hur mÃ¥r du? QBODYMARKERQ"}")`,
  marker in `getMessage()` and in the printed stack trace; the same body in ASCII gets the
  stand-in. This is the builder's own ECHOES_BODY scenario with the need's own Swedish text, and it
  breaks the decision "ingen förfrågningskropp finns i något meddelande ... eller någon orsak som
  modulen skapar" and the promise in the Javadoc of `RestClient` ("Secrets") and
  `RestClientTransportException`. Secret handling against data from outside: a crafted response
  is realistic input here (judge rule 6), so MAJOR stands. — fix_hint: Preferred, robust: never
  copy the text of a failure that can quote what the server sent (at least `ProtocolException`
  and the other response-parsing `IOException`s) into the message or keep it as the cause; keep
  only the class, as the stand-in already does. That also closes the partial-echo gap (MNT-7).
  Acceptable, narrower: also match the body in the form the JDK quotes it
  (`new String(body, ISO_8859_1)`), and then state the remaining limit (whole values only) in the
  Javadoc. Either way, add a non-ASCII body to the ECHOES_BODY case in the secret tests.

- BRK-2 [MAJOR] `RestClientResponseException.java:44-51` (message
  `"status N: what: " + bodyExcerpt`), `RestClient.java:322` and `:330`,
  `RestRequest.java:187-190` — `getMessage()`, `toString()` and the printed stack trace of a
  `RestClientResponseException` contain header values and the request body when an ordinary
  server echoes them. Judge's re-run (probe5): a FastAPI-style 422 that echoes the input (FastAPI's
  default validation response does) puts the body marker in `getMessage()` and the printed trace;
  an httpbin-style 200 that echoes the headers, with a codec that does not fit, gives
  `"Authorization":"Bearer sk-ant-KEYMARK-123"` in `getMessage()`. This contradicts the binding
  decision "Inga huvudvärden och ingen förfrågningskropp finns i något meddelande" and the absolute
  claims in `RestClient` ("Secrets"), `RestClientException` and PROGRESS. A developer who writes
  `log.warn("...", e)` puts the API key or the user's conversation in the log. The same decision
  list requires "början av utdraget" in the message, so the excerpt stays in the message; but the
  module already holds the request's values (`RestClient.secrets`) and applies them only to
  transport causes. — fix_hint: Keep `bodyExcerpt()` raw (its Javadoc already warns that the
  server may quote the request). In the message, replace every whole occurrence of a request
  value (header values, the query, the body) in the excerpt part with a placeholder, before the
  ASCII escaping (after `\uXXXX` escaping a non-ASCII body no longer matches). Header values and
  the query are then never shown. Whole-value matching cannot catch a server that echoes only part
  of the body, or a body longer than the excerpt: narrow the claims in `RestClient`,
  `RestClientException`, PROGRESS and ARCHITECTURE to what then holds. Test with echo servers (a
  4xx echoing the body, a 2xx echoing the headers with a codec mismatch): no marker in
  `getMessage()`, `toString()` or the printed trace; the markers are still in `bodyExcerpt()`.

- BRK-3 [MAJOR] `modules/client/src/spola/client/BodyReader.java:82-92` (`contentLength`,
  `value.length() > 18` gives -1) — A `Content-Length` of 19 digits, which the JDK accepts up to
  `Long.MAX_VALUE`, counts as unknown. The client then waits for the body until the timeout and
  throws `RestClientTransportException` with `timedOut()`, not `RestClientResponseException` with
  the status. Judge's re-run (probe8, timeout 5 s): `999999999999999999` (18 digits) gives a
  `RestClientResponseException` with status 200 in 9 ms; `1000000000000000000` (19 digits) gives
  `RestClientTransportException: ... timed out after 5000 ms` after 5025 ms (30 s with the
  default timeout). The binding decision says "Ett Content-Length över gränsen ger fel utan att
  kroppen läses ... Felet är ett RestClientResponseException med statusen." A response header is
  data from outside, so a crafted value counts (judge rule 6). The wait is bounded by the timeout,
  but the decision is broken and the fix is one line. — fix_hint: Treat an all-digit value longer
  than 18 digits as over the limit (for example `declared = Long.MAX_VALUE`), or parse it and map
  `NumberFormatException` to `Long.MAX_VALUE`. Add `1000000000000000000` and
  `9223372036854775807` to `LimitTests`.

## Rejected
- AUD-1 — Follows the binding decisions "maxResponseSize gäller kroppen i alla avslutande anrop"
  and "Ett Content-Length över gränsen ger fel utan att kroppen läses". The behaviour is
  documented (`Builder.maxResponseSize`, and `bodyExcerpt()`: "one that was not read ... gives an
  empty string") and tested. Not a defect.
- AUD-4 — Not a defect of this deliverable. Criterion 14 is met. The stall reproduces without
  `spola.client` (builder, auditor), the breaker's probe4 ties it to `spola.http`, and it is
  disclosed in PROGRESS. Changing `spola.http` is outside this mission (criterion 2). The
  test-side note is MNT-6.

## Duplicates
- AUD-2 → BRK-4

## MINOR (optional for the builder)
- BRK-4 — `Text.excerpt` with the byte cut in `BodyReader`: when a body is cut at a byte limit
  and decodes to 1 000 chars or fewer, the excerpt has no `...`, and it can end with a U+FFFD
  from a split character. Re-run confirmed (maxResponseSize 1024, "ö" body: 513 chars, no `...`,
  last char U+FFFD). It needs a `maxResponseSize` below about 3 KB (the default 8 KiB error read
  always decodes to more than 1 000 chars). The decision ties `...` to the 1 000-char cut and
  maps an incomplete final sequence to U+FFFD, so the contract is not broken; cosmetic. Hint: pass
  the `Ending` to `Text.excerpt`; on a cut, drop a trailing incomplete UTF-8 sequence and append
  `...`.
- BRK-5 — U+061C (ARABIC LETTER MARK) is not escaped. Re-run confirmed. The builder followed the
  spec's explicit list, and `bodyExcerpt()`'s Javadoc lists exactly those characters. The class
  Javadoc of `RestClientResponseException` ("without ... bidi controls") and README line 233
  ("inga ... bidi-tecken") claim more. Add U+061C to `mustEscape`, or narrow the wording.
- AUD-3 — README deviation table, redirect row: it calls Spring's default factory
  `HttpURLConnection`. Since Spring Framework 6.1, `RestClient.builder()` prefers the JDK
  `HttpClient` factory when `java.net.http` is present (not verified against the Spring source
  here). The claim comes from the mission text, and Spola's behaviour is right either way. Hedge
  the row ("beror på fabriken") as the HTTP-version row already does.
- MNT-1 — The if-chain over `BodyReader.Ending` in `exchange`: an exhaustive `switch` without
  `default` would make a new constant a compile error. It is not a REGLER §4 violation: no
  runtime error path exists today, and §4 states a preference.
- MNT-2 — `Uris.java:33` and `:89` call `Text.limit` before `Text.ascii`, so a 200-char non-ASCII
  path becomes about 1 200 chars in the `IllegalArgumentException`. The 6 in `Text.limit` is
  unnamed.
- MNT-3 — Small duplications: `isClean` runs twice per failure, `describe(char)` is in both
  `HeaderRules` and `Uris`, and `escape` and `ascii` share a loop skeleton.
- MNT-4 — `Collector` repeats the "mark done" block four times, and the `limit == 0` branch is
  dead.
- MNT-5 — Unnamed literals (300, 18, 8_192, 16, 20), and two unrelated 8 KiB constants
  (`MAX_ERROR_BODY`, `EXCERPT_BYTES`).
- MNT-6 — `Support.FAST`: add a comment saying that `ConcurrencyTests` relies on the 5 s
  `idleTimeout` (the known stall in PROGRESS).
- MNT-7 — The Javadoc claims more than `Causes` does (whole values only). BRK-1's fix resolves
  this, either robustly or by narrowing the Javadoc. Also, `Failures.thrown`'s Javadoc names a
  parameter `call` that does not exist.
- MNT-8 — Swedish and English comments are mixed in `AiClients.java`, which is README example 1.
