# Report: 20261003-1022-web-json-innehallsforhandling

- **Status:** PASS
- **Title:** Web part 2: JSON bodies, media types and content negotiation
- **Source:** uppdrag/08b-web-json-och-innehallsforhandling.md (PLAN row 08, continued; 08c = cookies and form bodies)
- **Rounds:** 2 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | FAIL (0 blocker, 1 major, 5 minor) | green | f73d0b4 |
| 2 | PASS (0 blocker, 0 major, 4 minor) | green | ffa67ec |

## What was built
`MediaType` and `AcceptHeader` (strict RFC 9110 parsing, specificity-based negotiation), `WebRequest.contentType/negotiate/readJson`, `RouterBuilder.jsonLimits`, and `Responses` (json/created/noContent). Errors: 415 (wrong Content-Type/Content-Encoding), 406, 400 with the shared `errors` list, 413 from streaming parse, and 500 for encode failures. The README has a CRUD example next to Spring Boot. `spola.json` gained `JsonMappingException.problemCount()`, which is justified in ARCHITECTURE.md.

## Fixed during the run
- BRK-1 (MAJOR): `Accept: application/json; charset=utf-8` got 406. Ranges with charset=utf-8 now match JSON offers.
- BRK-2: the legacy `HttpURLConnection` Accept header (gives 400) is now a documented deviation.
- MNT-1/2/4 (round 1): the duplicated cap constant, Content-Type parsing and Javadoc errors were cleaned up.

## Remaining (MINOR)
- `MediaTypeParser.parse` is long; splitting it was deferred.
- The charset/utf-8 literals are inline in `MediaType.includes` and duplicated in `JsonBody`.
- The UTF-8 exception applies only to JSON (`text/plain;charset=utf-8` in Accept vs a plain `text/plain` offer gives 406; this is documented).

## Assumptions / notes
- The 400 detail uses "1 problem"/"N problems" rather than the literal "problem(s)".
- After a readJson 413, `spola.http` drains the rest of the body (up to maxBodySize, nothing kept in memory), as required by the 07a rule.
- The orchestrator wrote the round 2 findings itself (all critics PASS). The breaker's 9.7 MB scratch copy of framework/ was removed before committing.

No stash.
