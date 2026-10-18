# Round 1 build: 13a, client scheme and address from X-Forwarded-* (spola.web, spola.http)

## Changes

### spola.http (main)
- `Request.java`: new `remoteAddress()` (`InetSocketAddress`). Javadoc covers the TCP peer (under TLS: the TCP socket), that it is taken at accept, the same for every request on a connection, never null, no name lookup, and that behind a proxy it is the proxy's address (pointing to `ForwardedHeaders` in `spola.web`). Class Javadoc and `scheme()` now say that `scheme()`/`tls()`/`remoteAddress()` describe the connection.
- `Connection.java`: takes `socket.getInetAddress()` + `getPort()` once in the constructor (at accept) as `new InetSocketAddress(address, port)`, with no lookup, and passes it to `Request`. The log `peer` string now uses the same value and keeps the same text (`/127.0.0.1:port`).
- `HttpServer.java`: `new Connection(...)` moved inside the existing `try`. If a socket has no remote address (which should never happen after `accept`), it is closed and logged instead of killing the accept thread. The message changed from "starting a connection thread failed" to "starting a connection failed".
- `module-info.java`: Javadoc mentions `Request.remoteAddress()`. `requires` and `exports` are unchanged.

### spola.web (main)
- `ForwardedHeaders.java` (new, public final, implements `Filter`): `trusting(TrustedProxies)` throws NPE on null, and `toString()` is `ForwardedHeaders[<proxies>]`. The filter:
  - checks the trusted sender against `http().remoteAddress().getAddress()`; if the sender is not trusted, the request goes on unchanged;
  - reads `X-Forwarded-Proto`: exactly one field, `http` or `https`, folded in ASCII only;
  - walks `X-Forwarded-For` from the right over all fields, scanning backwards without splitting the whole list first;
  - throws `WebException(400, detail)`. The detail names the header but never the value. The filter logs nothing.

  The class Javadoc covers all six required topics.
- `TrustedProxies.java` (new, public `@FunctionalInterface`): `trusts(InetAddress)`, `privateNetworks()` and `of(String...)`. The Javadoc covers networks, literals, prefixes, mapped entries, the Docker gateway case, clients on trusted networks, and Tomcat's wider defaults.
- `ProxyNetworks.java` (new, package-private): the immutable implementation.
  - It collects every invalid entry into one `IllegalArgumentException`, one line per entry, each quoting the entry (escaped).
  - For bits set after the prefix, the message suggests the network (`10.0.0.1/8` gives "did you mean "10.0.0.0/8"?").
  - It rejects IPv4-mapped entries and requires a decimal prefix with no sign and no leading zero.
  - Matching compares 4- or 16-byte addresses, so each family matches only its own networks and zones are ignored.
  - `toString()` lists the entries as given.
- `IpLiteral.java` (new, package-private): the strict IPv4/IPv6 (RFC 4291 §2.2) reader over ASCII only. After the check, `InetAddress.ofLiteral` creates the address, and the address is used only if its bytes equal the ones the strict check computed (fail-closed if the JDK ever reads the text differently). It also has `format` (RFC 5952) for the suggestion messages.
- `WebRequest.java`: new `scheme()` and `clientAddress()`.
  - The router's existing 8-arg constructor now delegates with `http.scheme()` and `http.remoteAddress().getAddress()`, so `Router` is unchanged.
  - `withAttribute` keeps both values.
  - The new package-private `withClient(scheme, address)` is used only by `ForwardedHeaders` (a source test enforces this).
  - The class Javadoc has a new paragraph "The client behind a reverse proxy", and `http()` documents that it describes the connection.
- `module-info.java`: new bullet for `ForwardedHeaders`/`TrustedProxies`. `requires` and `exports` are unchanged.

### Tests
- `spola.http`: `RemoteAddressTests.java` (new). Criterion 9 is a transport-parameterized suite, registered in `HttpTests` with `Transport.PLAIN` and in `TlsTests` with `Transport.Tls.standard()` (registration lines only). There is also a source check (no `getByName`/`getAllByName`/`getHostName`/`getCanonicalHostName`, reflect or invoke in the main code), registered in `HttpTests`.
- `spola.web` (new files, registered once in `WebTests` via `ProxyTests`):
  - `WhoamiApp.java` is the need's code. The README shows it unchanged.
  - `ForwardedSupport.java` holds the shared invalid tables for criteria 5 and 6 and the helpers.
  - `ForwardedForTests.java` covers criteria 4 and 5.
  - `ForwardedProtoTests.java` covers criterion 6.
  - `ForwardedChainTests.java` covers criterion 7.
  - `TrustedProxiesTests.java` covers criterion 8.
  - `ForwardedConcurrencyTests.java` covers criterion 10.
  - `ForwardedLeakTests.java` covers criterion 11.
  - `ForwardedReadmeTests.java` covers criteria 3 and 13, using `HttpClient` for criterion 3.
  - `IpLiteralTests.java` checks the literal tables and runs a 20 000-address differential test against `ofLiteral`.
  - `ProxyTests.java` aggregates the suites.

  `SourceRulesTests` gets one new test: no name lookups in the main code, `withClient(` only in `ForwardedHeaders`/`WebRequest`, and `ofLiteral` used in `IpLiteral`.
- `spola.security.web`: `ForwardedSecurityTests.java` (new, criterion 12) plus one registration line in `SecurityWebTests`, before `ReadmeTests`/`LeakTests`.

### Docs
- `modules/web/README.md`:
  - an intro line;
  - 4 comparison rows;
  - the new section "## Bakom en omvänd proxy" between Sessioner and Validering. It has the Spring Boot code first (`server.forward-headers-strategy=native`, `getScheme()`, `getRemoteAddr()`), then `### Spola` with `WhoamiApp.java`, then bullets on: registering first, the trusted-sender rule, the walk from the right, the 400 cases, strict literals, `http()`, publishing only the proxy's port, the Docker gateway and `TrustedProxies.of(...)`;
  - 5 deviation rows: headers read, invalid values give 400, strict literals, `privateNetworks()` vs Tomcat `internal-proxies`, and `Secure` coming in 13b.
- `modules/http/README.md`: the `Request` row mentions `remoteAddress()`.
- `ARCHITECTURE.md`: new section "## Bakom en omvänd proxy (`spola.web`, `spola.http`)" with an API table and all the required decisions.
- `PROGRESS.md`:
  - new "Uppdrag 13a" entry: first part of `2026-10-13-proxyhuvuden`, 13b remains, security-critical, human review;
  - "Återstår" now lists 13b;
  - new "Kända problem" entry with every required limitation.
- `MONSTER.md`: the `spola.web` row mentions `server.forward-headers-strategy` (`RemoteIpValve`), `ForwardedHeaders` and `TrustedProxies`.
- Root `README.md`: the `spola.web` module row mentions proxy headers.

## Verification

Full build, run twice (the second after the final Javadoc and README edits):
```
cd framework && export PATH=/opt/jdk-25/bin:$PATH && java Build.java   -> exit 0
guardrails: no violations
spola.guardrails: OK - 17 tests
spola.test: OK - 77 tests
spola.di: OK - 48 tests
spola.config: OK - 68 tests
spola.util: OK - 80 tests
spola.codegen: OK - 41 tests
spola.json: OK - 417 tests
spola.http: OK - 290 tests, 290 ok        (was 283: +3 plain, +3 TLS, +1 source rule)
spola.validation: OK - 251 tests
spola.web: OK - 1083 tests, 1083 ok       (was 823: +259 under "behind a reverse proxy", +1 source rule)
spola.security: OK - 305 tests
spola.security.web: OK - 133 tests, 133 ok (was 130: +3)
spola.demo: OK - 43 tests
BUILD OK in 297188 ms
```
Module boundaries: `git diff` of both `module-info.java` files shows no `requires` or `exports` lines changed, and `Build.java` is not in the diff. Under `src/` and `test/`, the only changes outside `web` and `http` are the new `ForwardedSecurityTests.java` and one line in `SecurityWebTests`.

Mutation check (scratch runner, each file restored afterwards and verified with `diff`). The web suite catches every mutation:
- trust check on the sender removed: 3 failures
- element trust inverted: 26
- `withAttribute` drops the scheme: 1
- IPv4 leading zeros allowed: 10
- several `X-Forwarded-Proto` fields accepted: 3
- partial-byte prefix mask ignored: 3
- "all trusted" returns the sender instead of the leftmost element: 7

Probes during the work:
- JDK 25 `ofLiteral` turns `::ffff:x` into `Inet4Address`.
- `HttpClient` sends header values as ASCII (0xA0 becomes `?`), so obs-text cases go over a raw socket. The criterion 3 test asserts that exactly the 4 non-ASCII cases took that path.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Deviations from the mission's decisions (documented in ARCHITECTURE.md where relevant)
- `WhoamiApp.router(TrustedProxies proxies)` takes the proxies as a parameter instead of calling `TrustedProxies.privateNetworks()` in the filter line. Criterion 3 requires "samma router med `TrustedProxies.of("10.0.0.0/8")`". `main` passes `privateNetworks()` with the need's comment, and the filter, the route and the handler lines are the need's code.
- `TrustedProxies.of` reports every invalid entry at once, one line each (MONSTER.md: "alla fel rapporteras på en gång"). With one bad entry the message is a single line that quotes it.
- `IpLiteral.address` also compares the `ofLiteral` bytes with the strict reading. This is fail-closed defence in depth on top of the decision "skapas först efter kontrollen, med `InetAddress.ofLiteral`".
- `X-Forwarded-Proto` is checked before `X-Forwarded-For`. If both are invalid, the 400 names `X-Forwarded-Proto`.
- `HttpServer` accept loop: `Connection` is now created inside the `try` (see Changes). This is a small robustness change in `spola.http`, which is in scope.

## Known gaps
- Two fail-closed branches cannot be triggered from a test: `IpLiteral.address` returning null when `ofLiteral` refuses or reads differently, and `Connection` throwing for an accepted socket without an address. Both are defensive.
- The Tomcat and Spring Boot facts in the README deviation rows come from Tomcat docs (internal-proxies includes 169.254/16 and 100.64/10) and from my knowledge of Spring Boot's `TomcatWebServerFactoryCustomizer` (host and port headers set with `native`). They are worded conservatively: "avvisar aldrig förfrågan" rather than claiming what Tomcat makes of `unknown`.
- As in the existing web tests, each `Support.serve` makes `HttpServer` print INFO start and stop lines to stderr. The table suites share one server each to keep this down.
