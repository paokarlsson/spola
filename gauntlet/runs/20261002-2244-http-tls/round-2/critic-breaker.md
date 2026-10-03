```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/http/src/spola/http/ConnectionInput.java:96-117 (readDeadline = the same instant as soTimeout) together with Connection.java checkRead and HttpServer.java:250-262 (guard period)
  problem: Over TLS, a client that simply stops sending loses its 408 now and then. The soTimeout and the guard's cut-off are armed for the same instant. When the guard's tick happens to land before the soTimeout wakeup, it closes the TCP socket under the read, so the 408 (header timeout, or body readTimeout) cannot be written. Plain HTTP always sends the 408, and the docs only describe losing it for clients that drip bytes slowly, not for plain stalls.
  evidence: round-2/scratch-breaker/exp/Stall.java, run against the current framework/out/spola.http.jar with headerTimeout = readTimeout = 100 ms. Each run sends a partial header, or a POST with Content-Length: 10 and only 3 body bytes, then waits. Over TLS the header case got 408 on 295 of 300 runs ("run 109: null" ...), and the body case got 408 on 291 of 300. With 200 ms timeouts, all 30 runs got 408 over both plain and TLS. The gap depends on timing, and with the default timeouts (guard period 1 s) it happens less often. The handler still sees RequestBodyException 408 every time; only the response to the client is lost. The same overlap also lets the guard cut a read that returned just before its deadline, while `readDeadline` is not yet cleared.
  fix_hint: Give the guard some slack (for example, treat a read as overdue only after deadline + period, or + about 50 ms), so that the soTimeout always fires first for a plain stall and the guard only catches reads that have run past it.
```

**What I tried beyond that:**
- **Build:** ran `java Build.java` twice on a copy of `framework/`. Both exited 0 with 283/283 in spola.http, in about 29 s each.
- **Slow-sender cases:** the round-1 slow-sender bug (BRK-1) is fixed for the header (the builder's test). I also checked a request body sent one byte every 20 ms with readTimeout 500 ms (`exp/BodyDribble.java`). The handler got a 408 `RequestBodyException` after 576 ms and the connection was cut at about 500 ms.
- **Code review:**
  - **Connection.java:** the null-safety of `closeSocket` and `checkRead` (`in` and `guarded` are only read when `ssl` and `output` are non-null), the nested `finally`, and the `handshaking` flag.
  - **HttpServer.java:** how the guard period is computed and clamped.
  - **HttpTimeouts.java:** timeouts near the maximum cannot overflow the deadline.
  - **Pem.java:** strict padding and `clear()` on the reject path. CRLF and trailing blanks are still accepted.

Nothing found at MAJOR or above.

Experiment files are in /workspace/gauntlet/runs/20261002-2244-http-tls/round-2/scratch-breaker/exp/ (Stall.java, BodyDribble.java).
