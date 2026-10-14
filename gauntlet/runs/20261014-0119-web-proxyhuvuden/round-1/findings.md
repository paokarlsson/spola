# Round 1 findings

VERDICT: PASS

The judge step was skipped: all three critics (breaker, auditor, maintainer) gave PASS with no
BLOCKER or MAJOR. The MINOR findings below are carried over as reported, without a ruling.

Counts: 0 blocker, 0 major, 13 minor (reported, not judged).

## MINOR
- BRK-1: the README example's comment describes Docker, but `HttpServerOptions.DEFAULT` binds loopback,
  so Caddy in another container cannot reach it (connection refused, loud, no security impact).
- BRK-2: one shared InetSocketAddress per connection; an app calling `getHostName()` caches the name, so
  later `getHostString()`/`clientAddress().toString()` show it, contradicting a sentence in ARCHITECTURE.
- AUD-1: the README example takes the proxies as a router parameter (spec ambiguity; reasonable).
- AUD-2: the accept-loop log message changed slightly (explained).
- MNT-1: the short WebRequest constructor resets scheme/client; a future copy method could fail open.
- MNT-2: `ProxyNetworks.parse` uses null plus an out-parameter and a second IPv4-mapped check.
- MNT-3: a full RFC 5952 formatter exists only for a "did you mean" hint.
- MNT-4: the backward X-Forwarded-For scan is dense and its rationale is not written down.
- MNT-5: X-Forwarded-Proto is checked before X-Forwarded-For; not documented or tested.
- MNT-6: tests coupled to README line breaks and to case display names.
- MNT-7: hard-coded counts in ARCHITECTURE and unexplained arithmetic in the leak test.
- MNT-8: `TRUSTING`/`NOT_TRUSTING` test constant names are misleading.
- MNT-9: the comment-stripping regex is copied once more in each module's tests.

Critic reports: critic-breaker.md, critic-auditor.md, critic-maintainer.md.
