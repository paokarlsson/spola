```
VERDICT: PASS
FINDINGS: none
```

I couldn't break the round-3 fix. Here is what I tried:

- **Build:** I copied `framework/` to `/workspace/gauntlet/runs/20261002-1523-http-server-karna/round-3/scratch-breaker/fw`, deleted its `out/` and ran `java Build.java`. Exit code 0, with spola.http at 172/172 including the new child-JVM test.
- **Last round's MAJOR (round-2 BRK-1), three ways:** `exp/App3.java` with `Cli.java` in `round-3/scratch-breaker/exp/` runs an app whose `main` returns right after `start()`. `/slow` sleeps 1.5 s, and `/shutdown` stops the server in one of three ways:
  - (a) calls `close()` directly
  - (b) starts a virtual thread that calls `close()` and returns at once
  - (c) starts that virtual thread and waits 100 ms for it

  In all three, `/slow` returned `200 ... Connection: close ... slow done` and `/shutdown` returned `200 ... Connection: close ... bye`. The app exited 0 and logged "stopped listening".
- **One run failed, not because of the server:** my first run of (a) died with "module not found: spola.http". Another process was rebuilding `framework/out` at that moment. The rerun passed as described above.
- **Reading the keeper code (`HttpServer.java:85, 108-115, 327-352`):** I looked for ways it could keep the JVM alive forever or leave without waiting:
  - Connections that `close()` already aborted are skipped.
  - Connections that ended are removed through `connectionEnded`.
  - When `close()` is called from `main` (try-with-resources), the keeper ends right away.
  - The rollback when `start()` fails is safe with threads that never started (joining an unstarted thread returns at once).
  - The keeper waits at most until the shutdown deadline (at least 1 s), then aborts and waits 1 s more.

  I found nothing wrong.
- **Connection refactor:** `enterLingering()` does the same as the old pair of compare-and-sets, and `skipUnreadBodyOrFail` keeps the same conditions as before.
- **Parser probe (`exp/Probe.java`, 50 raw-socket requests):** all statuses matched RFC 9112/9110. The cases covered:
  - Transfer-Encoding: `Chunked`, `chunked,`, `,chunked`, quoted, empty, and two TE headers
  - Content-Length: `03`, `3,3`, empty, and a full-width digit
  - chunked bodies: extensions with a quoted string, trailers (one of them a `Content-Length`), a bare LF inside the chunked framing, a size with a trailing space, and a long run of zeros
  - targets and versions: absolute form, `OPTIONS *`, `CONNECT`, the `PRI` preface, HTTP/0.9, 1.2, 3.0 and 1.10, a leading empty line, a fragment, and non-ASCII in the target
  - Host values: empty, `a b`, `a@b`, `a/b`
  - other headers: DEL, obs-text and a tab in a value, a non-ASCII field name, an h2c upgrade request, and HTTP/1.0 without Host

The only weakness I saw is the one already ruled MINOR in round 1 (BRK-3). If a handler calls `close()` after the shutdown deadline has passed, its own connection gets no linger time, so in theory a TCP reset could destroy its response. That needs pipelined input at exactly that moment, so I am not raising it again.
