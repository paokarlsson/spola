VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/http/src/spola/http/ConnectionInput.java:57-72 (deadline turned into a per-read soTimeout), used by Connection.java:190 (`in.deadline(... headerTimeout ...)`) over the `SSLSocket`
  problem: Over TLS, `headerTimeout` no longer limits the total time, so a slowloris client can hold a connection and a `maxConnections` permit for hours. The deadline is only checked between `in.read()` calls. One `SSLSocket.read()` blocks inside the JDK until a whole TLS record has arrived, and every underlying TCP read gets the full remaining soTimeout again. A client that sends one encrypted record a byte at a time, each byte inside that timeout, is never cut off. The mission requires that limits and timeouts "fungerar som i 07a över SSLSocket", and this is attacker input on an HTTP server (REGLER §4). The same cause defeats the total for the idle wait and for the linger drain (`LINGER_MILLIS`).
  evidence: The scratch program `/workspace/gauntlet/runs/20261002-2244-http-tls/round-1/scratch-breaker/exp/Slow.java` uses `headerTimeout=1s` and `readTimeout=1s`. After the handshake, the client's socket writes `GET / HTTP/1.1\r\nHost: x\r\n\r\n` one byte every 300 ms.
    `java -p fw/out/spola.http.jar --add-modules spola.http Slow.java pki2 plain` → `after 1018 ms: HTTP/1.1 408 Request Timeout`
    `java ... Slow.java pki2 tls` → `after 19242 ms: HTTP/1.1 200 OK` (the request was served 19 s after the 1 s header deadline). A 16 KiB record with byte gaps just under the timeout keeps the connection for many hours.
  fix_hint: Enforce the header deadline (and the linger and idle totals) for TLS connections from the guard thread, the way `checkHandshake` does: close the TCP socket when the deadline passes instead of relying on soTimeout. Add a TLS test that dribbles one record slowly.

- id: BRK-2
  severity: MINOR
  where: framework/modules/http/src/spola/http/Connection.java:114-115 and :429 (closeSocket) with :124-125
  problem: The handshake can succeed and then have its socket closed or shut down before `in`/`guarded` are created. This happens if `closeIfIdle()` runs `shutdownInput`, or if `checkHandshake`/`abort` closes the socket right at that moment. `io.getInputStream()` or `getOutputStream()` then throws, and `closeSocket()` runs with `ssl != null` and `guarded == null`. `guarded.guard(ssl::close)` then throws an NPE from the `finally` block, so `server.connectionEnded(this)` is skipped. The `maxConnections` permit leaks, the connection stays in the list, and an uncaught NPE is printed.
  evidence: This comes from reading the code; the race window is too narrow to reproduce reliably. For a closed or input-shut-down socket, `SSLSocketImpl.getInputStream()` throws "Socket is closed" or "Socket input is already shutdown", so the IOException catch is reached with `guarded` still null.
  fix_hint: In `closeSocket`, check `guarded != null`, or create the streams before setting `ssl`. Also call `connectionEnded` in a nested `finally`.

- id: BRK-3
  severity: MINOR
  where: framework/modules/http/src/spola/http/TlsMaterial.java:150-160 (KEY_ALGORITHMS = RSA, EC, EdDSA)
  problem: An unencrypted PKCS#8 RSASSA-PSS key (from `openssl genpkey -algorithm RSA-PSS`) is rejected with a message that is wrong: it says the file "is not an unencrypted PKCS#8 RSA, EC, Ed25519 or Ed448 private key".
  evidence: `scratch-breaker/exp/Mat.java` with `ossl/pss.key` and `ossl/pss.pem` → `FAIL pss -&gt; TlsMaterialException: private key .../pss.key is not an unencrypted PKCS#8 RSA, EC, Ed25519 or Ed448 private key`
  fix_hint: Add "RSASSA-PSS" to the key algorithms and the signature check, or name the unsupported algorithm in the message.

Checked with no defect found:
- Idle-timeout and graceful-stop closes over TLS 1.2 and 1.3: `close()` took 2–5 ms.
- Responses with `Connection: close` and unread or pipelined bodies over TLS 1.2 and 1.3: 40 of 40 were complete.
- A client half-closing (`close_notify`) after its request still gets the full response.
- PKCS#12 files from OpenSSL 3 load (default, empty password, `-legacy`), as do PEM fullchains and Ed448 keys.
- An OpenSSL truststore gives a clear error message.
- TLS 1.2 client-initiated renegotiation is allowed, as README documents.
- Main code: no reflection, `invoke`, system properties or internal imports.
- README maps all eleven `server.ssl.*` properties.
- No key material is in the repo; tests create it with `keytool`.

All experiment files are under `/workspace/gauntlet/runs/20261002-2244-http-tls/round-1/scratch-breaker/`.
