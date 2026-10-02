# Round 1 – build

## Changes

New module `spola.http` (`framework/modules/http/`):

- `src/module-info.java`: exports `spola.http`; `requires static java.net.http` (for the tests only, see "Decisions to look at").
- Public API: `HttpServer` (start/port/address/close), `HttpServerOptions` (record: address, port, limits, timeouts), `HttpLimits` (record, `DEFAULT` 8192/16384/100/10 MiB/10000), `HttpTimeouts` (record, `DEFAULT` 10/60/30/30/30 s), `Handler`, `Request`, `Headers` (+ `Headers.Field`), `Response` (record), `ResponseBody` (sealed: `Empty`, `Bytes`, `Streaming`), `BodyWriter`, `RequestBodyException`, `HttpVersion`.
- Internal: `Connection` (one virtual thread per connection, states IDLE/ACTIVE/LINGERING/CLOSED, keep-alive, pipelining, error handling, lingering close), `ConnectionInput` (buffered input; per-read timeout or absolute deadline for headerTimeout), `RequestParser` (strict request-line/headers/framing), `BodyStream` (Content-Length/chunked body, maxBodySize, readTimeout, 100-continue on first read, skip of unread rest), `ResponseWriter` (head, Date in IMF-fixdate, framing, chunked/close-delimited streaming with lazy commit), `GuardedOutput` (write guard), `Syntax` (char classes, escaping), `LoggedThrowable` (escaped copy of an exception chain for logs), `ProtocolException`.
- Tests (`test/spola/http/`, 138 tests, ~5 s): `HttpTests` (entry), `Support` (server on 127.0.0.1:0, raw socket client, captured logger), `ClientTests`, `ConnectionTests`, `MalformedRequestTests`, `ChunkedTests`, `ExpectTests`, `TimeoutTests`, `MaxConnectionsTests`, `ErrorTests`, `ResponseTests`, `ShutdownTests`, `ConcurrencyTests`, `SourceRulesTests`.
- `README.md`: complete example (start, respond, read body, stream, stop) beside Spring Boot (`@RestController` + `StreamingResponseBody`) and embedded Tomcat `HttpServlet`; table of `HttpLimits`/`HttpTimeouts` vs `server.max-http-request-header-size`, `server.tomcat.max-swallow-size`, `server.tomcat.connection-timeout`, `server.tomcat.keep-alive-timeout`, `server.tomcat.max-connections`, `server.shutdown=graceful` with behavioural differences; table of what is rejected with status and RFC section.

Other files:
- `framework/Build.java`: `spola.http` with `TestEntry("spola.http.HttpTests", "suite")` added to `MODULES`.
- `framework/ARCHITECTURE.md`: new section "HTTP-server del 1" with all decisions from criterion 18 (own implementation vs `jdk.httpserver`, blocking I/O on virtual threads, strict CRLF, smuggling rules, default limits and timeouts with Tomcat/nginx comparison, write guard, `maxConnections` behaviour, reserved response headers, graceful stop, the 07a/07b split) plus the `requires static` decision and the lazy-body consequence; directory listing updated.
- `framework/PROGRESS.md`: 07a under "Klart", "Återstår" now 07b and 08–20, known HTTP limitations.

## Verification

`java Build.java` in `framework/` (exit 0):

```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config, spola.util, spola.codegen, spola.json, spola.http, spola.demo]
...
guardrails: no violations
...
tests of spola.http:
spola.http: OK - 138 tests, 138 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 5392 ms
...
BUILD OK in 48524 ms
```

Stability: three consecutive full builds green (http tests 5.1–5.5 s each), and one full build with 16 busy-loop shells saturating all 16 cores: `BUILD OK in 132822 ms`, `spola.http: OK - 137 tests` (before the last added unit test).

Slowest http tests: slow-steady-reader 905 ms, maxConnections 717 ms, headerTimeout 503 ms; everything else ≤ 384 ms.

## Criteria map

| # | Where |
|---|---|
| 1 | `Build.java` MODULES; build green |
| 2 | `SourceRulesTests` (no `java.lang.reflect`/`invoke`, no `com.sun.net.httpserver`/`jdk.httpserver` in code; module-info requires only `static java.net.http`; runtime descriptor has no `spola.*` requires; no main class uses `java.net.http`) |
| 3 | `ClientTests`: GET, POST (Content-Length), POST via `BodyPublishers.ofInputStream` (handler sees `Transfer-Encoding: chunked`), HEAD with same Content-Length as GET and empty body |
| 4 | `ConnectionTests`: 3 requests keep-alive, pipelining (2 in one write, plus with a body in between), `Connection: close`, HTTP/1.0 closes (also with `keep-alive`), unread body skipped (CL, chunked, 200 KB, half-read) |
| 5 | `MalformedRequestTests`: 71 head cases (handler call count asserted 0) + 14 chunked-body cases; each asserts exact status, `Connection: close`, closed connection; each has an RFC 9112/9110 section |
| 6 | `MalformedRequestTests` "a smuggled request hidden in a body never reaches the handler" (CL.TE, TE.CL, obfuscated TE, bare LF, double CL, after a good request) |
| 7 | `ChunkedTests`: extensions (with BWS, quoted-string) + trailers, connection reusable after; streaming response exact bytes `3\r\nabc\r\n5\r\ndefgh\r\n2\r\nij\r\n0\r\n\r\n`; HTTP/1.0 close-delimited; chunked > maxBodySize gives 413 |
| 8 | `ExpectTests` (100 on read, none when not read + close, 413 at once over max, ignored in 1.0/without body) |
| 9 | `TimeoutTests` (byte every 50 ms cut at ~500 ms; idle; read; write guard frees the handler thread; slow-but-steady reader not cut) |
| 10 | `MaxConnectionsTests` (decision: wait with accept; 6th silent for 700 ms, served after one closes; 20 more connections afterwards) |
| 11 | `ErrorTests` (500 without message, Error x3 then server still serves, null response, abort after streaming started without last chunk, clean 500 before) |
| 12 | `ResponseTests` (CR/LF/NUL/ESC/DEL in names/values, reserved headers any case, 1xx and out-of-range, 204/304 without CL pipelined, Date IMF-fixdate on 200/HEAD/500/505/400, no `Server`) |
| 13 | `ShutdownTests` (idle closed, new refused, `close()` blocks until the slow request finishes with `Connection: close`; stuck handler aborted after 300 ms and interrupted; handler closing its own server) |
| 14 | `ConcurrencyTests` (1 000 virtual-thread clients × 3 keep-alive requests, bodies echoed) |
| 15 | `ErrorTests` "no log line and no error body holds raw control characters" (ESC/NUL in target, header values, Host, method, ESC/NUL/CR/LF/C1 in an exception message, bad chunk line) + `LoggedThrowable` unit test |
| 16 | `modules/http/README.md` |
| 17 | guardrails green; only `.java`/`.md` added; annotations: only `@FunctionalInterface`, `@Override`, `@Serial` |
| 18 | ARCHITECTURE.md "HTTP-server del 1", PROGRESS.md |

## Decisions to look at (deliberate, documented)

1. **`Transfer-Encoding` statuses deviate from the mission's wording.** Mission: 501 for any TE that is not exactly `chunked`. RFC 9112 §6.3: "If a Transfer-Encoding header field is present in a request and the chunked transfer coding is not the final encoding, the message body length cannot be determined reliably; the server MUST respond with the 400 (Bad Request) status code and then close the connection." So `chunked, identity`, `xchunked`, `identity` → 400; `gzip, chunked` and `chunked` twice → 501 (§6.1). Connection closed in all cases. Documented in README and ARCHITECTURE.
2. **Chunked-body errors call the handler.** The mission makes the body lazy (100-continue only when the handler reads), so framing errors inside a chunked body can only be found while the handler reads. Criterion 5's "handler is never called" is asserted for all 71 head cases; for the 14 body cases the test asserts the handler never obtains the body and its response is never sent (the server answers the body's status). Documented in the test class, README, ARCHITECTURE and PROGRESS.
3. **`requires static java.net.http`** in `module-info.java`: tests are compiled as part of the module, so criterion 3 (HttpClient test) needs a read edge; `static` keeps it optional at run time; `SourceRulesTests` checks no main class uses it. The alternative (a test-only JDK-reads mechanism in Build.java and guardrails) would change two other modules.
4. **Stricter than asked:** target path/query checked against RFC 3986 (`#`, `{`, broken `%XX` → 400); `Expect` other than `100-continue` → 417; values with leading/trailing whitespace refused in `Headers.Field`; status outside 200–599 refused; 205 also requires an empty body (sent with `Content-Length: 0`).
5. **Lingering close** after a `Connection: close` response (shutdownOutput, drain ≤ 2 s / 1 MiB) so a TCP reset does not destroy the response.
6. **A new connection** may wait `headerTimeout` for its first byte (then the header deadline starts).

## Addressed

Round 1: no findings yet.

## Rebuttals

None.

## Known gaps

- `close()` waits for every connection thread after aborting; a handler that neither does I/O nor reacts to interrupts keeps `close()` waiting (the spec says `close()` returns when all connection threads are done).
- A request arriving at the instant `close()` closes an idle keep-alive connection is lost (client sees a closed connection; RFC 9112 §9.3.1 retry).
- Lingering after `Connection: close` holds a `maxConnections` slot for up to 2 s if the client does not close.
- Chunk line limit (4 096) and backlog (1 024) are constants, not in `HttpLimits`.
- The request body can only be read on the handler's thread while the handler or its `BodyWriter` runs.
