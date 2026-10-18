# Round 2 – build

This round was resumed. The interrupted round-2 builder left most of the code and test changes
in the working tree, and they were not verified. I checked them against the findings, re-ran the
judge's probes 5 and 8 against them, tightened the secret claims in the Javadoc and the README,
updated `PROGRESS.md` and `ARCHITECTURE.md` (both were still at round 1) and ran the full build.

## Changes

Main code (`framework/modules/client/src/spola/client/`):

- `Causes.java`: rewritten (BRK-1, MNT-3, MNT-7). Whether a JDK failure keeps its text now
  depends on its class, not on a search for the request's values. Only `SocketException` (for
  example `ConnectException`), `SSLException` and `HttpTimeoutException` keep their text, because
  for them it comes from the JDK or the OS. Even those lose it if the text, or a cause or a
  suppressed exception, contains a request value (`textIsSafe`, `quotesNone`). Every other failure,
  `ProtocolException` and other response-parsing `IOException`s among them, is replaced by
  `withoutText`. That is a chain of `IOException`s with no text, one per cause, each naming the
  original class and keeping its stack trace. The message then names only the class and the root
  cause's class (`describe`). The check runs once per failure.
- `Text.java`:
  - `excerpt(body, length, more, hidden)` replaces `excerpt(body, length)`.
  - `hide(...)` (BRK-2) puts `<hidden>` in place of every whole occurrence of a request value. It
    runs before the 1 000-char cut and before escaping. Overlapping or touching occurrences become
    one placeholder, and a value cut off by a read limit is hidden too.
  - `more` and `wholeCharacters` (BRK-4): when reading stopped at a limit, the excerpt ends with
    `...` and drops an incomplete UTF-8 sequence that the cut made.
  - U+061C is now in `mustEscape` (BRK-5).
  - `describe(char)` moved here from `HeaderRules` and `Uris` (MNT-3), `escape` and `ascii` share
    `escapeWhere` (MNT-3), and `EXCERPT_BYTES` is now tied to `RestClient.MAX_ERROR_BODY` with a
    reason (MNT-5).
- `RestClientResponseException.java`: `of(request, ...)` builds the raw `bodyExcerpt()` and the
  message's hidden excerpt from the same bytes (BRK-2). The Javadoc says what is hidden and what
  is not: parts of a value, re-encoded values, short values that also hide the server's own text,
  and the up to 40 chars of the response that the `spola.json` cause can quote. New in this resume
  are the short-value sentence and the wording for both json causes.
- `RestRequest.java`: `secrets()` returns the value of every header the caller gave, the raw query,
  and the body as UTF-8 and, when that differs, as ISO-8859-1 (the form the JDK quotes).
  `unusable(...)` passes the request on.
- `BodyReader.java`:
  - `contentLength` maps an all-digit value too long for a `long` to `Long.MAX_VALUE` (BRK-3).
    `refusedUnread()` covers the case where `java.net.http` fails the exchange itself, which it
    does for values over `Long.MAX_VALUE` after the handler has seen the head. Both cases give
    `RestClientResponseException` with the status.
  - `Collector` uses a single `end(outcome, cancel)` instead of four copies of the "mark done"
    block, and the dead `limit == 0` branch is gone (MNT-4).
  - The constants are named (`INITIAL_MAX`, `INITIAL_UNKNOWN`, `INITIAL_MIN`, MNT-5).
- `RestClient.java`:
  - `exchange` uses an exhaustive `switch` over `Ending` (MNT-1). `send` uses `refusedUnread`.
    `transportFailure` uses the new `Causes`. `MAX_QUOTED_LENGTH` is named.
  - The class Javadoc's "Secrets" section is now a list that claims only what holds (rewritten in
    this resume).
- `RestClientException.java`, `RestClientTransportException.java`, `module-info.java`: the Javadoc
  is narrowed to what holds (BRK-2, MNT-7). The module never writes the request's values, the
  message hides whole values the server sends back, and transport causes keep text only for the
  three classes above.
- `HeaderRules.java`, `Uris.java`: use `Text.describe`. `Text.ascii` now runs before `Text.limit`,
  so the cap is on the escaped text (MNT-2). `MAX_QUOTED_SCHEME` is named.

Tests (`framework/modules/client/test/spola/client/`):

- `Failures.java`: a `sendsBack(...)` helper and six new failure kinds, which every message test
  and secret test runs:
  - a FastAPI-style 422 that echoes the body (`VALIDATION_ECHO`);
  - an httpbin-style 200 that echoes the headers, with a codec that does not fit (`HEADER_ECHO`);
  - a 404 problem whose `instance` is the target with its query (`PROBLEM_ECHO`);
  - part of the request line, then close (`ECHOES_PART_OF_LINE`);
  - part of the `x-api-key` value in a bad header (`ECHOES_PART_OF_KEY`).
  `ECHOES` and `ECHOES_BODY` were kept and now use the helper. The Javadoc of `thrown` is fixed
  (MNT-7).
- `SecretTests.java`: the body marker now sits in Swedish text (`"Hej, hur mår du? " + marker`),
  as BRK-1 asked. New tests cover:
  - echoed values hidden in `getMessage()` but kept in `bodyExcerpt()`;
  - the exact hidden message for the 422;
  - a long echoed body cut at 8 KiB, for 400 and 200;
  - the cause of a request echoed back as the response;
  - the body echoed back as the status line, in Swedish;
  - a partial request line;
  - `Causes.textIsSafe` and `Causes.withoutText` as units.
- `LimitTests.java`: `Content-Length` values `1000000000000000000`, `9223372036854775807`,
  `9223372036854775808`, `99999999999999999999` and `1` followed by 100 zeros, each for 200 and 500.
  Each must fail within 2 s with the status, an empty excerpt and the length (cut to 40 chars) in
  the message (BRK-3).
- `ExcerptTests.java`: U+061C in the escape list. New: a body cut at `maxResponseSize(1024)` (`€`,
  200 and 500) ends with `...` and no split character. Unit tables for `Text.excerpt` with `more`,
  `wholeCharacters` and `hide`, and a value that runs across the 1 000-char cut.
- `SetupTests.java`: the expected messages now include `(space)`/`(CR)` from the shared
  `Text.describe`. `Support.java`: a comment on `FAST` saying that `ConcurrencyTests` relies on the
  5 s `idleTimeout` (MNT-6). `AiClients.java`: all comments are in Swedish, the language of the
  need's own comments that the file carries (MNT-8).

Docs:

- `modules/client/README.md`:
  - The README copy of `AiClients` matches the source again.
  - The redirect row is hedged ("Beror på fabriken", AUD-3).
  - New row "Felmeddelandet" in the deviation table.
  - The limits table has U+061C, the `...` at a read limit and `<hidden>`.
  - The intro and the "Loggraden"/"Hela undantaget i loggen" notes claim only what holds (wording
    tightened in this resume).
- `framework/ARCHITECTURE.md`, section "REST-klient (`spola.client`)":
  - new bullet "Värden från förfrågan som servern skickar tillbaka": what is hidden, where, the
    limits, and why searching for parts was rejected;
  - the "Orsaker" bullet is replaced by "Transportfelets orsak behåller bara text som inte kan
    komma från servern";
  - the size limit covers 19+ digits and `refusedUnread`;
  - `bodyExcerpt()` covers U+061C and the `...` at a read limit;
  - the intro, the list of internal classes and the test summary are updated.
- `framework/PROGRESS.md`, entry 14 and its known limitations:
  - narrowed the secret claim;
  - replaced the old `Causes` limitation with two: values sent back are hidden only whole, and
    transport failures show only class names for bad responses;
  - updated the test count (about 330).

Not changed: `Build.java` (this round), other modules, `MONSTER.md`, the root `README.md`.

## Verification

Full build, `cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`, on exactly the
state handed over (after the last doc edit):

```
guardrails: source       519 .java files
guardrails: source       16 other files: 0 not allowed, 16 documents (0 parse as Java)
guardrails: bytecode     1391 .class files
guardrails: dependencies 28 module-info.class files
guardrails: files        1964 files
guardrails: no violations
...
spola.json: OK - 417 tests, 417 ok, 0 failed, ...
spola.http: OK - 290 tests, 290 ok, 0 failed, ...
spola.web: OK - 1093 tests, 1093 ok, 0 failed, ...
spola.security.web: OK - 151 tests, 151 ok, 0 failed, ...
spola.client: OK - 330 tests, 330 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 13062 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, ...
exit guard:
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
BUILD OK in 300179 ms
exit=0
```

Excerpt from the client report (new rows):

```
    no marker in getMessage, toString or the printed stack trace of any failure
      ok      [14] a 422 that sends the request body back (3 ms)
      ok      [15] a 200 that sends the request headers back, with a codec that does not fit (3 ms)
      ok      [16] a 404 problem that sends the path and query back (3 ms)
      ok      [17] a server that echoes the request (3 ms)
      ok      [18] a server that echoes the request body (3 ms)
      ok      [19] a server that sends part of the request line and closes (3 ms)
      ok      [20] a server that sends part of a header value back in a bad header (3 ms)
    a Content-Length of 19 digits or more fails at once with the status, also one too long for a long
      ok      [1] 1000000000000000000 (7 ms)
      ok      [2] 9223372036854775807 (17 ms)
      ok      [3] 9223372036854775808 (7 ms)
      ok      [4] 99999999999999999999 (7 ms)
      ok      [5] 1000…000 (101 digits) (4 ms)
    ok      a 422 with the body's Swedish text: the message hides the body and keeps the rest (3 ms)
    ok      a server that sends the body back as its status line, with Swedish letters: only the class (3 ms)
    ok      Text.hide: whole values, overlapping and touching ones as one, an end that starts a value when cut (0 ms)
```

Before the full build, the client suite also ran on its own, compiled against copies of the other
module jars (`scratchpad/dev/build.sh`): `spola.client: OK - 330 tests, 330 ok`, twice (before and
after the Javadoc edits; doclint `-Werror` included).

Judge's probes, re-run against the current client (compiled from the working tree):

- probe 5 (BRK-1, BRK-2):
  ```
  1 echo body (Swedish): RestClientTransportException
     message: POST http://127.0.0.1:33037/v1/messages: the response is not valid HTTP/1.1 (java.net.ProtocolException)
     marker in getMessage: false, in printed stack trace: false, cause java.io.IOException: java.net.ProtocolException (its text is left out: it can quote what the server sent)
  3 FastAPI 422 echoing the input: RestClientResponseException
     message: POST http://127.0.0.1:40349/v1/messages: status 422: a client error: {"detail":[{"type":"missing","loc":["body","max_tokens"],"msg":"Field required","input":<hidden>}]}
     marker in getMessage: false, in printed stack trace: false, cause null
  4 httpbin-like echo of headers, codec mismatch: RestClientResponseException
     message: POST http://127.0.0.1:41495/anything: status 200: the response body does not fit the codec: {"headers":{...,"Authorization":"<hidden>",...},"method":"POST"}
     marker in getMessage: false, in printed stack trace: false, cause spola.json.codec.JsonMappingException: 3 problems: ...
  ```
- probe 8 (BRK-3), default timeout 30 s:
  ```
  Content-Length 999999999999999999 (18 digits): 5 ms, RestClientResponseException: ... status 200: ... (Content-Length 999999999999999999 is over maxResponseSize 4194304 bytes; the body was not read)
  Content-Length 1000000000000000000 (19 digits): 5 ms, RestClientResponseException: ... status 200: ...
  Content-Length 99999999999999999999 (20 digits): 4 ms, RestClientResponseException: ... status 200: ...
  ```

## Addressed

- **BRK-1** [MAJOR]: took the judge's preferred, robust fix. The class decides: only
  `SocketException`, `SSLException` and `HttpTimeoutException` keep their JDK text, and only when
  it holds no request value. Every other failure (`ProtocolException`, the parsing `IOException`s,
  `NumberFormatException`, ...) is a text-free copy with classes and stack traces. The secret tests
  now use Swedish text in the body. A partial echo of the request line and a partial echo of the
  key are new failure kinds, which closes MNT-7's partial-echo gap for transport failures. Probe 5
  case 1: no marker.
- **BRK-2** [MAJOR]: followed the hint.
  - `getMessage()` hides every whole occurrence of a request value (header values, raw query, body
    as UTF-8 and ISO-8859-1), before the cut and before escaping. `bodyExcerpt()` stays raw.
  - The claims in `RestClient`, `RestClientException`, `RestClientResponseException`,
    `RestClientTransportException`, `module-info`, the README, PROGRESS and ARCHITECTURE are
    narrowed to what holds (whole values only; the `spola.json` cause can quote 40 chars).
  - The tests are the hint's echo servers (a 4xx echoing the body, a 2xx echoing the headers with
    a codec mismatch) plus a 404 problem echoing the query. None has a marker in `getMessage()`,
    `toString()` or the printed trace, and the markers are still in `bodyExcerpt()`. Probe 5 cases
    3 and 4: no marker.
- **BRK-3** [MAJOR]: an all-digit `Content-Length` too long for a `long` counts as
  `Long.MAX_VALUE`. When `java.net.http` fails the exchange itself (over `Long.MAX_VALUE`),
  `refusedUnread()` turns that into the same `RestClientResponseException`. `LimitTests` has both
  of the hint's values and three more, for 200 and 500. Probe 8: 19 and 20 digits give the status
  in about 5 ms.
- **BRK-4** (MINOR): done as hinted. `excerpt` gets `more`. On a cut it drops a trailing
  incomplete sequence and appends `...`. Tested end to end and in unit tables.
- **BRK-5** (MINOR): U+061C added to `mustEscape`, the excerpt tests, the Javadoc of
  `bodyExcerpt()`, the README and ARCHITECTURE. The list is now all of `Bidi_Control`.
- **AUD-3** (MINOR): the README redirect row is hedged: "Beror på fabriken: `HttpURLConnection`
  följer omdirigeringar för `GET`, och JDK:ns `HttpClient` gör det om den är inställd så."
- **MNT-1**: an exhaustive `switch` over `BodyReader.Ending` in `exchange`, without `default`.
- **MNT-2**: `Text.ascii` runs before `Text.limit` in `Uris`. `Text.limit` uses the named
  `ESCAPE_LENGTH`.
- **MNT-3**: `Causes` checks once. `describe(char)` lives only in `Text`. `escape` and `ascii`
  share `escapeWhere`.
- **MNT-4**: `Collector.end(...)` replaces the four "mark done" blocks, and the dead branch is
  gone.
- **MNT-5**: the literals are named (`MAX_QUOTED_LENGTH`, `MAX_TEXT`, `MAX_QUOTED_SCHEME`,
  `INITIAL_*`). `EXCERPT_BYTES` is defined as `RestClient.MAX_ERROR_BODY`, with the reason the two
  are the same window.
- **MNT-6**: there is now a comment on `Support.FAST`.
- **MNT-7**: the Javadoc matches `Causes` (see BRK-1), and the Javadoc of `Failures.thrown` is
  fixed.
- **MNT-8**: all comments in `AiClients.java` (and its README copy and the Spring side) are now
  Swedish, the language of the need's own code comments that the file reproduces.

## Rebuttals

None.

## Known gaps

- **Whole values only (by design, now documented).** A server that sends back one member of the
  body or one query parameter, or re-encodes JSON with other spacing or escapes, shows that part
  in `getMessage()`. A short header value (`X-Page: 2`) also hides the same text where the server
  wrote it on its own. Only diagnostics are lost; `bodyExcerpt()` is unchanged. I rejected
  searching for parts (every string in the body, every query parameter), because it guesses what
  is secret and would hide most of a message. ARCHITECTURE gives the reason.
- **The `spola.json` cause** (`JsonParseException`, `JsonMappingException`) is kept, as the mission
  requires. It can quote up to 40 chars of the response, for example a member name the server
  echoed. Documented on `RestClient` and `RestClientResponseException`, and in the README and
  PROGRESS.
- **Less diagnostics for bad responses.** A garbage status line, a body cut short or a bad header
  now shows only class names (`java.io.IOException, caused by java.io.EOFException`). That is the
  cost of BRK-1's robust fix, and PROGRESS lists it.
- Carried over from round 1, unchanged:
  - there is no end-to-end test of `connectTimeout` firing;
  - the excerpt is cut at 1 000 chars before escaping, as the mission specifies, so after escaping
    it can be longer;
  - the 200-thread stall comes from `spola.http`/`java.net.http`, not this module (AUD-4,
    rejected, listed in PROGRESS);
  - a human should review the module.
- The working tree also has the planner's untracked `uppdrag/14-rest-klient.md` and a behov file
  moved to `uppdrag/behov/klara/`. They are not mine, and I left them alone.
