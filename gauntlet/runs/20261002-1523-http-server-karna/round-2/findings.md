# Round 2 — verdict: FAIL

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] `HttpServer.java:31-32,78` (the acceptor is the only non-daemon thread) together with `shutdown()` (~254-260: `closeQuietly(serverSocket)`, `acceptor.interrupt()`, `joinUninterruptibly(acceptor)` come first). When the first `close()` caller is a handler, or any other daemon or virtual thread, and `main` has already returned, the JVM exits as soon as the acceptor ends. That happens before in-progress requests have been answered and before the caller's own response goes out. This breaks the documented guarantees "keeps the JVM alive until close()", "lets requests in progress finish" and "a handler may call it too", and criterion 13. I reproduced it against the current `framework/out/spola.http.jar` (built 22:23, newer than every source file) using `round-2/scratch-breaker/exp/App.java` and `Cli.java`. Result: `/shutdown -> ` (empty), `/slow -> ` (empty), `app exit=0`, and the "stopped listening" line never appears. A `/shutdown` endpoint in an app whose `main` returns after `start()` is exactly the pattern the Javadoc invites, so this is ordinary use and MAJOR. The existing ShutdownTests miss it because the test JVM's main thread stays alive. — fix_hint: keep a non-daemon thread alive until `shutdown()` is fully done, including the caller's own connection writing its response. One way: the acceptor, or a dedicated non-daemon platform thread, waits on the `closed` latch and then on the caller's own connection (bounded by `shutdownTimeout` plus grace) before it exits. Another way: run `shutdown()` itself on a non-daemon platform thread. Add a child-JVM test where `main` returns, a handler calls `close()`, and both the in-progress request and the handler's own response arrive complete with `Connection: close`.

## Rejected
- (none)

## Duplicates
- (none)

## MINOR (optional for the builder)
- AUD-1 [MINOR] `HttpServer.shutdown()`: after the 1 s abort grace period, a handler thread that ignores interrupts is left running while `close()` logs a WARNING and returns. This is a deliberate, documented trade-off. Keep it documented.
- MNT-1 [MINOR] `Connection.java:185-202`: `body.skipRest()` is used for its side effect inside a `&&` chain and again as the return value of `exchange()`. Extract it into a named method such as `skipUnreadBodyOrFail(...)`, and document that `skipRest()` is idempotent.
- MNT-2 [MINOR] `HttpServer.java:256`: the `deadline | 1` sentinel ("never 0") is a bit trick. Derive "closing" from the `closing` flag instead.
- MNT-3 [MINOR] `Connection.lingerBeforeClose()` mixes half-close, the state transition and the drain loop, and its double-CAS condition is hard to read. Extract `enterLingering()`/`drainInput(deadline)`, and note in the State table that LINGERING is only reached when linger is set.
