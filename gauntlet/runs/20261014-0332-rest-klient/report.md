# Report: 20261014-0332-rest-klient

- Status: PASS
- Title: REST client for JSON APIs on java.net.http (spola.client)
- Mission: uppdrag/14-rest-klient.md (need: psykologen, rest-klient)
- Rounds: 2 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 3 major, 11 minor) | green | b1b3c7a |
| 2 | PASS (0 blocker, 0 major, 18 minor reported) | green | a02d012 |

## Built
New module `spola.client` (Spring's `RestClient`): `RestClient` with builder, `RestRequest`, sealed
`RestClientException` (`RestClientResponseException` with status, headers, excerpt;
`RestClientTransportException` with `timedOut()`). One deadline for the whole call, response size limit,
no redirects, HTTP/1.1, JSON via `spola.json` codecs. `Build.java` lets test layers resolve Spola modules
required `static`.

## Fixed in round 2
- BRK-1: non-ASCII request bodies leaked through JDK ProtocolException text; causes are now text-free
  except for socket/TLS/timeout errors.
- BRK-2: echoed header values, query and body leaked into getMessage(); they are now <hidden>.
- BRK-3: a 19+ digit Content-Length waited for the timeout; now fails at once with the status.
- All round-1 MINOR findings.

## Remaining findings (MINOR)
See round-2/findings.md. Most notable: with `Authorization: Bearer <key>` a server that echoes only the
token shows it in getMessage(); the README overstates this and the spola.json cause bound.

## Notes
- Round 2 was resumed after an API usage limit interrupted the first round-2 builder.
- Round 2's judge was skipped because all critics passed with no BLOCKER/MAJOR.
- Stash: –
