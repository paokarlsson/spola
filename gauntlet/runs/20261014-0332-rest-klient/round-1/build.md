# Round 1 – build

## Changes

New module `spola.client` (`framework/modules/client/`):

- `src/module-info.java`: exactly `requires transitive java.net.http`, `requires transitive spola.json`,
  `requires static spola.web`, `exports spola.client`; Javadoc for the module.
- `src/spola/client/RestClient.java`: final, immutable, thread-safe, `AutoCloseable`; nested
  `RestClient.Builder` (`baseUri`, `defaultHeader`, `timeout` 30 s, `connectTimeout` 10 s,
  `maxResponseSize` 4 MiB, `build()` collecting one line per problem); `get/post/put/patch/delete`;
  `close()`. Sends with `HttpClient` (HTTP/1.1, `Redirect.NEVER`, `NO_PROXY`, no cookie handler)
  via `sendAsync` + own `BodyHandler`, and waits with `get(timeout)` = total deadline including the
  body; on timeout/interrupt it cancels the body subscription and the future (connection closed).
  A read/write lock makes `close()` race-free (a call either starts before `HttpClient.close()` or
  gets `IllegalStateException`).
- `src/spola/client/RestRequest.java`: immutable request: `header`, `json(T, JsonCodec<T>)`,
  `json(JsonValue)`, `retrieve(codec)`, `retrieveJson()`, `retrieveBodiless()`; `toString()` with
  header names only and the URI without query.
- `src/spola/client/RestClientException.java` (abstract, sealed, `method()`, `uri()`,
  `MAX_MESSAGE`), `RestClientResponseException.java` (`status()`, `headers()`, `bodyExcerpt()`,
  `MAX_EXCERPT`), `RestClientTransportException.java` (`timedOut()`); package-private constructors.
- Internal: `BodyReader.java` (body handler/subscriber with the limits: 2xx up to
  `maxResponseSize`, other statuses up to `min(8 KiB, maxResponseSize)`, `Content-Length` over
  `maxResponseSize` refused before reading; cancelling closes the connection), `HeaderRules.java`
  (token names, value rules, the 11 refused names, messages without values), `Uris.java` (base URI
  and path checks per RFC 3986, ASCII only, text concatenation, URI without query for messages),
  `Text.java` (excerpt, escaping, one-line ASCII, cutting without splitting a `\u` escape),
  `Causes.java` (replaces a JDK cause whose text quotes a header value, the query or the body).
- `test/spola/client/`: `ClientTests` (entry) and `ModuleTests`, `AnthropicTests`, `AiClients`
  (README example 1, the need's code), `ItemApp` + `ItemApiTests` (README example 2),
  `ResponseTests`, `LimitTests`, `TransportTests`, `ExcerptTests`, `MessageTests`, `SecretTests`,
  `Failures` (every failure kind of criteria 6–9, shared), `SetupTests`, `RequestTests`,
  `ConcurrencyTests`, `CloseTests`, `SwitchTests`, `ReadmeTests`, `Support` (spola.http and raw
  socket servers).
- `README.md` (module): comparison table, Spring vs Spola for the Anthropic/OpenAI calls and for
  the integration test next to `@SpringBootTest(webEnvironment = RANDOM_PORT)`, limits table,
  deviation table (all 8 required topics plus more).

Other files:

- `framework/Build.java`: the new `MODULES` line; `runTests` adds the module's `requires static`
  project modules as roots of its test layer (`staticProjectRequires`, read from the compiled
  `module-info.class`), plus one doc-comment sentence. Nothing else (diff below).
- `framework/ARCHITECTURE.md`: directory list line, a bullet under "Bygget" about the test layer
  and `requires static`, new section "REST-klient (`spola.client`)".
- `framework/PROGRESS.md`: section "Uppdrag 14 ... — behovet levererat" (says the need
  `2026-10-13-rest-klient` is delivered), "Återstår" updated, known limitations (all 9 required
  plus others).
- `framework/MONSTER.md` module catalogue and root `README.md` module table: a `spola.client` row.

No other module's `src/` or `test/` was changed.

## Verification

`cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java`, run twice on the final
code. The last run, on exactly the state handed over (after a prose edit in PROGRESS.md), ended
`guardrails: no violations`, `spola.client: OK - 295 tests, 295 ok, 0 failed ... in 13335 ms`,
`BUILD OK in 334602 ms`, exit 0. The run before it (same code):

```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config, spola.util, spola.codegen, spola.json, spola.http, spola.validation, spola.web, spola.security, spola.security.web, spola.client, spola.demo]
...
compiled spola.client (10 files)
compiled tests of spola.client (20 files)
guardrails: source       519 .java files
guardrails: source       16 other files: 0 not allowed, 16 documents (0 parse as Java)
guardrails: bytecode     1387 .class files
guardrails: dependencies 28 module-info.class files
guardrails: files        1960 files
guardrails: no violations
...
spola.web: OK - 1093 tests, 1093 ok, 0 failed, ...
spola.security.web: OK - 151 tests, 151 ok, 0 failed, ...
spola.client: OK - 295 tests, 295 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 13397 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, ...
exit guard:
  ok - after the decision: exit 0, other hooks and deleteOnExit ran
  ok - before the decision: System.exit(0) ends red (exit 1)
BUILD OK in 315712 ms
exit 0
```

Excerpt of the spola.client report (parameterized rows left out):

```
  module boundaries
    ok      module-info has exactly requires transitive java.net.http, requires transitive spola.json, requires static spola.web and exports spola.client
    ok      the main code uses nothing from spola.web, spola.http, spola.validation, java.lang.reflect or java.lang.invoke
  the need's code against a server that plays Anthropic and OpenAI
    ok      200 with JSON gives the decoded MessagesResponse; the server saw every header and the body
    ok      400 with Anthropic's error format gives RestClientResponseException 400 with the type and text
    ok      the need's log line has the status and the excerpt but not the key
    ok      500 and 529 give RestClientResponseException with that status
    ok      timeout(Duration.ofSeconds(60)) from the need is taken
    ok      OpenAI: defaultHeader Authorization Bearer, json(JsonValue) and retrieveJson give the server's tree
  item API through a real server
    ok      create, show, delete, then 404 and 400
  maxResponseSize
    ok      Content-Length: 10000000000 fails at once; the server never sends the body (10 ms)
    ok      a chunked body that never ends fails within 2 s and the connection is closed (97 ms)
    ok      with timeout 30 s, a 500 with a body that never ends fails within 2 s; the excerpt is at most 1 000 characters plus ... (12 ms)
  timeouts and transport failures
    ok      timeout 500 ms: a server that takes the request and never answers gives timedOut within 2 s, and the server sees the connection close within 5 s (516 ms)
    ok      timeout 500 ms: status line and headers, then one byte every 100 ms without end, gives timedOut within 2 s, and the server sees the connection close within 5 s (622 ms)
    ok      https: the client starts with a TLS handshake (first byte 0x16) and gets RestClientTransportException from a server that does not speak TLS
    ok      a thread interrupted while it waits gets RestClientTransportException within 2 s, the cause is InterruptedException and the interrupt flag is set
  secrets
    ok      the echo server: the JDK's message quotes the request line, so the cause is a stand-in
  concurrency
    ok      200 virtual threads share a RestClient, send their own body and get their own answer (5067 ms)
  close
    ok      calls racing with close() either finish or get IllegalStateException, never a transport error
  switch over RestClientException
    ok      the two cases, without default
  README
    ok      the Anthropic and OpenAI example is AiClients.java as it is compiled and tested
    ok      the router example is ItemApp.java and the integration test is ItemApiTests.java
```

`git diff framework/Build.java` (criterion 2):

```
+        new ModuleDir("spola.client", Path.of("modules/client"),
+                new TestEntry("spola.client.ClientTests", "suite")),
...
-/// `framework/` as working directory.
+/// `framework/` as working directory. The project modules that the module `requires static` are
+/// roots of its layer too (see staticProjectRequires).
...
+        var roots = new ArrayList<>(List.of(module.name(), TEST_RUNNER));
+        roots.addAll(staticProjectRequires(module));
         var configuration = boot.configuration().resolve(ModuleFinder.of(paths.toArray(Path[]::new)),
-                ModuleFinder.of(), Set.copyOf(List.of(module.name(), TEST_RUNNER)));
+                ModuleFinder.of(), Set.copyOf(roots));
...
+List<String> staticProjectRequires(ModuleDir module) { ... }   // reads requires static from module-info.class
```

During development the client suite was also run repeatedly in isolation (compiled against copies
of the module jars); all runs green, e.g. `spola.client: OK - 295 tests, 295 ok`.

## Addressed

Round 1: no findings yet.

## Rebuttals

None.

## Design choices worth checking (the spec left them open)

- **No proxy at all** (`HttpClient.Builder.NO_PROXY`), not the JDK default selector that reads
  `https.proxyHost`: the mission says "ingen proxy" and "Förfrågan går därmed alltid till basens
  schema, värd och port". Documented in Javadoc, README deviation table, ARCHITECTURE and PROGRESS.
- **`Content-Length` over `maxResponseSize` is refused unread for every status**, also a 4xx/5xx
  (excerpt `""`, the message says the body was not read). Otherwise an error response could make
  the client wait for a body that never comes.
- **The deadline running out while an error body is being read** gives
  `RestClientTransportException` with `timedOut()` (the mission: "Går den ut ... kastar då
  RestClientTransportException med timedOut()"). Listed in PROGRESS.
- `json(null, codec)` throws `NullPointerException` (the mission's rule "null som argument ger
  NullPointerException"); JSON `null` is sent with `json(JsonNull.INSTANCE)`. `json(...)` is
  allowed on every method, `GET` included (documented).
- `getMessage()` of a transport failure includes the JDK exception's class and (ASCII, ≤ 300
  characters) message, unless that text quotes a header value, the query or the body of the
  request: then the cause is a stand-in `IOException` (class name + stack trace, no text) and the
  message has only the class. Tested with a server that echoes the request and one that echoes
  the body.
- The URI part of a message is cut at 400 characters so that the status always fits in 1 000.

## Known gaps

- `connectTimeout` firing (`HttpConnectTimeoutException` → `timedOut()`) has no end-to-end test:
  there is no reliable way to make a local connect hang. The mapping is in `transportFailure`.
- `Causes` recognises whole values only: a server that echoes part of a header value and gets the
  JDK to quote that part would show the part in the message. A short value (`1`, body `{}`) makes
  the stand-in replace causes more often than needed (only diagnostics are lost).
- `java.net.http` drops an empty query (`/x?` is sent as `/x`); documented on `get(path)` and in
  PROGRESS.
- The excerpt is cut at 1 000 chars *before* escaping (as the mission specifies), so an excerpt
  full of control characters is longer than 1 000 characters after escaping.
- Observed, not caused by this module: with 200 concurrent calls against a `spola.http` server,
  in about one run of three one or two calls complete only when the server closes an idle
  connection (`idleTimeout`; 5 s in the tests, so the concurrency test takes about 5 s). The same
  happens with plain `java.net.http.HttpClient` and no `spola.client` (reproduced with a probe that
  varied each server timeout: only `idleTimeout` moved the stall). Root cause not found (JDK
  client or `spola.http`). Recorded in PROGRESS under known problems.
- The module is security-relevant (secrets in headers, data from outside); PROGRESS says a human
  should review it.
