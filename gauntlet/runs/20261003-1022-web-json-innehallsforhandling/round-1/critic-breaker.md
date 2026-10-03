VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/web/src/spola/web/MediaType.java:144-158 (includes), used by AcceptHeader.quality (AcceptHeader.java:206-226) and Responses.json (Responses.java:66, 88)
  problem: A common client header such as `Accept: application/json; charset=utf-8` gets 406 from `Responses.json` and `negotiate(APPLICATION_JSON)`, even though the server sends UTF-8 JSON. Spring answers 200 here, and the README deviation table does not list this difference.
  evidence: Probe.java (in /workspace/gauntlet/runs/20261003-1022-web-json-innehallsforhandling/round-1/scratch-breaker/) runs a GET route that returns `Responses.json(r, 200, ITEM, ...)` behind a real HttpServer:
      ACCEPT application/json; charset=utf-8 => HTTP/1.1 406 Not Acceptable
      ACCEPT application/json;charset=UTF-8 => HTTP/1.1 406 Not Acceptable
      ACCEPT application/json;v=1 => HTTP/1.1 406 Not Acceptable
    Probe5.java: `AcceptHeader.parse("application/json; charset=utf-8").quality(MediaType.APPLICATION_JSON)` => 0, `negotiate` => Optional.empty.
    Cause: `includes` requires every range parameter to be present in the offered type. `Responses` offers `APPLICATION_JSON` with no parameters, so a range that has `charset` never includes it, and no other range matches. Clients really send this header: Feign/RestTemplate code built on Spring's `APPLICATION_JSON_UTF8_VALUE`, and Retrofit/OkHttp `@Headers("Accept: application/json; charset=utf-8")`. Each of them would get 406 on every JSON endpoint. The README deviation table (README.md:442-446) does not mention this, and neither does ARCHITECTURE.md.
  fix_hint: Ignore `charset` in a range when matching (JSON is always UTF-8), or match on type/subtype and only use parameters to rank specificity, as Spring's `isCompatibleWith` does. Otherwise, document it as a deviation and add a test for it.

- id: BRK-2
  severity: MINOR
  where: framework/modules/web/src/spola/web/AcceptHeader.java:131-141 / MediaTypeParser.java:63-68, 155-169
  problem: Before JDK 23, `HttpURLConnection` sent the default `Accept: text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2`. Spola rejects that header as invalid, so every `Responses.json` call answers 400. Spring treats `*` as `*/*`, reads `q=.2` as 0.2 and answers 200.
  evidence: Probe5.java: `AcceptHeader.parse("text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2")` => `IllegalArgumentException: invalid media range "*; q=.2": expected '/' after the type at index 1`. WebRequest.accept() turns that into a 400 (verified over HTTP: an invalid Accept => 400 "Invalid Accept header: ..."). JDK 25 HttpURLConnection now sends `*/*` (checked with Probe4), so only clients on older JVMs are affected. This follows the binding "invalid Accept => 400" decision, but the deviation table says nothing about this well-known legacy header.
  fix_hint: Accept the legacy `*` element as `*/*`, or add a line to the README deviation table that names this header.
```

**What else I tried. All of these passed:**
- **415 cases:** text/plain, gzip, an invalid header, `charset="UTF-8"`, uppercase `APPLICATION/JSON`, OWS before `;`. The `Accept` and `Accept-Encoding` response headers are present.
- **400 cases:** a missing body or Content-Length 0. A syntax error does not leak the marker, even in a duplicate member name. The mapping `errors` list escapes control characters in paths.
- **Encode failure:** a failure in `encode` gives a 500 with no details.
- **413 for readJson:** a 413 on a keep-alive connection skips the rest of the body, so it cannot be smuggled in as a second request. Above `maxBodySize` you still get the 413 from `spola.http`, sent as Content-Length and as chunked.
- **Mappers:** a `WebException` mapper sees 415 and 400 (with `errors()`).
- **Small cases:** a UTF-8 BOM, non-ASCII round trip, merging several Accept fields, `q=1.5` gives 400.
- **Build:** `java Build.java` exits 0.

Scratch probes are in /workspace/gauntlet/runs/20261003-1022-web-json-innehallsforhandling/round-1/scratch-breaker/ (Probe.java through Probe5.java). Run them with `java --module-path /workspace/framework/out --add-modules spola.web,spola.http,spola.json ProbeN.java`.
