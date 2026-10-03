# Round 2: build

## Changes
- `modules/http/src/spola/http/ConnectionInput.java`: each `fill()` now publishes a volatile `readDeadline`. In header mode that is the header deadline; otherwise it is now + the per-read timeout. New methods `overdue(now)`, `cutOff()` and `wasCutOff()`. When the guard has cut the socket, the read's `IOException` is rethrown as `SocketTimeoutException`, so the rest of the 07a code paths stay as they were. The inline `java.util.Arrays` is now imported. The class Javadoc explains why `soTimeout` alone is not enough over TLS. (BRK-1)
- `Connection.java`: new `checkRead(now)` for the guard. It only acts on TLS connections (`ssl != null`). When the read is overdue it marks the input as cut and closes the TCP socket underneath. `in` and `ssl` are now volatile because the guard reads them. In `closeSocket()`, the `guarded` field is null-checked and `close_notify` is skipped after a cut. `connectionEnded` now runs in a nested `finally` (BRK-2). The handshake sentinel `deadline | 1` is replaced by an explicit `handshaking` flag (MNT-2).
- `HttpServer.java`: the guard calls `checkRead`. With TLS, its period is a quarter of the shortest of the write, handshake, header, idle and read timeouts (clamped to 5 ms–1 s). (BRK-1)
- `Pem.java`: Base64 is now strict per RFC 7468. The block length must be a multiple of 4 (padded); otherwise the error is "is not valid Base64". (AUD-1)
- `TlsMaterialTests.java`: comment on why the `badLength` mutation is now always invalid. New case "unpadded Base64" (`AQIDBA==` with the `=` stripped). Updated the expected message for "key block that is not a key". (AUD-1, BRK-3)
- `TlsMaterial.java`: the unsupported-key message now reads "cannot be read as an unencrypted PKCS#8 RSA, EC, Ed25519 or Ed448 private key (other key types, such as RSASSA-PSS, are not supported)" (BRK-3). `Collections` is now imported (MNT-3).
- `TlsOptions.java`: comments saying that the weak-suite list and the TLS 1.3 check rely on the JSSE/IANA standard names, with examples (MNT-1).
- `TlsHandshakeTests.java`: new test "header, idle and linger timeouts are totals over TLS: a record sent one byte at a time is cut off". It uses a `DribbleSocket` under a client `SSLSocket`: the handshake runs at full speed, then the request record is sent one byte every 20 ms (about 6 s for the whole record). The connection must be cut at `headerTimeout` (800 ms), at `idleTimeout` (800 ms, after a first request at full speed), and at `LINGER_MILLIS` after a `Connection: close` response.
- `ARCHITECTURE.md`, `PROGRESS.md`, `modules/http/README.md`: documented the TLS read deadlines and their two consequences (no 408 on a TLS cut; a whole record must arrive within `readTimeout`), strict PEM padding, and 32 bad-material cases.

## Verification
- `java Build.java` (final run, after all edits):
  ```
  spola.http: OK - 283 tests, 283 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 29589 ms
  ... all modules OK ...
  BUILD OK in 73892 ms   (exit 0)
  ```
- Flakiness check: the full build ran 3 more times in a row. Every run gave exit 0, 283/283 in spola.http, `[17] invalid Base64 length` ok, and the new TLS timeout test ok in about 4.36 s each time.
- Negative check: I disabled `connection.checkRead(now)` in the guard temporarily. The new test then FAILED with `the server read the whole record sent one byte at a time (19668 ms)`. I restored the change afterwards, and the guard has exactly one `checkRead` call again.
- AUD-1 mutation stress: 60 fresh RSA-2048 PKCS#8 keys, each with the same one-character drop, run through `TlsOptions.pem`. Result: `60/60 rejected as not valid Base64 (DER length multiple of 3: 22)`. Those 22 are the keys the old reader could accept.
- Breaker's `scratch-breaker/exp/Slow.java` against the new jar (150 ms gap, headerTimeout=1s):
  ```
  plain: after 1022 ms: HTTP/1.1 408 Request Timeout
  tls:   after 1095 ms: EOF          (round 1: 200 OK after 9638 ms)
  ```

## Addressed
- AUD-1 [BLOCKER]: `Pem` now requires padded Base64 (length a multiple of 4), so a dropped character is always reported as "not valid Base64". Added a deterministic "unpadded Base64" case. Shown stable over 4 builds and 60/60 random keys.
- BRK-1 [MAJOR]: the guard now enforces absolute read deadlines over TLS (header, idle, body read, linger) by closing the TCP socket under an overdue read. Covered by a TLS test that dribbles a record byte by byte; the test fails without the fix.
- BRK-2 [MINOR]: null-check on `guarded` in `closeSocket`, and `connectionEnded` in a nested `finally`.
- BRK-3 [MINOR]: the message is now accurate and names RSASSA-PSS as unsupported.
- MNT-1 [MINOR]: comments on the naming dependency, with examples.
- MNT-2 [MINOR]: explicit `handshaking` flag instead of `| 1`.
- MNT-3 [MINOR]: imported `Collections`, and `Arrays` in ConnectionInput too.
- MNT-4 [MINOR]: not done. The transport branching is still a few `ssl != null` checks, and the finding itself says to refactor only if more transport behaviour is added.

## Rebuttals
- (none)

## Known gaps
- Over TLS, a connection cut at `headerTimeout` gets no `408`, because the socket is closed under the TLS layer and nothing can be written. Plain HTTP still gets `408`. This is documented in README, ARCHITECTURE and PROGRESS.
- Over TLS, a whole record (at most 16 KiB) must now arrive within `readTimeout`, not just one byte of it. With the 30 s default that means less than about 5 kbit/s. This is documented.
- How precisely a deadline is enforced depends on the guard period (a quarter of the shortest timeout, at least 5 ms, at most 1 s).
