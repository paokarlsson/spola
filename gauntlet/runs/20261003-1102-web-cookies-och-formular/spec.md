# Spec: Web part 3 — cookies, Set-Cookie and form bodies (spola.web)

## Task
Add cookie reading (`WebRequest.cookie`/`cookieAll`/`cookie(name, Param)`/`cookies()`), outgoing cookies
(`ResponseCookie` with `SameSite`, `Responses.withCookie`) and `application/x-www-form-urlencoded`
form bodies (`WebRequest.readForm`/`readForm(int)`, `FormData`, `RouterBuilder.maxFormSize`) to
`spola.web`, explicitly and without reflection. This completes row 08 of `uppdrag/PLAN.md`.
The full mission, including **all design decisions** (which are binding), is in `mission.md` in
this run directory. Where this spec and mission.md differ in detail, mission.md's design
decisions apply.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/` (module `spola.web` under `framework/modules/web/`, plus `PROGRESS.md`,
`ARCHITECTURE.md`, `modules/web/README.md`).

## Verification command
`java Build.java` in `framework/` — must exit 0.

## Acceptance criteria
(Taken over from mission.md; none dropped or softened.)

1. `java Build.java` in `framework/` exits with code 0.
2. `modules/web/src/module-info.java` has no new `requires`. Main code imports nothing from
   `java.lang.reflect` or `java.lang.invoke`.
3. A table-driven test of cookie parsing has **at least 25 cases** and shows: multiple cookies;
   the same name twice (`cookieAll` in order, `cookie` gives the first); spaces and tab around
   pairs; a quoted value that keeps its quotes; an empty value (`a=`); `=` inside the value;
   two `Cookie` fields merged; percent-encoding not decoded; that each of the following is
   skipped without affecting valid pairs around it: pair without `=`, empty name, name with an
   illegal character, value with space, comma, `\`, control character and non-ASCII; that 200
   cookies are accepted and 201 gives 400 with no values in the detail; that a missing
   `Cookie` gives empty results.
4. A test shows cookie parsing happens at most once per request even when several derived
   `WebRequest`s (via `withAttribute`) and several threads read cookies, and that
   `cookie(name, Param.INT)` gives 400 with the name and an escaped, truncated value for `x=abc`.
5. A table-driven test of `ResponseCookie` has **at least 15 valid cases** with exact expected
   `Set-Cookie` text, covering: defaults; every attribute; a quoted value; `maxAge` 0 and 30
   days; `expire` with and without `path`/`domain`; `__Host-` and `__Secure-` used correctly.
   It also has **at least 15 invalid cases** giving `IllegalArgumentException`: name with `=`,
   space, control char, and empty name; value with `;`, `,`, space, `"` in the middle and
   non-ASCII; `path` with `;` and `path` not starting with `/`; `domain` with `/`; negative
   `maxAge`; `SameSite=None` without `Secure`; `__Secure-` without `Secure`; `__Host-` with
   `domain` and `__Host-` with a `path` other than `/`. No error message contains an unescaped
   control character. Header order is fixed:
   `name=value; Path=...; Domain=...; Max-Age=...; Secure; HttpOnly; SameSite=...`; no `Expires`.
6. A test over a real `HttpServer` (port 0, `127.0.0.1`) with a raw socket shows that a response
   with three cookies has three separate `Set-Cookie` lines. A test with `HttpClient` and a
   `CookieManager` shows a cookie set in one response is sent back and read by
   `request.cookie(...)` in the next request.
7. A test over a real server shows the form flow per the README example: fields with `+`, `%20`,
   `%2B`, UTF-8 (`%C3%A5`) and repeated names; `FormData.all` in order; typed fields via `Param`;
   `required` for a missing field giving 400 with "form field" and the name; an empty body giving
   an empty `FormData`; query and form kept apart (same name in both gives different values).
8. A test shows 415, with `Accept: application/x-www-form-urlencoded` in the response, for: missing
   `Content-Type`; `application/json`; `multipart/form-data`; a malformed `Content-Type`;
   `charset=iso-8859-1`; `Content-Encoding: gzip`. It also shows
   `application/x-www-form-urlencoded; charset=UTF-8` is accepted.
9. A test shows 400 with no text from the body (checked with a unique marker string) for: invalid
   percent-encoding (`%G1`); invalid UTF-8 after decoding (`%C3%28`); a raw byte above 0x7F;
   1 001 pairs. It also shows exactly 1 000 pairs are accepted.
10. A test shows size limits: a form body over `RouterBuilder.maxFormSize(...)` (but under
    `maxBodySize`) gives 413 without buffering the whole body — using a body many times larger
    than the limit, sent chunked, showing the response arrives; `readForm(maxBytes)` takes
    precedence over the router default; a body over `HttpLimits.maxBodySize` still gives the body
    status from `spola.http` (`RequestBodyException` is not swallowed).
11. A test shows `readForm` after `readJson`, `body()` or `readForm` in the same request gives 500
    (via `IllegalStateException`), and that a `WebException` mapper can produce its own response
    for 415 and 400 from `readForm`.
12. A test with 200 concurrent clients (virtual threads) posting different forms and different
    cookies shows each client gets back its own values.
13. A test shows a cookie or form value containing `%0A`, `\u001b` or a raw newline does not end
    up unescaped in any log line or problem detail.
14. `README.md` in `modules/web/` has the form+cookie example next to Spring Boot (as a compiled
    test source that a test compares with the README) and the new deviation rows listed in
    mission.md (ResponseCookie defaults; no `Expires`; malformed cookies skipped; quotes kept in
    cookie values; form and query not merged; empty form body valid; 415 for wrong form
    `Content-Type`).
15. No annotations beyond the allow-list and no dependencies beyond the JDK (guardrails green).
    No new file types under `framework/`, and no rule in `framework/REGLER.md` is broken.
16. `PROGRESS.md` and `ARCHITECTURE.md` are updated. Row 08 of `PLAN.md` is marked done in
    `PROGRESS.md`. ARCHITECTURE.md describes every item listed in mission.md criterion 16
    (cookie parsing and why malformed pairs are skipped; the 200 limit; quotes kept / no decoding;
    ResponseCookie defaults and prefix rules; why no `Expires`; separate `Set-Cookie` fields;
    `readForm` order and status codes; empty body = empty form; form and query not merged;
    `BodyState` shared with `readJson`).

Additional binding requirements (from mission.md "Krav"): everything new is immutable and
thread-safe with no state shared between requests; all public API has Javadoc, including the
`ResponseCookie` defaults; existing 07/08a/08b tests remain unchanged and green; reuse existing
helpers (`Decoding.parseQuery`, `BodyState`, the `JsonBody` order) rather than parallel variants.

## Out of scope
Multipart and file upload; sessions, signed/encrypted cookies, CSRF; form→record mapping and
per-field validation; `Expires`, `Partitioned`, `Priority` and other cookie attributes; merging
query and form; charsets other than UTF-8 for forms.

## Assumptions
- Small additions to `spola.http` are allowed only if justified in ARCHITECTURE.md.
- Tests use only `127.0.0.1` and ephemeral ports.
- Round limit: 5 (from mission.md).
