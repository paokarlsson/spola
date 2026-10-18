VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: framework/modules/client/src/spola/client/Text.java:196 (limit), called at Uris.java:36 and :92, RestClient.java:356, Causes.java:107, RestClientException.java:82
  problem: `Text.limit` only works on text that is already ASCII, but nothing enforces that, and every caller repeats the pair `Text.limit(Text.ascii(x), n)`.
  evidence: Round 1's MNT-2 was exactly this mistake (limit called before ascii). Today's callers are correct, but the precondition is only a Javadoc remark: "{@code text} (already ASCII)".
  fix_hint: Add one `Text.shown(String text, int max)` that does ascii then limit. Make `limit` private or keep it for tests only.

- id: MNT-2
  severity: MINOR
  where: RestClient.java:101-117, RestClientException.java:42-54, RestClientResponseException.java:32-43, RestClientTransportException.java:22-38, module-info.java:33-37, Builder.defaultHeader and RestRequest.header Javadoc, README.md (module) lines 6-8, 233-244, 473, ARCHITECTURE.md:3573-3611, PROGRESS.md:880-884 and 961-971, root README.md:76
  problem: The rule for what is hidden is written out again in about ten places, and one of them over-claims.
  evidence: Round 1 already had drift (MNT-7: "Javadoc claims more than Causes does"). Root README.md:76 still says "utan att läcka huvudvärden", and MONSTER.md:61 is the catalog line next to it. That is absolute, while PROGRESS lists whole-value-only hiding as a known limitation.
  fix_hint: Keep one full statement (the `RestClientException` Javadoc) and have the other Javadocs and the README link to it. Soften the root README line to "döljer huvudvärden i felmeddelanden".

- id: MNT-3
  severity: MINOR
  where: Causes.java:44-45 and RestClient.java:441-450 (transportFailure)
  problem: Which exception classes count as "text from our side" is decided in two places that must be kept in step, and `transportFailure` is a factory that sometimes throws.
  evidence: `textIsSafe` tests SocketException, SSLException and HttpTimeoutException. The `switch (failure)` below it tests HttpConnectTimeoutException, HttpTimeoutException, ConnectException, SSLException, ProtocolException and IOException. `if (failure instanceof Error error) throw error;` sits in a method that returns an exception.
  fix_hint: Add a comment in both places pointing at the other, or move the labelling next to `Causes`. Rename `transportFailure` or add a Javadoc line saying it rethrows an Error.

- id: MNT-4
  severity: MINOR
  where: BodyReader.java:85 and RestClient.java:336
  problem: "Is this a 2xx" is computed separately in the reader (which sets the read limit) and in `exchange` (which decides whether the status is a problem).
  evidence: `boolean success = status >= 200 && status <= 299;` appears in both places.
  fix_hint: Add a small `Status.isSuccess(int)` helper or a `success()` method on `Received`.

- id: MNT-5
  severity: MINOR
  where: BodyReader.java:148-149 (Collector.bytes and length)
  problem: The thread-safety comment is missing for exactly the fields that look unguarded.
  evidence: `subscription` and `done` say "guarded by this". `bytes` and `length` say nothing, yet `abort()` runs on the caller's thread. They are only touched by the subscriber's serial signals (`onNext`, `onComplete`, `finish`).
  fix_hint: Add `// only touched by the subscriber's signals (serial under Flow)`.

- id: MNT-6
  severity: MINOR
  where: RestRequest.java:221 (`secrets()`), RestClient.java:421, RestClientResponseException.java:84, Text.excerpt's parameter `hidden`
  problem: The name `secrets` suggests credentials, but the list is every caller-given header value, the raw query and the whole body.
  evidence: This is why `anthropic-version: 2023-06-01` or `X-Page: 2` are "secrets". The surprising side effect (a short value also hides the server's own text) is only found in the "short value" caveat.
  fix_hint: Rename to something like `requestValues()` or `echoable()`. State in the Javadoc that all header values are included on purpose.

- id: MNT-7
  severity: MINOR
  where: framework/modules/client/test/spola/client/SecretTests.java:51-53, 95-99, 105, 130, 140, 145
  problem: Failure kinds are looked up by their display string, and the `default ->` branch of the `switch` silently stands for "the header-echo case".
  evidence: `kind("a 422 that sends the request body back")` and the same literals in `RESPONSE_ECHOES` and in `Failures.all()`. `default -> List.of(KEY_MARKER, AUTH_MARKER, TRACE_MARKER)`.
  fix_hint: Put the name constants or the list of markers that must stay in `bodyExcerpt()` on `Failures.Kind` (or as named constants), so a renamed kind does not fall into `default`.

- id: MNT-8
  severity: MINOR
  where: LimitTests.java:86-93, 109-116, 137-144, 157-163, 175-181 versus Failures.java HUGE_LENGTH, ENDLESS_CHUNKED, ENDLESS_ERROR; TransportTests.java:44, 56, 136
  problem: The raw-server scripts for "huge Content-Length", "chunked without end" and "500 without end" exist both in `Failures` and as inline copies in `LimitTests`. `awaitClosed` lives in `LimitTests` but is used by `TransportTests`.
  evidence: The same `var in = socket.getInputStream(); if (raw.readRequest(in) != null) {...}` block, repeated 5 times in LimitTests alone. `LimitTests.awaitClosed(server, 1, start)` is called from TransportTests.
  fix_hint: Reuse the `Failures` scripts (give them parameters such as `Failures.hugeLength(status, length)`). Move `awaitClosed` to `Support`.

- id: MNT-9
  severity: MINOR
  where: AnthropicTests.java:156-164 ("timeout(Duration.ofSeconds(60)) from the need is taken")
  problem: The test proves nothing about the need's code.
  evidence: `assertTrue(clients.toString().contains("AiClients"))` is always true, because `AiClients` has no `toString` and `Object.toString` contains the class name. The second half builds a new `RestClient` with 60 s and checks that one. If `AiClients` changed to 6 s the test would still pass.
  fix_hint: Rename it to what it checks ("build() accepts 60 s"), or let `AiClients` expose the built client's `toString` and assert on that.

- id: MNT-10
  severity: MINOR
  where: TransportTests.java:125-127
  problem: An unbounded wait in a test hangs the whole build instead of failing it.
  evidence: `while (server.requests.isEmpty()) { Thread.sleep(5); }` has no deadline.
  fix_hint: Wait with a deadline (as `LimitTests.awaitClosed` does) and fail with a message.

- id: MNT-11
  severity: MINOR
  where: SetupTests.java:50-62 and 104-108
  problem: Rows are positional `List<List<String>>`, and the test rebuilds the expected text by testing the row's text.
  evidence: `(row.get(2).startsWith("starts") || row.get(2).startsWith("ends") ? "" : "has ") + row.get(2)`. The row layout is name, input, expected, with no names.
  fix_hint: Use a record like `ExcerptTests.Row`, with the full expected fragment in each row.

- id: MNT-12
  severity: MINOR
  where: LimitTests.java:71 and 204, AnthropicTests.java:54 and 161, Failures.java:223, MessageTests.java:67-68, ConcurrencyTests.java:47-48, TransportTests.java:38 and 106, RequestTests.java:38
  problem: Fully qualified type names are used inline in files that import the same package, and in several cases the import is missing.
  evidence: `java.util.List.<spola.test.Body>of(...)`, `spola.json.JsonArray`, `java.time.Duration.ofSeconds(60)`, `spola.json.JsonNumber.of(404)`, `java.nio.charset.StandardCharsets.UTF_8`, `spola.http.HttpServer`.
  fix_hint: Add the imports.

- id: MNT-13
  severity: MINOR
  where: Text.java:79-90 (`wholeCharacters`)
  problem: The UTF-8 lead-byte arithmetic has no explanation of the magic numbers.
  evidence: `(bytes[lead] & 0xC0) == 0x80`, `length - lead <= 3`, `b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1`, `b < 0xF8`.
  fix_hint: Add a three-line comment: continuation bytes are 10xxxxxx, lead bytes 110/1110/11110, and 0xF8 and up are invalid so they are kept.

Overall: all of round 1's MNT-1 to MNT-8 are really fixed. Checked: the exhaustive `switch` in `exchange`, `Text.ascii` before `Text.limit` in `Uris`, a single `Causes` check, `Collector.end`, the named constants, the comment on `Support.FAST`, and Javadoc matching `Causes`. The main code is small and explicit, and I found no hidden wiring, reflection or naming conventions. `module-info.java` is exactly as specified, and the `Build.java` change (`staticProjectRequires`) is small and commented. The README, `ARCHITECTURE.md` and `PROGRESS.md` entries are consistent with the code. None of the findings above will realistically cause a bug or block a change, so the verdict is PASS.
