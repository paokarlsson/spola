# Round 3 — build

## Changes
- `framework/modules/http/src/spola/http/HttpServer.java`: new non-daemon platform thread
  `spola-http-keeper-<port>` (`keepJvmAlive`). It waits on the `closed` latch, which means until
  `close()` has fully returned. It then waits for the connections that `close()` did not wait for
  (the one whose handler called `close()`). That wait lasts until the shutdown deadline, but at
  least `ABORT_GRACE_MILLIS` after `close()` returned. Whatever is left after that is aborted and
  given `ABORT_GRACE_MILLIS` more. Connections that `close()` already aborted are skipped. The
  acceptor is now a daemon, so the keeper is the server's only non-daemon thread. `start()` closes
  the server if a thread fails to start, so a keeper is never left behind. Updated the class and
  `close()` Javadoc. Added a comment on the `deadline | 1` sentinel (MNT-2).
- `framework/modules/http/src/spola/http/Connection.java`: added `aborted()`. Extracted
  `skipUnreadBodyOrFail(...)` with a documented note that `skipRest()` is idempotent (MNT-1). Split
  `lingerBeforeClose()` into `enterLingering()`, `lingerDeadline()` and `drainInput(deadline)`.
  The State table now says LINGERING is reached only after a `Connection: close` response (MNT-3).
  The behaviour is unchanged.
- `framework/modules/http/test/spola/http/ReturningMainProbe.java` (new): the child-JVM app. Its
  `main` returns right after `start()`. `/slow` sleeps 1 s, `/shutdown` calls `close()` from the
  handler, and the app prints `port N` and `entered <path>`.
- `framework/modules/http/test/spola/http/ShutdownTests.java`: new test "main returns after start()
  and a handler stops the server: the JVM waits for every response". It starts the probe in a child
  JVM, sends `/slow`, waits for `entered /slow`, then sends `/shutdown`. It asserts that both
  responses arrive complete with `Connection: close`, that the child exits 0 within 15 s, and that
  its output contains "stopped listening".
- `framework/PROGRESS.md`, `framework/ARCHITECTURE.md` (new decision "En keeper-tråd håller JVM:en
  igång"; acceptor described as a daemon), `framework/modules/http/README.md`: documentation updated.

## Verification
- `java Build.java` in `framework/`: `spola.http: OK - 172 tests, 172 ok, 0 failed ...`,
  `BUILD OK in 50005 ms` (exit 0). The new test:
  `ok      main returns after start() and a handler stops the server: the JVM waits for every response (3506 ms)`
- Regression check: I temporarily put back the HEAD (round 2) `HttpServer.java` and ran the build.
  The new test fails as the breaker described:
  `FAILED  main returns after start() ... java.io.EOFException: connection closed after ""`
  (`spola.http: FAILED - 172 tests, 171 ok, 1 failed`). I then restored the new file and rebuilt
  green (above).
- I ran the breaker's own repro (`round-2/scratch-breaker/exp/App.java` + `Cli.java`) against the
  new `out/spola.http.jar`:
  ```
  /slow -> HTTP/1.1 200 OK | ... | Connection: close |  | slow done
  /shutdown -> HTTP/1.1 200 OK | ... | Connection: close |  | bye
  INFO: stopped listening on port 18089
  app exit=0
  ```

## Addressed
- BRK-1 [MAJOR]: added a keeper thread (non-daemon) that outlives `close()` and the calling
  handler's connection, bounded as described above. Covered by a child-JVM test that fails on the
  old code and passes now.
- AUD-1 [MINOR]: the trade-off stays documented (`close()` Javadoc, ARCHITECTURE "Mjukt stopp").
  The keeper follows the same rule: after the abort plus grace it stops waiting, and a stuck virtual
  thread does not keep the JVM alive.
- MNT-1 [MINOR]: extracted `skipUnreadBodyOrFail`, and its Javadoc documents that `skipRest()` is
  idempotent.
- MNT-2 [MINOR]: partly done. I kept the sentinel and explained it in a comment.
  Deriving the state from `closing` instead would be racy, because `closing` is set (by the CAS)
  before the deadline is written. A connection reading `closing == true` together with deadline 0
  would cut its linger to 0 and could reset the response.
- MNT-3 [MINOR]: extracted `enterLingering()`, `lingerDeadline()` and `drainInput(deadline)`, and
  noted in the State table that LINGERING happens only after `Connection: close`.

## Rebuttals
- (none)

## Known gaps
- A started server whose `close()` is never called keeps the JVM alive through the keeper. That is
  intended and documented, as with Tomcat.
- After `shutdown()` has stopped the write guard, a slow client of the handler's own connection is
  limited by the keeper's abort (deadline / 1 s minimum), not by `writeTimeout`.
