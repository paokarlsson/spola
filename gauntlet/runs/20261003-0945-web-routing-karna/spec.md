# Spec: Web part 1 — routing as code, parameters, filters and error handling

## Task
Add a new module `spola.web` on top of `spola.http`: an immutable, thread-safe `Router` (a `spola.http.Handler`) with routes written as code, a trie lookup with literal > variable > catch-all precedence and backtracking, strict percent-decoding of the path (400 before routing), lazy query parsing (max 1 000 pairs), typed `Param<T>` conversion, typed `AttributeKey<T>` attributes on an immutable `WebRequest`, filters (router-level and group-level), and central exception mapping (closest class wins) with RFC 9457 `application/problem+json` responses.
Full details, design decisions and requirements: `mission.md` in this directory (source `uppdrag/08a-web-routing-karna.md`).
The "Designbeslut" and "Krav" sections of `mission.md` are binding.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/`

## Verification command
`java Build.java` in `framework/` (exit code 0).

## Acceptance criteria
1. `java Build.java` in `framework/` exits 0, and `spola.web` with its test entry point is listed in `MODULES` in `Build.java`.
2. `modules/web/src/module-info.java` has `requires transitive spola.http`, `requires spola.json` and no other `requires` on framework modules (apart from `requires static` for test needs); main code imports nothing from `java.lang.reflect` or `java.lang.invoke`.
3. A test over a real `HttpServer` (port 0, `127.0.0.1`) with `HttpClient` shows `GET`, `POST`, `PUT`, `PATCH` and `DELETE` to routes with path variables and query parameters, where the handler receives the correctly decoded values.
4. A table-driven matching test with at least 20 cases shows: literal before variable before catch-all; the backtracking example `/a/{x}/c` vs `/a/b/{y}` (for `/a/b/c`, `/a/b/{y}` wins; with only `/a/{x}/c` registered, it matches); that the outcome does not depend on registration order (same routes registered in reverse order give the same outcomes); catch-all with one and several segments and that catch-all requires at least one segment; the root `/`; strict trailing slash; that the method selects between routes with the same path; nested groups with variables in the prefix; and `%2F` staying inside a segment and therefore giving 400.
5. A test shows 404 (with a problem response), 405 with the exact `Allow` value, `HEAD` routed to the `GET` route (no body, same `Content-Length`), automatic `OPTIONS` (204 with `Allow`), that an explicit `HEAD` or `OPTIONS` route takes precedence over the automatic one, and `OPTIONS *` (404).
6. A test shows that `build()` rejects, with all problems in the same exception: pattern without leading `/`, empty variable name, duplicate variable names, partial segment, catch-all not last, `%` in pattern, invalid method, two routes with the same method and shape, and two mappers for the same exception class. A router with five bad patterns and two conflicts gives one exception that mentions all seven.
7. A table-driven test with at least 25 malformed paths over a raw socket (or `HttpClient` where the client allows it) shows 400 and that the handler is never called, covering at least: `%` without hex digits, `%G0`, incomplete UTF-8, overlong encoding (`%C0%AF`), encoded surrogate (`%ED%A0%80`), `%2F`, `%2f`, `%5C`, `%00`, `%0A`, `%1B`, `%7F`, `.`, `..`, `%2e`, `%2E%2e`, `.%2e`, empty segment `//`, and double encoding (`%252F` decodes to the segment text `%2F` and then matches one variable, not two segments; the expectation is written out in the test).
8. A test shows query parameters: multiple values in order, `+` as space, `%2B` as `+`, name without `=`, empty value, `&&`, `=` inside the value, bad encoding (400), exactly 1 000 pairs (200) and 1 001 pairs (400), and that parsing happens at most once even when the handler asks for many parameters.
9. A test shows typed parameters: every built-in `Param` with valid values, and for each built-in type at least one invalid value (including `"1.0"`, `" 5"` and `2147483648` for `INT`, `"TRUE"`/`"1"` for `BOOLEAN`, wrong case for enum) giving 400 with the parameter's name in the problem response; `requiredQuery` without a value gives 400; `pathParam` with an unknown name gives 500.
10. A test shows filters: order (outermost first, recorded in a list); that a filter can respond without calling `next`, modify the response (add a header), and pass an attribute that the handler reads via `AttributeKey`; that router filters run for 404, 405, automatic `OPTIONS` and 400 from the path check; that a group's filters run only for the group's routes and inside the router filters.
11. A test shows error handling: the closest mapper in the class hierarchy wins regardless of registration order; a `WebException` subtype without a mapper gives its status as a problem response; a mapper for `WebException` can produce a custom 404 page; an unknown `RuntimeException` gives 500 whose body contains neither the exception message nor a stack trace; a mapper that itself throws gives 500 without recursion; an exception from a filter goes to the same mappers; `RequestBodyException` (body over `maxBodySize`, chunked) gives the body's status from `spola.http`.
12. A test shows the problem response: `Content-Type: application/problem+json`; the body parses with `Json.parse` and has `type`, `title`, `status`, `instance` (and `detail` only when present); extra members are included; `detail` is truncated to at most 2 000 characters.
13. A test shows that no log line written by `spola.web` contains raw control characters, even when the path or a parameter value contains percent-encoded `%0A`, `%0D` or `%1B` and the handler throws an exception whose message contains them.
14. A test with at least 1 000 routes shows all of them match correctly, and a test with 200 concurrent clients (virtual threads) against the same router shows every client gets the right response and the right attribute (nothing leaks between requests).
15. `README.md` in `modules/web/` exists with the complete example next to the corresponding Spring Boot code, and the table of deviations from Spring MVC, as required in `mission.md`.
16. No annotations beyond the allowlist, no dependencies beyond the JDK (guardrails green), no new file types under `framework/`, no rule in `framework/REGLER.md` broken.
17. `PROGRESS.md` and `ARCHITECTURE.md` are updated, including the split of row 08 into 08a/08b, the module's dependencies, the pattern syntax, the matching order and the trie lookup, strict path and decoding, the query rules and limit, filter scope and order, attributes as typed keys on an immutable request, mapper selection and the problem format.

## Out of scope
JSON bodies in/out, `Content-Type`/`Accept`, 415/406, cookies, form bodies and multipart (08b and later); input validation beyond typed parameters (09); CORS, CSRF, authentication, sessions (10–11); static files, templates, `Range`, compression, SSE, WebSocket; proxy headers and access log; wiring to `spola.config`/`spola.di` (19). Changes to `spola.http` beyond small additions the mission needs (motivated in ARCHITECTURE.md; 07a/07b tests unchanged and green).

## Assumptions
- API names may be adjusted as long as the shape in `mission.md` holds.
- The catch-all value representation (string joined by `/` and/or a list) is the builder's choice, documented in ARCHITECTURE.md.
- Tests bind only `127.0.0.1` on port 0 and need no external network.
