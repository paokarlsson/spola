# Report — 20261002-1523-http-server-karna

- **Status:** PASS
- **Title:** HTTP server part 1: HTTP/1.1 on virtual threads with abuse limits
- **Mission source:** uppdrag/07a-http-server-karna.md (moved to uppdrag/klara/)
- **Rounds:** 3 of 5

## Rounds
| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 2 major, 6 minor) | green | ee0a922 |
| 2 | FAIL (0 blocker, 1 major, 4 minor) | green | 6f65214 |
| 3 | PASS (0 blocker, 0 major, 4 minor) | green | f65c082 |

The round-2 build step was interrupted and rerun in full by a fresh builder on resume. That builder found the earlier attempt's changes complete and verified them.

## What was fixed
- Round 1 BRK-1: a chunked body the handler left unread with broken framing used to get the handler's 200. The server now reads and checks the unread body before a non-streamed response and answers 400/413 with `Connection: close`.
- Round 1 BRK-2: a deadlock between an outside `close()` and an in-handler `close()`. Fixed with a CAS plus latch and no monitor, and joins are now time-limited.
- Round 1 BRK-3: the connection now lingers during a graceful stop, up to the shutdown deadline.
- Round 2 BRK-1: the JVM exited partway through `close()` when a handler called it after `main` had returned. A non-daemon keeper thread now holds the JVM until shutdown is done, and a child-JVM test covers it.
- The MINOR maintainability findings (splitting up `exchange`, `volatile guarded`, the state transition table, one `closeQuietly`).

## Remaining findings (MINOR)
- A server whose `close()` is never called keeps the JVM alive. This is intended and documented.
- The abort-and-await sequence is duplicated in `shutdown()` and `keepJvmAlive()`.
- The keeper's ordering assumption (`closed` is counted down only after the acceptor is joined) is documented in a single comment line.
- `shutdownDeadline` uses 0 as a sentinel with the `| 1` trick.
- An aborted handler thread that ignores interrupts is left running after the grace period (logged as a WARNING).
- `requires static java.net.http` exists only for the tests (allowed).

## Assumptions and deviations
- Invalid Transfer-Encoding gives 400, not the 501 the mission names, per RFC 9112 §6.3 (upheld in round 1).
- When a body error sits in the body itself (not the head), the handler may already have been called. This follows from the lazy-body design.

## Stash
–
