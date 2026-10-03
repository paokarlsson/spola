# Round 1 build: 08c cookies, Set-Cookie and form bodies

## Changes
Main code (`framework/modules/web/src/`):
- `spola/web/CookieState.java` (new): parses `Cookie` lazily, at most once per request, and is shared by `withAttribute` copies (same pattern as `QueryState`). Follows RFC 6265 §4.2.1/§5.4: fields joined, split on `;`, SP/HTAB trimmed, token name, `*cookie-octet` or quoted value, quotes kept, no decoding. Malformed pairs are skipped. More than 200 valid pairs gives 400 with no names or values in the detail.
- `spola/web/ResponseCookie.java` (new): immutable cookie plus an immutable `Builder` and `SameSite {STRICT, LAX, NONE}`. Defaults are `Path=/`, `HttpOnly` and `SameSite=Lax`. Validation happens in `build()`: name, value, path, domain (LDH, leading dot removed), negative or fractional `maxAge`, `SameSite=None` needs `Secure`, `__Secure-`, `__Host-`. Messages are escaped. The attribute order is fixed and no `Expires` is written. `expire(name)` gives an empty value and `Max-Age=0`; calling `maxAge` on that builder throws ISE.
- `spola/web/FormData.java` (new): immutable `first`/`all`/`get`/`required`/`asMap`/`isEmpty`. Errors say "form field". `toString` shows names only.
- `spola/web/FormBody.java` (new): runs the `readForm` steps in this order: Content-Encoding (415), Content-Type/charset (415 + `Accept: application/x-www-form-urlencoded`), streaming read through `JsonBody.LimitedInput` (413, stops at max+1), byte >0x7F (400), `Decoding.parseQuery` (400, max 1000 pairs).
- `WebRequest`: `cookie`, `cookieAll`, `cookie(name, Param)`, `cookies()`, `readForm()`, `readForm(int)`, Javadoc, and the package-private `cookieParses()` for tests.
- `BodyState`: `maxFormSize` and `claimForForm()`. `body()`/`bytes()` after `readForm` now also throw ISE.
- `Router`/`RouterBuilder`: `CookieState` per request, `maxFormSize(int)` (default 2 MiB).
- `Responses.withCookie(Response, ResponseCookie)`: adds one `Set-Cookie` field per cookie.
- `MediaType.APPLICATION_FORM_URLENCODED` (new constant).
- `JsonBody`: `checkEncoding(headers, accept)` overload, and `single` made package-private so `FormBody` can reuse them. JSON behaviour and messages are unchanged.
- `Decoding.parseQuery(raw, subject)` overload so the form detail reads "The form body ..."; `parseQuery(raw)` is unchanged.
- `Param` Javadoc (kinds) and the `module-info.java` Javadoc. No `requires` changed.

Tests (`framework/modules/web/test/`):
- `CookieTests` (36-case parsing table, cookie/cookieAll/cookies, skipping on a real request, 200/201, parse-once across copies and 24 virtual threads, `Param.INT` 400 with escaped and shortened value, unmodifiable map).
- `ResponseCookieTests` (22 valid exact texts, 29 invalid with printable messages, accessors, builder immutability, expire+maxAge ISE, exact messages, three `Set-Cookie` lines over a raw socket, HttpClient+CookieManager round trip through the README app, and a pin of the JDK `CookieManager` Max-Age quirk).
- `FormTests` (README flow, FormData API, empty body, 10×415, accepted types, 8×400 with marker, 1000/1001 pairs, 413 for 4 MiB chunked against 1 KiB, exactly max+1 bytes read, per-call limit precedence, `maxBodySize` still from spola.http, read-once 500s, WebException mapper for 415/400, 200 concurrent clients with forms+cookies, log/problem lines without control chars) and `FormTests.readme()` (README contains `ProfileApp.java` verbatim, plus the deviation rows).
- `ProfileApp.java` (new README example source).
- `WebTests.java`: registers the four new suites. This is the only edit to an existing test file. It's needed because Build.java names `WebTests` as the entry; no existing test was changed.

Docs: `modules/web/README.md` (intro, comparison rows, "Formulär och cookies" next to Spring Boot, 7 deviation rows), `PROGRESS.md` (08c entry, row 08 marked done, known problems), `ARCHITECTURE.md` (new section "Web del 3" covering every item in criterion 16).

## Verification
`cd framework && java Build.java` → exit 0:
```
guardrails: no violations
...
  cookies in
    Cookie header parsing (36 cases)
  ResponseCookie and Set-Cookie
    valid cookies (22 cases): the exact Set-Cookie text
    invalid cookies (29 cases): IllegalArgumentException, printable message
  form bodies
  README form and cookie example
spola.web: OK - 591 tests, 591 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 2290 ms
BUILD OK in 80962 ms
```
(Before this round: 455 tests in spola.web, so 136 are new.) `module-info.java` still has only `requires transitive spola.http; requires transitive spola.json; requires static java.net.http;`, and `SourceRulesTests` checks this and the ban on reflection imports.

## Addressed
Round 1: no findings yet.

## Rebuttals
None.

## Known gaps
- **JDK `CookieManager` and `Max-Age`.** `java.net.HttpCookie` treats `Max-Age` without `Expires` as RFC 2965 and sends back `$Version="1"; s="1";$Path=...`. The value then arrives quoted, Spola keeps the quotes, and `cookie("s")` gives `"1"`. This follows from two binding mission decisions together (no `Expires`, quotes kept). It is pinned by a test and documented in ARCHITECTURE/PROGRESS. Because of it, the README example uses a session cookie, so the CookieManager round trip (criterion 6) passes. Browsers are not affected.
- A form body must be visible ASCII only (parseQuery rules), so a raw trailing CRLF gives 400. This is documented.
- `readForm` needs `Content-Type` even for an empty body (415). After a 413, spola.http drains the rest of the body (up to maxBodySize) before the response is sent; this was already the case for `readJson`.
- `maxAge` with fractional seconds is rejected rather than truncated. This is my reading of "skrivs i hela sekunder" (it is listed under validation), and it is documented in Javadoc/ARCHITECTURE.
