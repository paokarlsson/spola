# Round 1 — build

There were no half-finished changes: at the start `git status` showed only the run directory and `uppdrag/07b-http-tls.md`.

## Changes

### Main code (`framework/modules/http/src/spola/http/`)
- **New public types**
  - `TlsOptions`: a record of identity, clientAuth, `Optional<TlsTrust>`, protocols and cipherSuites.
    - Factories: `pkcs12(path, pw[, alias])`, `pem(chain, key)`, `of(SSLContext)` and `of(TlsIdentity)`.
    - Copy methods: `withProtocols`, `withCipherSuites` and `withClientAuth`.
    - Validation:
      - Protocols: only TLSv1.3 and TLSv1.2 are accepted. Anything older or unknown throws `IllegalArgumentException`.
      - Cipher suites: each one must be supported by the JDK and not weak, and at least one must fit the chosen protocols.
      - The clientAuth/trust combination is checked.
    - `toString()` never shows a password.
  - `TlsIdentity`: the server key and chain from PKCS#12 or PEM, or an `SSLContext`. The material is read and checked when the object is created:
    - the key is checked against the certificate with a sign/verify round trip;
    - the chain order is checked;
    - the password is copied and the copy is zeroed after use;
    - the key ends up in an in-memory keystore read by a SunX509 `KeyManagerFactory`.
  - `TlsTrust`: trusted certificates for client authentication, from PKCS#12 trusted entries or PEM.
  - `ClientAuth`: NONE, WANT or NEED.
  - `TlsInfo`: protocol, cipher suite and the client's certificate chain.
  - `TlsMaterialException`: extends `IllegalArgumentException` and has a `file()` accessor.
- **New internal classes**
  - `Pem`: a strict RFC 7468 reader that does not use the preview PEM API.
  - `TlsMaterial`: loads PKCS#12, certificates and PKCS#8 keys (RSA, EC, EdDSA), runs the checks and builds the JSSE managers. PKCS#1, SEC1 and encrypted keys are rejected with an `openssl pkcs8 -topk8 -nocrypt` hint.
  - `TlsLayer`: builds the server's `SSLContext` and `SSLParameters` once per server (protocols, suites, clientAuth, ALPN `http/1.1`) and layers a server-mode `SSLSocket` over an accepted socket.
- **Changed**
  - `HttpServerOptions`: new 5th component `Optional<TlsOptions> tls`, plus `withTls` and `withoutTls`. The old 4-argument constructor is kept.
  - `HttpTimeouts`: new 6th component `handshakeTimeout` (default 10 s) and `withHandshakeTimeout`. The old 5-argument constructor is kept and uses the default.
  - `Connection`:
    - The handshake runs on the connection's own virtual thread. Its deadline is accept time plus `handshakeTimeout`: each read waits at most the remaining time, and the guard thread closes the socket when the deadline passes.
    - A failed handshake is logged at DEBUG, escaped.
    - On graceful stop, a TLS connection is woken with `shutdownInput`. Its own thread then sends `close_notify`.
    - The TLS `shutdownOutput`/`close` calls run under the write guard. Aborts close the TCP socket underneath.
  - `GuardedOutput.guard(...)`: runs a blocking close as a watched write.
  - `HttpServer`: holds the `TlsLayer`, sets the guard period from the smaller of `writeTimeout` and `handshakeTimeout`, and checks handshake deadlines.
  - `Request.scheme()` and `Request.tls()`: added.
  - `module-info.java`: Javadoc only, no new `requires`.

### Tests (`framework/modules/http/test/spola/http/`)
- `TestPki` creates all certificate material once per run with `keytool` in a temp directory, in two parallel rounds that use `-genkeypair -signer ca`. The `KeyStore` API then splits the keystores and writes the PEM files.
  - Material: a CA, servers with RSA, EC and Ed25519 keys (SAN 127.0.0.1 and localhost), a client certificate, a self-signed rogue certificate, and a server certificate for another name.
  - Client contexts are created per test, plus a "forcing" key manager so that an untrusted client certificate is really sent.
- `Transport` (plain or TLS) and `Support.Client.over(socket)`. `closedByServer` and `rest` now also treat an `SSLException` as a closed connection; plain sockets never throw it.
- These 07a tests now take a transport parameter. The plain run is unchanged: same suite names and same expectations.
  - `ConnectionTests`, `ChunkedTests`, `ExpectTests`
  - `MalformedRequestTests.selection(t, step)`: every 3rd case of both tables (24 head cases and 5 body cases) plus smuggling
  - `ShutdownTests.inProgressFinishes(t)`
- New test classes:
  - `TlsMaterialTests`: the 31-case bad-material table, password handling and toString, a PEM CRLF/explanatory-text case, and validation of protocols, cipher suites, clientAuth and the options.
  - `TlsHandshakeTests`: protocols (including raw TLS 1.1, 1.0 and SSLv3 ClientHellos), explicit cipher suites, ALPN, mTLS with NEED, WANT and NONE, `handshakeTimeout`, failed handshakes and their log lines, `maxConnections` during handshake, and absolute-form over TLS.
  - `TlsServerTests`: `HttpClient` GET and POST against six kinds of material, a hostname-check negative case, the 07a selection over TLS, `writeTimeout`, graceful stop, and 200 concurrent clients.
  - `TlsTests`: the root suite, with a beforeAll that creates the material and an afterAll that deletes it.
- `SourceRulesTests` has one new test: no `System.setProperty`, `Security.setProperty` or `clearProperty` calls, and no `sun.`, `jdk.internal` or `com.sun.` imports in the main code.

### Docs
- `modules/http/README.md` has a new HTTPS section with:
  - PKCS#12, PEM (Let's Encrypt) and mTLS examples next to the matching Spring Boot configuration;
  - a table mapping every `server.ssl.*` property listed in the mission to `TlsOptions`, with deviations (plus `key-password`, `bundle` and `http2`);
  - the renegotiation risk and `jdk.tls.rejectClientInitiatedRenegotiation`.

  The API table was also updated.
- `ARCHITECTURE.md` has a new section "HTTP-server del 2: TLS" covering every decision listed in criterion 18. It explains why the TLS setting is an `Optional` and not a sealed `Transport`.
- `PROGRESS.md` has a 07b entry, the remaining-work list now starts at 08, and there are new known limits for HTTPS.

## Verification

I ran `java Build.java` in `framework/` three times on the final code (the first time before the last source-rules test was added).

```
run 1 exit 0
guardrails: no violations
spola.http: OK - 281 tests, 281 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 24898 ms
BUILD OK in 73999 ms
run 2 exit 0
guardrails: no violations
spola.http: OK - 281 tests, 281 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 25968 ms
BUILD OK in 68588 ms
```

- **Test split:** 173 tests sit outside the `TLS` suite (the 172 from 07a plus the new source-rules test), and 108 sit inside it.
- **Module time:** about 25–30 s, under the ~45 s limit (07a alone took ~17 s).
- **Cleanup:** no `/tmp/spola-http-tls-*` directories are left after a run.

Behaviours I checked empirically against JDK 25 before relying on them:
- Closing an `SSLSocket` from another thread is fast.
- A raw `shutdownInput` wakes a blocked TLS read.
- A read timeout leaves a layered `SSLSocket` usable.
- `shutdownOutput` works for both TLS 1.2 and 1.3.
- WANT with an untrusted certificate that is actually sent fails the handshake. In the first run this test seemed to show the opposite, but the JDK's client key manager had simply not sent a certificate whose issuer the server did not name. The test now forces the certificate to be sent.

## Addressed
Round 1, so there were no findings to address.

## Rebuttals
None.

## Known gaps
- The handshake timeout is enforced by the guard thread. Its period is a quarter of min(`writeTimeout`, `handshakeTimeout`), between 5 ms and 1 s, so the cut-off can come up to one period late. The tests allow 750–2500 ms for an 800 ms timeout.
- On graceful stop, a TLS connection is woken with `shutdownInput`. On a platform where that does not wake a blocked read, the connection only ends at `shutdownTimeout` (documented in PROGRESS.md).
- The PEM text read as a `String` cannot be zeroed; the decoded bytes and buffers are.
- The malformed-request selection over TLS runs every 3rd case (29 cases plus smuggling). The criterion asks for at least 10, so this was chosen to keep the time budget.
- A plain-HTTP client on the TLS port gets a closed connection, not an HTTP 400.
- Chain-order checking compares names only; a chain with extra cross-signed certificates out of issuer order is rejected.
