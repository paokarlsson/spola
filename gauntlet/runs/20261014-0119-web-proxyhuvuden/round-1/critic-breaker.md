```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/web/test/spola/web/WhoamiApp.java:24-28 (shown verbatim in framework/modules/web/README.md, section "Bakom en omvänd proxy" / "### Spola")
  problem: The README's Spola example says "Caddy and the app on Docker's network; only Caddy's port is published", but it starts the server with `HttpServerOptions.DEFAULT.withPort(8080)`. DEFAULT binds the loopback address (HttpServerOptions.java:22-24, 35), so a Caddy container on the Docker network cannot reach the app. A developer who copies the example for the Docker setup it describes gets connection refused, which Caddy shows as a 502.
  evidence: scratch-breaker/probe/src/Probe3.java runs the same router and options (`HttpServerOptions.DEFAULT.withPort(18080)`) and connects the way another container would, through the host's non-loopback address 192.0.2.2. Output:
      INFO: listening on localhost/127.0.0.1:18080
      127.0.0.1 -> HTTP/1.1 200 OK | https 203.0.113.7
      192.0.2.2 -> java.net.ConnectException: Connection refused
    The failure is loud and has no security impact, so MINOR.
  fix_hint: In WhoamiApp.main, bind an address reachable on the container network (for example `.withAddress(InetAddress.ofLiteral("0.0.0.0"))`) and say in the comment that only Caddy's port is published. Or drop the Docker wording from the comment.
- id: BRK-2
  severity: MINOR
  where: framework/modules/http/src/spola/http/Connection.java:90-107 (one shared InetSocketAddress per connection), Request.java:109-111 (Javadoc), ARCHITECTURE.md section "De strikta IP-literalerna" ("clientAddress() har inget värdnamn som toString() eller loggar kunde avslöja")
  problem: Every request on a keep-alive connection gets the same InetSocketAddress/InetAddress object. Behind a proxy, that means every client multiplexed on the proxy's connection. If any code calls `remoteAddress().getHostName()` once, the JDK caches the name in that shared object. After that, `getHostString()`, which the Request Javadoc recommends "for the address as text", returns the host name for every later request on the connection. `clientAddress().toString()` then contains the name too, which contradicts the ARCHITECTURE claim.
  evidence: scratch-breaker/probe/src/Probe4.java sends three requests on one connection: `/w`, then `/lookup` (whose handler calls `r.http().remoteAddress().getHostName()`), then `/w` again. Output:
      /w -> clientAddress=/127.0.0.1 remote=/127.0.0.1:55286 hostString=127.0.0.1
      /lookup -> looked up localhost
      /w -> clientAddress=localhost/127.0.0.1 remote=localhost/127.0.0.1:55286 hostString=localhost
    It needs app code that calls the discouraged `getHostName()`, so MINOR.
  fix_hint: Narrow the Javadoc to recommend only `getAddress().getHostAddress()`, and qualify the ARCHITECTURE sentence. Alternatively, give each Request its own unresolved-name copy of the address.
```
What I tried, with no BLOCKER or MAJOR found:
- Full build of a copy of `framework/` (scratch-breaker/fw, JDK 25): `BUILD OK in 295317 ms`, exit 0. Guardrails report no violations. The test counts match build.md: spola.http 290, spola.web 1083, spola.security.web 133. Log: scratch-breaker/build.log.
- Differential fuzz of `IpLiteral.parse`/`address` (copied into scratch-breaker/ip) against a regex of the RFC 3986 IPv6address/IPv4address ABNF (RFC 4291 §2.2 text forms, strict dec-octet), with byte equality to `InetAddress.ofLiteral` on every accepted input. 6,000,000 random and mutated strings over `0-9a-fA-F:.%[]/ ,g`: 0 mismatches. All RFC 4291 §2.2 and RFC 5952 example literals parse to the right addresses.
- Differential fuzz of `TrustedProxies.of(net/prefix).trusts` against a BigInteger mask reference. 200,000 random IPv4/IPv6 networks and all prefixes, 20 addresses each (half of them one bit off the network): 0 mismatches. The suggestion messages (`10.0.0.1/8`, `fc00::1/7`, `172.31.0.1/12`, `::1/0`) are right. `IpLiteral.format` round-trips 500,000 addresses.
- Checked against JDK 25 that `ofLiteral` really accepts `127.1`, `2130706433`, `1.2.3`→1.2.0.3, `010.0.0.1`, `[2001:db8::1]` and `fe80::1%1`, as the docs claim.
- End-to-end probes over raw sockets through a real HttpServer (Probe.java): header names in any case; an empty XFF; an empty XFP (400); a quoted element (400); obs-text 0xA0 (400); tabs and trailing commas; `::ffff:10.0.0.1` skipped as a trusted IPv4 address; `::10.0.0.1` kept as IPv6; an invalid value on a 404 path (400 that names the header, no value). Everything matched the design decisions.
- Code review: the router builds WebRequest only before the filter chain, so `withAttribute`/`withClient` are the only copies and both keep the values. The walk never reads anything left of the client. An untrusted sender never reaches header parsing. Chunked trailers are dropped by spola.http, so they cannot inject XFF. `Request` is built only in `Connection`. The `HttpServer` accept-loop change is safe. No name lookups or reflection appear in the main code.
