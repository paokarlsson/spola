# Round 2 — build

## Changes
- `modules/http/src/spola/http/Connection.java`
  - `exchange` split into `handle` (handler call, returning an `Outcome` record), `streams`, `awaitsContinue`, `shouldClose`, `bodyWriterFailed` and `bodyFailureResponse` (MNT-1).
  - **BRK-1:** before the response head is written, the server now reads and drops the unread rest of the body (`body.skipRest()`, bounded by `maxBodySize`/`readTimeout`) when the handler did not fail, no body failure has been seen yet, the response is not streaming, and no `100-continue` is pending. If that fails, the body's status (400/408/413/431) is sent with `Connection: close` in place of the handler's response. Three cases keep the old behaviour, which is now documented: a streaming response (its `BodyWriter` may read the body), an unread body under `Expect`, and a handler that caught `RequestBodyException` itself. In those cases the response goes out and the connection closes afterwards.
  - `State` Javadoc now has a transition table (MNT-3). `guarded` is `volatile` (MNT-2). The private `closeQuietly` is gone and `HttpServer.closeQuietly` is used instead (MNT-4).
  - **BRK-3:** the connection now lingers during a graceful stop too, bounded by `server.shutdownDeadline()`. `closeIfIdle()` closes only `IDLE` connections, so `LINGERING` ones finish their linger. Added `lingering()`.
- `modules/http/src/spola/http/HttpServer.java`
  - **BRK-2:** `close()` is no longer `synchronized`. An `AtomicBoolean closing` CAS picks the first caller and a `CountDownLatch closed` is released when it finishes. A later call on a connection thread returns at once; a later call from any other thread waits on the latch. The work moved to `shutdown()`.
  - Joins are bounded (`joinUntil`). After the abort, `close()` waits at most `ABORT_GRACE_MILLIS` (1 s) and then logs a WARNING and returns, so a handler that ignores interrupts can no longer hang `close()`. The "aborting N connection(s)" warning no longer counts connections that are only lingering.
- Tests:
  - `MalformedRequestTests` has two new parameterized runs over all 14 BODY_CASES. In one the handler never reads the body (handler calls = 1). In the other it reads one byte and lets exceptions propagate. Both assert the exact status, `Connection: close`, a closed connection and no handler body.
  - `ChunkedTests`: the existing "ignoring handler, chunked over limit" case now expects 413 with close, where it used to expect the handler's 200. New test: streaming response plus a broken unread body gives the complete response, then the connection closes, and the pipelined request never reaches the handler.
  - `ShutdownTests` has four new tests: an outside and an in-handler `close()` running concurrently (the handler waits until `closing()` is true); a second outside `close()` waits for the first; a handler that ignores the interrupt does not keep `close()` from returning; a request in progress at `close()` with unread input in the kernel still gets its whole 200 KB response.
- Docs: `modules/http/README.md` (rejection paragraph, the `maxBodySize` and `shutdownTimeout` rows, graceful stop), `ARCHITECTURE.md` (new decisions "Oläst kropp läses före svaret" and "`close()` utan monitor", graceful stop revised) and `PROGRESS.md` (counts, known problems).

## Verification
- Resumed builder (this run): the working tree from the interrupted attempt was checked against the findings, and two full runs of `java Build.java` were both green, with every module OK:
  ```
  spola.guardrails 17/17, spola.test 77/77, spola.di 48/48, spola.config 68/68, spola.util 80/80,
  spola.codegen 41/41, spola.json 417/417, spola.http 171/171, spola.demo 43/43
  BUILD OK in 55372 ms   (exit=0)
  ```
  The breaker's reproductions were run again against the fresh jar: `Chunk.java` gives `Bad Request [EOF] handler calls = 1`, and `Deadlock.java` gives `close() returned within 10 s (shutdownTimeout 1 s): true`.
- Earlier interrupted attempt:
- `java Build.java` in `framework/` (last run):
  ```
  spola.http: OK - 171 tests, 171 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 12399 ms
  BUILD OK in 52991 ms   (exit=0)
  ```
  Two earlier full runs before the last test was added were green too (170/170 both times).
- The breaker's reproductions, run against the new `out/spola.http.jar`:
  - `Chunk.java`: `HTTP/1.1 400 Bad Request ... Connection: close ... Bad Request [EOF] handler calls = 1` (it was `200 OK`).
  - `Deadlock.java`: `close() returned within 10 s (shutdownTimeout 1 s): true` (it was `false`).
- I checked that the BRK-3 test discriminates. With the old condition put back temporarily (`linger && !aborted && !server.closing()`), it fails with `java.net.SocketException: Connection reset`. With the fix it passes. The temporary change was reverted and the final build above runs the real code.

## Addressed
- BRK-1 → server-side skip and validation of the unread body before the response head, with the body's status and `Connection: close` on failure. Exceptions are documented. BODY_CASES now also run with a non-reading handler and a partly-reading one.
- BRK-2 → monitor removed; CAS plus latch; a second call on a connection thread returns at once; bounded joins plus an abort grace period. A concurrent outside/in-handler test was added.
- BRK-3 → the server lingers during stop, bounded by the shutdown deadline. The test fails with the old rule.
- MNT-1 → `exchange` split up (see above).
- MNT-2 → `guarded` is `volatile`.
- MNT-3 → transition table in the `Connection.State` Javadoc.
- MNT-4 → one `closeQuietly` (`HttpServer.closeQuietly`, package-private).
- AUD-3 → not changed (optional; left as is, allowed per the judge).

## Rebuttals
None.

## Known gaps
- Because of BRK-3, `close()` can now wait for a lingering connection whose client keeps the socket open after `Connection: close`. That wait lasts until min(2 s, shutdownTimeout) and is visible in the tests as about 1–2 s for clients that hold the socket open.
- A handler that answers early without reading a large upload (for example 401) now gets its response sent only after the server has read the body, up to `maxBodySize`. This is documented in ARCHITECTURE and PROGRESS.
- After the 1 s abort grace period, an aborted handler thread that ignores interrupts is left running. `close()` logs a warning and returns.
- When the first `close()` caller is a handler, its own connection is not protected by the write guard after `close()` returns, because the guard stops at the end of `shutdown()`. This was the same in round 1.
