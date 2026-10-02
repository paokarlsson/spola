```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/http/src/spola/http/HttpServer.java:31-32, 78 (the acceptor is the only non-daemon thread), together with close()/shutdown() at about lines 225-300 ("a handler may call it too")
  problem: When a handler calls close() and is the first caller, the JVM can exit in the middle of the graceful stop. close() first joins the acceptor, which is the server's only non-daemon thread. The handler's thread and every connection thread are virtual, so they are daemon threads. Once main has returned (the pattern the docs suggest with "keeps the JVM alive until close()"), the JVM exits as soon as the acceptor ends. Requests still in progress, and the handler's own response, are then dropped with no response at all. That breaks close()'s documented guarantee "lets requests in progress finish" and criterion 13.
  evidence: Scratch files are in /workspace/gauntlet/runs/20261002-1523-http-server-karna/round-2/scratch-breaker/exp/.
    - App.java: main starts the server on port 18089 and returns. `/shutdown` calls `ref.get().close()` and then returns `Response.text(200,"bye")`. `/slow` sleeps for 1 s and returns 200.
    - Cli.java: sends `/slow`, waits 200 ms, then sends `/shutdown`, each on its own raw socket.
    - Result: `/shutdown -> ` (empty, EOF), `/slow -> ` (empty, EOF), `app exit=0`. The "stopped listening" log line never appears, so shutdown() never finished.
    - Control (App2.java): the same app plus one extra non-daemon platform thread that sleeps 8 s. Result: `/slow -> HTTP/1.1 200 OK ... Connection: close ... slow done` and `/shutdown -> HTTP/1.1 200 OK ... Connection: close ... bye`. This confirms the JVM exit is the cause.
    - The same thing happens when close() is called from any daemon or virtual thread, e.g. `Thread.ofVirtual().start(server::close)` inside a handler. The builder's ShutdownTests cover the in-handler close() only while the test's main thread is blocked, so they miss this.
  fix_hint: Keep a non-daemon thread alive until shutdown() has finished. For example, have the acceptor wait on the `closed` latch before it exits. Another option is to run shutdown() on a non-daemon platform thread and have it wait until the caller's own connection has written its response. Add a child-JVM test where main returns and a handler calls close().
```

What else I tried, with nothing found:
- **Build and flakiness:** three full `java Build.java` runs in parallel in fresh copies. All were green, with spola.http at 171/171 each time.
- **Default HttpClient:** a client with no version set (HTTP/2, so it tries an h2c upgrade) gets correct HTTP/1.1 answers for GET and POST.
- **Raw-socket unread-body cases:** these covered an unread Content-Length body, a partly read chunked body with extensions and trailers, an unread chunked body, `Expect: 100-continue` with and without the handler reading the body, HTTP/1.0 with an unread body, and GET with a chunked body. Each was followed by a pipelined request. All gave the right status and `Connection: close` behaviour.
- **Stop with a request in progress:** an HttpClient request still running at close() got its 200 with `Connection: close`, and close() returned in about 450 ms.
- **Raw client that keeps the socket open:** close() took 2.2 s because of the linger. That matches the documented known gap, so I am not reporting it.
- **Code review:** I read the shutdown CAS/latch logic, the state transitions, skipRest's handling of exceptions and when it sends 100 Continue, and the checks on response header characters (anything above U+00FF is refused). None of them showed a defect.
