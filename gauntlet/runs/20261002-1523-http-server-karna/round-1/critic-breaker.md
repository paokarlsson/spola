VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/http/src/spola/http/Connection.java (exchange: handler is called before the chunked body is parsed; BodyStream reads it lazily); MalformedRequestTests.java BODY_CASES
  problem: Criterion 5 says every malformed case gets exactly the expected status, `Connection: close`, and the handler is never called. It names chunk-size errors and a missing CRLF after chunk data explicitly. But for chunked-body errors the handler is called and runs its side effects, and if it doesn't read the body the client gets the handler's 200 with no `Connection: close` instead of 400.
  evidence: Scratch file /workspace/gauntlet/runs/20261002-1523-http-server-karna/round-1/scratch-breaker/exp/Chunk.java. Its handler only counts calls and returns `Response.text(200, "deleted\n")` without reading the body. Run with `java -cp /workspace/framework/out/spola.http.jar Chunk.java` and send "DELETE /item/1 HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n 5\r\nhello\r\n0\r\n\r\n" (chunk size with a leading SP, one of the cases criterion 5 lists). The output is "HTTP/1.1 200 OK / Content-Type: text/plain; charset=utf-8 / Content-Length: 8 / (no Connection: close) / deleted", then EOF, and "handler calls = 1". The tests hide this. Their handler always calls `request.bytes()`, and BODY_CASES only checks that the handler "never gets the body", not that it is never called (MalformedRequestTests.java, `refused(...)` and the "refused in the chunked body" test).
  fix_hint: When there is no `Expect`, validate the chunked framing before calling the handler. If that conflicts with lazy 100-continue, at least drain and validate the rest of the body before committing the handler's response, and answer 400/413 with `Connection: close` on a framing error. Otherwise the deviation has to be settled explicitly against the criterion; documenting it in a test Javadoc is not enough.

- id: BRK-2
  severity: MAJOR
  where: framework/modules/http/src/spola/http/HttpServer.java close() (`public synchronized void close()`, abort then `joinUninterruptibly(connection.thread())` around line 251/269)
  problem: If code outside the server calls close() (a shutdown hook or try-with-resources) while a handler also calls close() (the Javadoc says "A handler may call close() itself"), they deadlock. The handler thread waits forever on close()'s lock, which an interrupt cannot wake. The first close() aborts it after shutdownTimeout and then joins it with no time limit, so close() never returns and the shutdown timeout guarantee is broken.
  evidence: Scratch file /workspace/gauntlet/runs/20261002-1523-http-server-karna/round-1/scratch-breaker/exp/Deadlock.java. Settings: shutdownTimeout 1 s. The handler sleeps 300 ms, then calls `server.close()` (a /shutdown endpoint). Meanwhile the main code calls `server.close()` on another thread. Output: "WARNING: shutdown timeout: aborting 1 connection(s) still in progress", then "close() returned within 10 s (shutdownTimeout 1 s): false". The stuck thread is at HttpServer.joinUninterruptibly(HttpServer.java:269) <- HttpServer.close(HttpServer.java:251). In real use, a SIGTERM that arrives while a /shutdown request is running leaves the JVM hanging.
  fix_hint: Don't hold a monitor across the join. Use an AtomicBoolean or a CountDownLatch for "already closing": a second caller waits interruptibly or returns at once, and a caller on a connection thread never blocks behind the first close().

- id: BRK-3
  severity: MINOR
  where: framework/modules/http/src/spola/http/Connection.java closeSocket() (`if (linger && !aborted && !server.closing())`)
  problem: During a graceful stop, a request that is still running gets its `Connection: close` response, and then the socket closes right away with no linger. Any unread input (a pipelined request, or a body the handler didn't read) then makes TCP send a reset, which can destroy the response that was just written. The class comment itself names this risk (RFC 9112 §9.6).
  evidence: Code path. `closeSocket` skips `lingerBeforeClose()` whenever `server.closing()` is true. LINGER_MILLIS documents that closing with unread input sends RST and "can destroy the response before the client has read it".
  fix_hint: Linger also while closing, bounded by the time left of shutdownTimeout. closeIfIdle already handles the LINGERING state.
```

What I checked that held up: the build passes, and the full build passed 3 more times in parallel, with 138/138 http tests each time. Raw-socket checks covered pipelining with an unread Content-Length body, chunk extensions and trailers, `TE: gzip` (400), HEAD on HTTP/1.0, obs-text headers, CL+TE and TE obfuscation, the Content-Length and chunk-size overflow checks, and the header byte and count budgets. I also read through the line-reader edge cases, escaping in the logs (LoggedThrowable, Request.toString, Headers.toString), the response-splitting validation, the HEAD/204/304 framing, the write guard slices, the maxConnections semaphore, and the races between close() and idle or lingering connections. None of these turned up a defect.

The query-string characters `[]{}|` get 400. That is strict RFC 3986 and the same as Tomcat's default, so I'm not counting it as a defect.
