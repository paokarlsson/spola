# Spec: HTTP server part 2 — TLS (HTTPS) on top of spola.http

## Task
Add TLS to `spola.http`: HTTPS with PKCS#12 or PEM material (or a ready `SSLContext`), TLS 1.3/1.2 only,
ALPN `http/1.1` only, optional mTLS (NONE/WANT/NEED), handshake on the connection's virtual thread with a
total `handshakeTimeout`, `Request.tls()`/`Request.scheme()`, and unchanged HTTP semantics over TLS.
Full details, design decisions and requirements: `mission.md` in this directory (source `uppdrag/07b-http-tls.md`).
The "Krav" and "Designbeslut" sections of `mission.md` are binding.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/`

## Verification command
`java Build.java` in `framework/` (exit code 0).

## Acceptance criteria
1. `java Build.java` in `framework/` exits 0, and all 172 07a tests still pass with unchanged expectations.
2. The module's main code imports nothing from `java.lang.reflect`, `java.lang.invoke`, `sun.*` or `jdk.internal.*`, sets no system properties, uses no preview features, and `module-info.java` has no `requires` on other framework modules.
3. A test using `java.net.http.HttpClient` does `GET` and `POST` with a body over HTTPS against a server with PKCS#12 material and checks status, headers and body; the same test runs against servers with PEM material (RSA and EC). The client trusts only the test CA, and the server certificate's SAN `127.0.0.1` is verified (hostname verification is not disabled).
4. A test shows that a client allowing only TLS 1.1 or older gets no handshake; that TLS 1.3 and TLS 1.2 both work (negotiated protocol visible in `Request.tls()`); and that `TlsOptions` with `TLSv1.1` or an unknown protocol throws `IllegalArgumentException`.
5. A test shows ALPN: a client offering `h2,http/1.1` gets `http/1.1` (`SSLSocket.getApplicationProtocol()`); a client offering only `h2` gets a failed handshake; `HttpClient` with its default version (HTTP/2 preferred) works and gets HTTP/1.1.
6. A test shows mTLS: with `NEED`, the handshake fails without a client certificate and with a certificate not signed by the truststore's CA; with a valid client certificate the handler sees its chain in `Request.tls()`. With `WANT`, a request without a certificate succeeds and the chain is empty. With `NONE`, no client certificate is requested.
7. A table-driven test shows that bad material gives a clear exception when `TlsOptions` is created, and that neither the password nor the key bytes (Base64 or raw) appear in the message, the cause chain or `toString()`. Cases at least: missing file, wrong keystore password, unknown alias, keystore without a private key, PEM key not matching the certificate, encrypted PKCS#8, PKCS#1 (`RSA PRIVATE KEY`), PEM without `END` line, invalid Base64, empty certificate chain, and a file that is not PEM at all.
8. A test shows that the caller's password array is not modified and that `TlsOptions.toString()` does not contain the password.
9. A test shows `handshakeTimeout` with a short value: a client sending ClientHello one byte at a time (faster than any per-read limit) is disconnected after `handshakeTimeout`, and a client that opens TCP and sends nothing is disconnected after the same time. Meanwhile the server serves other clients (the acceptor thread is not blocked).
10. A test shows failed handshakes do not harm the server: plain HTTP to the TLS port, random bytes, an aborted handshake and a client that does not trust the server all give closed connections, and the next valid HTTPS request is served. Log lines for these cases contain no raw control characters.
11. A test runs a selection of 07a protocol tests over TLS: keep-alive (three requests on one TLS connection), pipelining, chunked in both directions, `Expect: 100-continue`, at least ten cases from the malformed-request table (same status and closed connection), and the smuggling attempt.
12. A test shows `writeTimeout` over TLS: a client that stops reading a large streaming response is disconnected and the handler's thread is freed. A test shows `maxConnections` where connections in handshake are counted.
13. A test shows graceful stop over TLS: idle TLS connections are closed, an in-flight request gets its full response with `Connection: close`, and `close()` returns.
14. A test runs 200 concurrent HTTPS clients (virtual threads, keep-alive, several requests each) and checks that every response is correct.
15. Test keys and certificates are created by the tests in a temporary directory using `keytool` and the `KeyStore` API; no keystores, certificates or keys are in the repo, and no internal JDK classes or `--add-exports` are used.
16. `modules/http/README.md` has the HTTPS section with PKCS#12, PEM and mTLS examples next to Spring Boot config, the `server.ssl.*` mapping table (all properties listed in mission.md) with deviations, and describes the renegotiation risk and `jdk.tls.rejectClientInitiatedRenegotiation`.
17. No annotations beyond the allowlist, no dependencies beyond the JDK (guardrails green), no new file types under `framework/`, no rule in `framework/REGLER.md` broken.
18. `PROGRESS.md` and `ARCHITECTURE.md` are updated, covering: TLS as an optional value in the options, when material is read and validated, PEM without the preview API, protocol and cipher defaults, ALPN, handshake on the connection thread with `handshakeTimeout`, `Request.tls()`/`scheme()`, mTLS, that the module sets no global properties, and how tests create their certificate material.

Additional binding constraints: the module's full test suite should still take at most about 45 seconds; tests bind only `127.0.0.1` port 0 and must not change global system properties (one client `SSLContext` per test).

## Out of scope
Certificate hot reload, SNI with multiple certificates, OCSP stapling/CRL beyond JDK defaults; encrypted PEM keys, PKCS#1, JKS as a first-class format; HTTP→HTTPS redirect and HSTS; HTTP/2, HTTP/3, WebSocket; wiring to `spola.config`/`spola.di`; routing and application request/response model; fixing 07a's known limitations unless needed for TLS.

## Assumptions
- The choice between `Optional<TlsOptions>` and a sealed `Transport` is the builder's, as long as it is motivated in ARCHITECTURE.md.
- `keytool` is available at `${java.home}/bin/keytool` in the build environment.
