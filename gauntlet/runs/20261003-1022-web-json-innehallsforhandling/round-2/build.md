# Round 2 build

## Changes
- `framework/modules/web/src/spola/web/MediaType.java`: `includes` now treats a range's `charset=utf-8` as satisfied by a JSON type (`isJson()`) with no `charset` (RFC 8259 §8.1). Values are already lowercased at parse time, so this covers `UTF-8` and quoted `"UTF-8"`. Any other charset still excludes. The Javadoc describes the exception. I put it in `includes` rather than only in `AcceptHeader`, so the public method and negotiation always agree. The range keeps its specificity (parameter count is unchanged).
- `framework/modules/web/src/spola/web/JsonBody.java`: removed `MAX_LISTED` and now use `Problem.MAX_ERRORS` (MNT-1). The Content-Type steps are shared in one helper, `single(values, errorFactory)`, and `checkContentType` is now `void` (MNT-2). Fixed the `ACCEPT_IDENTITY` Javadoc and imported `Objects` (MNT-4). Side effect: the duplicate-field 415 detail from `contentType()` now reads "...times; send it once." No test pinned the old text.
- `framework/modules/web/src/spola/web/Problem.java`: imported `List`/`ArrayList` instead of using fully qualified names (MNT-4).
- Tests:
  - `MediaTypeTests`: new test "includes: charset=utf-8 is satisfied by JSON without a charset". It has 9 assertions, including iso-8859-1, utf-16, an extra parameter, `text/json`, and `application/*;charset=utf-8` against problem+json.
  - In the old "includes" test I replaced the assertion `application/json;charset=utf-8` does not include `application/json`, which contradicted the fix. The new assertions check that `text/plain;charset=utf-8` and `application/xml;charset=utf-8` do not include their bare types.
  - `AcceptTests`: 7 new table cases through the real server: `charset=utf-8`, `charset="UTF-8"`, ranking against text/plain, `q=0` exclusion, iso-8859-1 → 406, iso-8859-1 with `*/*;q=0.1` → JSON, and text/plain;charset=utf-8 → 406.
  - `ResponsesTests`: `Responses.json` gives 200 for `application/json; charset=utf-8`, `charset="UTF-8"` and iso-8859-1 with `*/*;q=0.1`, and 406 for `charset=iso-8859-1`.
- `framework/ARCHITECTURE.md`: new decision bullet on the charset=utf-8/JSON rule, covering why it lives in `includes` and why it is JSON only.
- `framework/modules/web/README.md`: added a negotiation paragraph and two deviation rows: `charset` in `Accept`, and the legacy `HttpURLConnection` header giving 400 (BRK-2).
- `framework/PROGRESS.md`: added the rule, updated the test counts (455), and listed the known limitations (text/plain charset, legacy header).

## Verification
`java Build.java` in `framework/` → exit 0, `BUILD OK in 78471 ms`.
```
      ok      [20] application/json; charset=utf-8 offering [application/json] -> application/json
      ok      [21] application/json;charset="UTF-8" offering [application/json] -> application/json
      ok      [24] application/json;charset=iso-8859-1 offering [application/json] -> 406
      ok      [25] application/json;charset=iso-8859-1, */*;q=0.1 offering [application/json] -> application/json
    ok      includes: charset=utf-8 is satisfied by JSON without a charset (RFC 8259 8.1)
    ok      200 for */*, application/*, a lower but non-zero weight, charset=utf-8, and no Accept
spola.web: OK - 455 tests, 455 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 2069 ms
```
All other modules are green in the same run.

## Addressed
- BRK-1 [MAJOR]: fixed in `MediaType.includes` as the fix hint describes, with table cases (Accept and Responses) and documentation in ARCHITECTURE.md and README.md.
- BRK-2 [MINOR]: documented as a README deviation row and in PROGRESS known problems. Behaviour is unchanged (400), per the binding "invalid Accept => 400".
- MNT-1, MNT-2, MNT-4 [MINOR]: fixed as described above.

## Rebuttals
- None.

## Known gaps
- MNT-3 (splitting `MediaTypeParser.parse` into helpers) is not done. It is optional, and I didn't want to refactor a security-relevant parser that is passing in a fix round.
- The UTF-8 exception covers only JSON. `Accept: text/plain;charset=utf-8` against an offered `text/plain` is still 406. This is documented, and the handler can offer `text/plain;charset=utf-8` explicitly.
