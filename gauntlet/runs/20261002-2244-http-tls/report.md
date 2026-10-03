# Report — 20261002-2244-http-tls

**Status:** PASS
**Title:** HTTP server part 2: TLS (HTTPS) on top of spola.http
**Mission:** uppdrag/07b-http-tls.md (moved to uppdrag/klara/)
**Rounds:** 2 of max 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (1 blocker, 1 major, 6 minor) | green | 2bab4f1 |
| 2 | PASS (0 blocker, 0 major, 6 minor) | green | 236fe7e |

## Delivered
- **HTTPS in `spola.http`:** TLS 1.3/1.2 only, with ALPN `http/1.1`.
- **Key material:** PKCS#12, PEM (with strict Base64) or a ready `SSLContext`, validated eagerly and never leaked in messages.
- **Client certificates:** NONE, WANT or NEED.
- **Timeouts:** a total handshake timeout, plus a guard thread that enforces the header, idle, body-read and linger deadlines over TLS.
- **Request API:** `Request.tls()` and `Request.scheme()`.
- **Tests:** the 07a suite also runs over TLS, and the new TLS tests use certificates generated with `keytool` at test time. 283 tests in spola.http.
- **Docs:** README (with the `server.ssl.*` mapping), ARCHITECTURE and PROGRESS updated.

## Fixed along the way
- **AUD-1 (BLOCKER):** the "invalid Base64 length" test was flaky. It is now deterministic through strict padding.
- **BRK-1 (MAJOR):** a slowloris client over TLS could bypass `headerTimeout` and the idle and linger limits. Fixed with the guard deadline.
- **Minor:** permit leak in `closeSocket`, RSASSA-PSS keys, the `|1` sentinel in the handshake code, and an inline FQN.

## Remaining (MINOR)
- **408 lost over TLS:** a client that stalls sometimes misses its 408, because the guard and soTimeout fire at the same instant (BRK-1 r2).
- **No 408 after a cut:** over TLS, a cut at `headerTimeout` sends no 408 (documented).
- **Code cleanup:** the `|1` sentinel is still used for `readDeadline`, the deadline calculation in `fill()` assigns twice, and a comment is missing at `sendError(408)`.
- **Plain/TLS branching:** not yet pulled out of `Connection` into a helper.

## Notes
- The judge was skipped in round 2 because all critics gave PASS with no BLOCKER/MAJOR.
- The breaker's scratch directories (with test keys) are gitignored and not committed.
- Stash: –
