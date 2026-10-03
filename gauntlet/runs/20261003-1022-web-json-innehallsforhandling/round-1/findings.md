# Round 1 — verdict: FAIL

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/web/src/spola/web/MediaType.java:139-153 (`includes`), reached through AcceptHeader.quality/negotiate and Responses.json. Problem: `Accept: application/json; charset=utf-8` (also `charset=UTF-8`) gives q=0 for `APPLICATION_JSON`, so `negotiate` returns empty and every `Responses.json` answers 406. I reproduced it with scratch-breaker/Probe5.java: `q(json)=0 negotiate=Optional.empty` for both spellings. Mainstream clients send this header (Retrofit/OkHttp, Spring's `APPLICATION_JSON_UTF8_VALUE` in Feign/RestTemplate), so a good-faith user will hit it on ordinary input. Neither README.md's deviation table nor ARCHITECTURE.md mentions it. The mission's rule that "a range with parameters is more specific than one without" is about ranking. It does not require a UTF-8 charset range to exclude a JSON response that is UTF-8 by definition (RFC 8259 §8.1). fix_hint: When matching, treat a range's `charset` (case-insensitive, quoted or not) of `utf-8` as satisfied by a JSON offer (`isJson()`) that has no charset. Responses are always UTF-8, and the range keeps its higher specificity. A non-UTF-8 charset should still exclude. Add Accept/Responses table cases: `application/json; charset=utf-8` and `charset="UTF-8"` give 200, `charset=iso-8859-1` gives 406 (or falls through to `*/*` if present). Describe the rule in ARCHITECTURE.md and the README. Documenting the 406 alone is not enough, because this breaks ordinary clients.

## Rejected
- AUD-1: Not a defect. Spec "Antaganden" explicitly allows small additions to `spola.json` when ARCHITECTURE.md motivates them. The motivation is at ARCHITECTURE.md:1731, and `git status` shows only `JsonCodec.java` and `JsonMappingException.java` changed under modules/json, with no test files.
- AUD-2: Not a defect. Pluralising correctly ("1 problem" / "3 problems") meets the intent of the mission text and does not weaken criterion 8.

## Duplicates
- (none)

## MINOR (optional for the builder)
- BRK-2 [MINOR] AcceptHeader/MediaTypeParser: the legacy `HttpURLConnection` default header (`text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2`) gets 400 (reproduced). This follows the binding decision "invalid Accept => 400" and only affects pre-JDK-23 clients. fix_hint: add a row to the README deviation table that names this header (or accept a bare `*` as `*/*` and document that).
- MNT-1 [MINOR] JsonBody.java:22 `MAX_LISTED` duplicates `Problem.MAX_ERRORS` (Problem.java:47). fix_hint: use `Problem.MAX_ERRORS`.
- MNT-2 [MINOR] JsonBody.java:73-112: the Content-Type parse steps are duplicated, and `checkContentType`'s return value is unused. fix_hint: share one helper.
- MNT-3 [MINOR] MediaTypeParser.parse is long (about 85 lines, one loop with many branches). fix_hint: split into helpers.
- MNT-4 [MINOR] JsonBody.java:27: the `ACCEPT_IDENTITY` Javadoc describes the wrong thing. JsonBody.java:206 and Problem.java:204/241 use fully qualified `java.util.*` names inline. fix_hint: fix the Javadoc and import the classes.
