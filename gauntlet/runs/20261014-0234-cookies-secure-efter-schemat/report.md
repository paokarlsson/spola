# Report: 20261014-0234-cookies-secure-efter-schemat

- Status: PASS
- Title: Proxy part 2: Secure on session and CSRF cookies from the scheme
- Mission: uppdrag/13b-cookies-secure-efter-schemat.md (last slice of psykologen's need proxyhuvuden)
- Rounds: 1 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 7 minor reported) | green | cbc1140 |

## Built
Session cookie and removal (spola.web) and XSRF-TOKEN (spola.security.web) get Secure when the request's
scheme is https (`template.secure() || https`), read once per request; behind a proxy this needs
`ForwardedHeaders` registered first. Javadoc, READMEs ("Bakom Caddy"), ARCHITECTURE and PROGRESS updated;
the 11a/11c "never from the scheme" decision is replaced.

## Remaining findings (MINOR)
See round-1/findings.md: the Caddy README snippet binds loopback, an attribute copy then override in
Sessions, an undocumented invariant between two scheme reads, and test-structure notes.

## Notes
- The judge was skipped because all three critics passed with no BLOCKER/MAJOR.
- The need 2026-10-13-proxyhuvuden is delivered in full (13a–13b).
- Stash: –
