# Round 1 – build

## Changes

Main code (`framework/modules/web/src/spola/web/`):
- `MediaType.java` (new, public record): strict RFC 9110 §8.3.1 media type; `parse`, `of`, constants `APPLICATION_JSON`, `APPLICATION_PROBLEM_JSON`, `TEXT_PLAIN`, `ALL`; `includes` (wildcards + parameters), `isJson` (`application/json`, `application/<x>+json`), `isWildcard`, `charset()`, canonical `toString`. Canonical constructor validates and normalizes too.
- `MediaTypeParser.java` (new, internal): the one strict parser (token / quoted-string with escape, lowercase type/subtype/names, `charset` value lowercased, 64 parameters, 1 024 chars, control/non-ASCII, unterminated quote, `*/json`, duplicates, empty parameters). In Accept mode also `q` (exact qvalue grammar, thousandths) and ignored accept-ext.
- `AcceptHeader.java` (new, public): `parse(String)`, `parse(List<String>)` (several fields), `ANY`, `ranges()` (`Range(range, quality)`), `quality`, `negotiate`. Quote-aware comma split, empty elements ignored, max 100 ranges. Most specific including range decides; equal specificity -> highest weight (order independent); equal quality -> server order; no Accept -> first offered.
- `JsonBody.java` (new, internal): the `readJson` rules (Content-Encoding 415, Content-Type/charset 415 with `Accept: application/json`, empty 400, streaming parse via `LimitedInput` that yields at most `maxInputSize + 1` bytes -> 413, syntax/limit 400 with line/column and excerpts of the body redacted, mapping 400 with `errors`).
- `BodyState.java` (new, internal): per-request read-once claim (atomic) + router JSON limits; shared by `withAttribute` copies.
- `Responses.java` (new, public): `json(request, status, codec, value)`, `json(request, status, JsonValue)`, `created(...)`, `noContent()`. Negotiates first (406), encodes to a byte array, `application/json` without charset; `encode` failure -> `IllegalStateException` -> 500.
- `WebRequest.java`: `contentType()`, `accept()`, `negotiate(MediaType...)`, `readJson(codec)`, `readJson(codec, limits)`; `body()`/`bytes()` mark the body as read (and throw after `readJson`).
- `RouterBuilder.java`: `jsonLimits(JsonLimits)`. `Router.java`: passes limits, creates `BodyState` per request.
- `Problem.java`: `FieldError(path, message)`, `withErrors(...)`, `MAX_ERRORS` (the shared `errors` format for 09). `WebException.java`: `errors()` + protected constructor with errors; `problem()` adds them.
- `module-info.java`: Javadoc bullet only; requires unchanged.

`spola.json` (small addition, motivated in ARCHITECTURE.md "Web del 2"):
- `JsonMappingException.problemCount()` + a 2-arg package-private constructor; `JsonCodec.decode` passes `Decoding.count()`. Needed to state the total count when > 100 problems. No existing json test changed.

Tests (`framework/modules/web/test/spola/web/`): new `MediaTypeTests`, `AcceptTests`, `CrudTests`, `ItemApi` (the README CRUD example, verbatim), `JsonBodyTests`, `ResponsesTests`; `WebTests.java` gets 5 registration lines (the module's single test entry, unavoidable; no 08a test changed).

Docs: `modules/web/README.md` (CRUD example next to Spring Boot, negotiation example next to Spring, new table rows and 6 new deviation rows), `ARCHITECTURE.md` (new section "Web del 2"), `PROGRESS.md` (08b entry, 08c remaining, known limits).

## Verification

`cd framework && java Build.java` -> exit 0:
```
spola.json: OK - 417 tests, 417 ok, 0 failed, 0 skipped, 0 suite fixtures failed
spola.http: OK - 283 tests, 283 ok, 0 failed, ...
spola.web: OK - 447 tests, 447 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 2197 ms
BUILD OK in 80451 ms
```
Guardrails ran green as part of the build (annotations, dependencies, files, README not parseable as Java).
Table sizes counted from the run log: MediaType valid 25, invalid 35; Accept/negotiate 48 cases over a real server.

Criterion mapping:
1. build green (above).
2. module-info requires unchanged (existing `SourceRulesTests` asserts the exact list and no reflection in main code; still green).
3. `MediaTypeTests`: 25 valid / 35 invalid incl. lowercase, quoted-string escapes, charset case, `+json`, `*/*`, `type/*`, `*/json`, duplicate, control chars, non-ASCII, unterminated quote, 65 params, 1 025 chars.
4. `AcceptTests` (48 cases through HttpServer): specificity in both orders, `q=0` vs `*/*`, server order on ties, missing/empty Accept, multiple fields, quoted comma, accept-ext after q, `1.5`/`-1`/`0.0001`/`abc`/`1.0000` -> 400, 101 ranges (one field and split over two) -> 400, 100 -> OK.
5. `CrudTests`: POST 201 + Location + body, GET/PUT 200, DELETE 204, `application/json`, Content-Length == UTF-8 byte length, round trip with the codec, HEAD same headers no body; plus a test that README contains `ItemApi.java` verbatim.
6. `JsonBodyTests` "415 with Accept: application/json" (11 cases incl. the 6 required) + accepted `charset=UTF-8`, `charset="utf-8"`, `application/vnd.example+json`.
7. empty body, syntax error (line 2, column 3; marker absent; also duplicate-member marker absent), depth 600 > 512.
8. three problems at three levels in document order with path/message; 250 problems -> 100 entries + count in detail (also 100 and 101 boundaries); `%0A`/ESC not raw in any log line (also a 500 path that logs the values).
9. 2 MiB chunked stream vs 1 KiB limit -> 413 arrives; unit test shows exactly 1 025 bytes read from an endless stream; `maxBodySize` -> spola.http's plain-text 413 + close even with an `Exception` mapper; own limits override the router's both ways.
10. second `readJson`, `readJson` after `bytes()`, `body()` after `readJson` -> 500; WebException mapper gets 415 (with Accept header) and 400 (with errors).
11. `ResponsesTests`: 406 for `text/html` and `application/json;q=0, */*`; 200 for `*/*`, `application/*`, `application/json;q=0.5, text/html;q=0.9`; problem+json with `Accept: text/html` (404, 400, 406).
12. encode failure -> 500 without detail, logged; a mapper for `JsonMappingException` cannot turn it into 400.
13. 200 virtual-thread clients POST distinct JSON and each gets its own back.
14. README updated. 15. guardrails green, no new file types (.java/.md only). 16. PROGRESS/ARCHITECTURE cover all eight points.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- After `readJson` answers 413 the server (spola.http, 07a rule) still reads and discards the rest of the body (up to `maxBodySize`, not buffered) before sending the response. Documented in ARCHITECTURE/PROGRESS.
- `readJson` cannot see reads through `request.http().body()` (only `WebRequest.body()/bytes()` are tracked).
- Mapping-problem detail uses proper pluralisation ("has 1 problem." / "has 3 problems.") rather than the literal "problem(s)" of the mission text.
- `WebTests.java` (08a file) got 5 registration lines; it is the module's only test entry in `Build.java`.
- Only `utf-8` is accepted as charset (not the alias `utf8`).
