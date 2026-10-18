# Report: 20261014-0119-web-proxyhuvuden

- Status: PASS
- Title: Proxy part 1: client scheme and address from X-Forwarded-* (spola.web)
- Mission: uppdrag/13a-web-proxyhuvuden.md (need: psykologen, proxyhuvuden; 13b remains)
- Rounds: 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 13 minor reported) | green | 25fc491 |

## Built
`Request.remoteAddress()` in spola.http; `WebRequest.scheme()`, `clientAddress()`, `TrustedProxies`
(`privateNetworks()`, `of(...)`) and `ForwardedHeaders.trusting(...)` in spola.web. Headers count only
from a trusted sender; X-Forwarded-For from the right skipping trusted proxies; strict IP literals before
`InetAddress.ofLiteral`; 400 for malformed values from a trusted proxy; no DNS lookups.

## Remaining findings (MINOR)
See round-1/findings.md: the README example binds loopback although its comment describes Docker, a shared
address object per connection can carry a cached host name, and maintainability notes.

## Notes
- The judge was skipped because all three critics passed with no BLOCKER/MAJOR.
- 13b (Secure cookies after the scheme) is waiting in uppdrag/.
- Stash: –
