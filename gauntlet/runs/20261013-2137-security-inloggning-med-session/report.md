# Report: 20261013-2137-security-inloggning-med-session

- Status: PASS
- Title: Sessions part 2: login and logout with session (spola.security.web)
- Mission: uppdrag/11b-security-inloggning-med-session.md (need: psykologen, inloggning-sessioner-csrf)
- Rounds: 1 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 11 minor reported) | green | 9fc4caa |

## Built
`WebSecurity.Builder.sessions(Sessions)`, `WebSecurity.login` (renew + identity in the session) and
`WebSecurity.logout` (invalidate). The security filter runs the session filter itself; the session
identity comes before mechanisms and never rejects; 401 without `WWW-Authenticate` when no HttpBasic.
README section "Inloggning med session" with a compiled example, ARCHITECTURE, PROGRESS, MONSTER, root README.

## Remaining findings (MINOR)
See round-1/findings.md: a wrong `config.booleanValue` call in the example's Javadoc/README snippet,
detection of a double session filter by catching IllegalStateException, test-helper placement and
copied rules, test thresholds tied to other tests, README wording, a "se nedan" that should be "se ovan".

## Notes
- The judge was skipped because all three critics passed with no BLOCKER/MAJOR.
- The auditor ran `pkill -f "java Build.java"` during critique; the breaker's build still finished green.
- 11c (CSRF) remains in uppdrag/.
- Stash: –
