VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/Uris.java:157-191 (hasDotSegment, isDotSegment)
  problem: The ';' handling is hand-rolled index bookkeeping (a cached `semicolon` that is re-found only when `semicolon < start`) mixed into the segment loop. It is hard to see that it is correct and linear, and `isDotSegment` stops its loop at `dots < 3` with no comment on why.
  evidence: `if (semicolon >= 0 && semicolon < start) { semicolon = path.indexOf(';', start); }` followed by `isDotSegment(path, start, semicolon >= 0 && semicolon < segmentEnd ? semicolon : segmentEnd)`. ARCHITECTURE.md:4221 describes it as "delar sökvägen vid /", which is not what the code does.
  fix_hint: Split the path with `split("/", -1)`, cut each segment at its first ';' and test the remainder with a small `isDotSegment(String)`. This is still linear and removes the `start`/`end`/`semicolon` juggling. Or add a comment that explains the cache and the `dots < 3` guard.

- id: MNT-2
  severity: MINOR
  where: Javadoc and README for the credential rule (RestRequest.java:69-72 and 253-260, RestClient.java:116-125, RestClientException.java:49-52, RestClientResponseException.java:36-49 and 161-170, RestClientTransportException.java:31, client README lines 8, 36, 238-261, 469-470, 492, 502; plus PROGRESS and ARCHITECTURE)
  problem: "The text after the first space is also hidden, and for Basic the decoded user:password and the password" is spelled out in about ten places. Changing the rule, for example to cover `X-Auth` or a tab separator, means finding and editing all of them. Several `ReadmeTests` assertions pin the wording, so the copies are also hard to reduce.
  evidence: Compare the Javadoc paragraphs in RestClient.java:116-125 and RestClientResponseException.java:36-49. Both list the Bearer/bearer/Basic cases in different words.
  fix_hint: Describe the rule once, in the class Javadoc of `RestClientResponseException`. Everywhere else, link to it with `{@link}` and add one short sentence.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/RestRequest.java:240-246 and 283-288
  problem: The "decode as UTF-8, and also as ISO-8859-1 when that differs" logic is written twice, once for the body in `secrets()` and once for the Basic user-pass in `addHeaderSecrets`.
  evidence: Both places build `utf8`/`text`, then `bytewise`, then `if (!bytewise.equals(...)) add`.
  fix_hint: Extract a helper such as `addDecodings(List<String> values, byte[] bytes, Consumer<String> add)` and use it in both places. Then the charset rule (D2) lives in one place.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/client/test/spola/client/Failures.java:269, 287 and 293
  problem: Three near-identical names have three different roles: `namesCredentials(name)` is a script factory, `NAMES_CREDENTIALS` is a constant script, and `namedCredentials(setup, authorization)` makes an exception. The test server and `Setup.token()` also repeat the production parse (`value.substring(value.indexOf(' ') + 1).strip()`), so a mistake in that parse would be repeated in the test.
  evidence: `static Support.Script namesCredentials(String name)`, `static final Support.Script NAMES_CREDENTIALS = namesCredentials("Authorization");`, `static RestClientException namedCredentials(Setup setup, String authorization)`.
  fix_hint: Rename by role, for example `credentialsNamer(header)`, `NAMES_AUTHORIZATION` and `failWithOwnAuthorization(setup, value)`. Give the test server a different, simpler way to pick out the credentials, or note in a comment that it mirrors the production parse on purpose.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/client/test/spola/client/SecretTests.java:53-58, 142-149 and 156-170
  problem: The kind names are long strings repeated as literals in `Failures.all()`, in `RESPONSE_ECHOES`, in the `switch (name)` and in `kind("...")` calls. The `default ->` branch silently means "the header echo", so a new entry in `RESPONSE_ECHOES` would get the wrong expectation without any hint.
  evidence: `case "a 401 that names the bearer token on its own", "a 401 that names the token after a lower-case bearer", "a 401 that names the decoded user:password from Basic" -> List.of(AUTH_MARKER); default -> List.of(KEY_MARKER, AUTH_MARKER, TRACE_MARKER);`
  fix_hint: Use named constants for the kind names, or make the echo table rows of (kind name, expected markers). Then no `switch` with a catch-all `default` is needed.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/client/test/spola/client/Failures.java:400-401
  problem: The kind "a 401 that names the bearer token on its own" only does what its name says with the `SECRET` setup, because it relies on that setup's default Authorization header. With `Setup.PLAIN` the server answers "Missing credentials". The other two new kinds build their own header and do not depend on the setup. This hidden assumption is not visible at the kind.
  evidence: `setup -> against(NAMES_CREDENTIALS, setup, Duration.ofSeconds(10), 1 << 20)` next to `namedCredentials(setup, "bearer " + setup.token())`.
  fix_hint: Make the bearer case send its own `Bearer <token>` like the other two, or add a comment at the kind.

- id: MNT-7
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/Uris.java:103, 110 and 115, and 16-21
  problem: `pathProblem` computes `path.substring(0, pathEnd)` three times, and the new dot-segment check adds the third. `DOT_SEGMENT` and `BASE_DOT_SEGMENT` are the same sentence with only the prefix changed, so a wording change has to be made twice (and a third time in the SetupTests copies).
  evidence: `Text.ascii(path.substring(0, pathEnd))`, `firstInvalid(path.substring(0, pathEnd), false)` and `hasDotSegment(path.substring(0, pathEnd))`.
  fix_hint: Use `var target = path.substring(0, pathEnd);` once. Build the two texts from one shared tail constant.

- id: MNT-8
  severity: MINOR
  where: /home/user/spola/framework/modules/client/test/spola/client/ReadmeTests.java:106-139
  problem: The new README tests pin exact prose with a regex-flattening helper. The K2 check finds log lines with a text heuristic (`line.contains("log.warn(")`), so a future example that calls `logger.warn(` would pass unnoticed. The K3 check uses three `||` alternatives, which are loose enough that "all" is satisfied by any backticked `all`. Any rewording of the docs breaks the build, while real mistakes can get through.
  evidence: `assertTrue(readme.contains("`" + name + "`") || readme.contains("-D" + name + "=") || readme.contains("=" + name + "`"), ...)`.
  fix_hint: Pin only what matters: the compiled-source comparison that already exists, plus the property names. Drop the prose matches, or make them exact for each name.

- id: MNT-9
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/RestClientResponseException.java:76-77 and 172-176
  problem: The Javadoc of `safeExcerpt()` promises "never {@code null}", but the field is serialized and `serialVersionUID` is unchanged. ARCHITECTURE.md:4211 admits that an old serialized exception deserializes with `null`. The public contract and the design note disagree.
  evidence: `@return the excerpt with the request's values hidden, never {@code null}` against "ett gammalt serialiserat undantag får `null`".
  fix_hint: Either raise `serialVersionUID` so that an old stream fails clearly, or return `safeExcerpt == null ? "" : safeExcerpt` as `headers()` does, or add the caveat to the Javadoc.

No BLOCKER or MAJOR findings.
- I found no reflection, annotations, classpath scanning or naming conventions in the changes.
- The deviations D1 and D2 are explained in ARCHITECTURE.md and in the Javadoc.
- The new tests are table-driven and readable.
