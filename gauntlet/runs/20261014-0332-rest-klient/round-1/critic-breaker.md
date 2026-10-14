VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/client/src/spola/client/Causes.java:47-76 (isClean), RestClient.java:420-421 (secrets), RestClient.java:435-447 (transportFailure/describe)
  problem: The secret scrubbing of transport failures fails for any request body with non-ASCII text (such as the need's own Swedish prompt "Hej, hur mår du?"). The JDK decodes the echoed status line as ISO-8859-1, so `text.contains(new String(body, UTF_8))` never matches. The JDK message then goes into getMessage(), and the ProtocolException stays as the cause, request body included.
  evidence: scratch-breaker/probe5/P5.java uses the builder's own ECHOES_BODY scenario (the server sends the request body back as its status line). With the body {"prompt":"Hej, hur mår du? QBODYMARKERQ"} the output is:
    `message: POST http://127.0.0.1:37785/v1/messages: the response is not valid HTTP/1.1 (java.net.ProtocolException: Invalid status line: "{"prompt":"Hej, hur mÃ¥r du? QBODYMARKERQ"}")`
    `marker in getMessage: true, in printed stack trace: true, cause java.net.ProtocolException: Invalid status line: "{"prompt":"Hej, hur m..r du? QBODYMARKERQ"}"`
    With the same body in ASCII ("mar"), the cause becomes the stand-in and the marker does not show (`marker in getMessage: false`). This breaks the documented guarantee in RestClient's and RestClientTransportException's Javadoc that the request body never appears in a message or cause. It is HTTP code handling data from outside, so a crafted response counts as realistic input.
  fix_hint: Also look for the body as `new String(bodyBytes, ISO_8859_1)` (and any other decoding the JDK may use), or never copy the text of a JDK ProtocolException/IOException into the message or cause and keep only the class.
- id: BRK-2
  severity: MAJOR
  where: RestClientResponseException.java constructor (message = "status N: what: " + bodyExcerpt), RestClient.java:322 and 330, RestRequest.java:187-189
  problem: getMessage(), toString() and the printed stack trace of RestClientResponseException contain header values and the request body when an ordinary server echoes them. This contradicts the binding decision "Inga huvudvärden och ingen förfrågningskropp finns i något meddelande" and RestClientException's Javadoc "No message, toString() or cause that this module creates holds a header value, the query or the request body".
  evidence: scratch-breaker/probe5/P5.java:
    (3) A FastAPI/Pydantic-style 422 that echoes the request in "input" (the normal behaviour of FastAPI's 422) gives `message: POST http://127.0.0.1:43053/v1/messages: status 422: a client error: {"detail":[...,"input":{"model":"m","messages":[{"role":"user","content":"Jag mår dåligt QBODYMARKERQ"}]}}]}` and `marker in getMessage: true, in printed stack trace: true`.
    (4) An httpbin-style /anything endpoint (200 echoing the request headers) with a codec that does not fit gives `message: ... status 200: the response body does not fit the codec: {"headers":{...,"Authorization":"Bearer sk-ant-KEYMARK-123",...}}` and `marker in getMessage: true`.
    A developer who logs the exception (`log.warn("...", e)`) writes the API key or the user's (psychological) conversation to the log. The module already holds the list of secrets (RestClient.secrets) but only applies it to transport causes.
  fix_hint: Keep bodyExcerpt() raw, as documented, but in the message replace each occurrence of a request secret (header values, query, body) in the excerpt part with a placeholder, or leave the excerpt out of the message when it contains one. This still meets "början av utdraget".
- id: BRK-3
  severity: MAJOR
  where: framework/modules/client/src/spola/client/BodyReader.java:88 (`value.length() > 18` -> -1)
  problem: A Content-Length of 19 digits (still valid for the JDK, which goes up to Long.MAX_VALUE) counts as "unknown" instead of "over the limit". The client therefore tries to read the body and waits for the whole timeout, then throws a RestClientTransportException with timedOut(). The decision says "Ett Content-Length över gränsen ger fel utan att kroppen läses ... Felet är ett RestClientResponseException med statusen".
  evidence: scratch-breaker/probe8/P8.java (the server sends only the head, timeout 5 s):
    `Content-Length 999999999999999999 (18 digits): 25 ms, RestClientResponseException: ... status 200: the response body is larger than maxResponseSize (... the body was not read)`
    `Content-Length 1000000000000000000 (19 digits): 5021 ms, RestClientTransportException: GET http://127.0.0.1:44617/x: timed out after 5000 ms`
    With the default timeout the call is held for 30 s and the status is lost. The response is data from outside, so a crafted header counts as realistic input.
  fix_hint: Treat any value made only of digits and longer than 18 digits as over the limit (for example declared = Long.MAX_VALUE), or parse it with Long.parseLong and map NumberFormatException to "too large".
- id: BRK-4
  severity: MINOR
  where: framework/modules/client/src/spola/client/Text.java:27-38 (excerpt) with BodyReader's cut at maxResponseSize / MAX_ERROR_BODY
  problem: When the client stops reading a body at a byte limit and the decoded text is under 1 000 chars (any maxResponseSize below about 3 KB), the excerpt has no "...". If the cut falls inside a multibyte character, the excerpt also ends with a U+FFFD that was never in the body. The decision says "Ett kortat utdrag slutar med ...".
  evidence: scratch-breaker/probe7/P7.java with maxResponseSize(1024) and a chunked body of "\"" + "ö"x2000:
    `/ok-o: status 200, excerpt length 513, ends with ... false, last chars [U+00F6, U+00F6, U+FFFD]`
    `/err-o: status 500, excerpt length 513, ends with ... false, last chars [U+00F6, U+00F6, U+FFFD]`
    The same happens with "€" (342 chars, no "...").
  fix_hint: Pass the Ending (TOO_LARGE/CUT) to Text.excerpt. When the body was cut, drop a trailing incomplete UTF-8 sequence and append "...".
- id: BRK-5
  severity: MINOR
  where: framework/modules/client/src/spola/client/Text.java:72-79 (mustEscape); Javadoc of RestClientResponseException ("without control characters, line separators or bidi controls") and the README ("inga ... bidi-tecken")
  problem: U+061C ARABIC LETTER MARK is a Unicode Bidi_Control character (general category Cf, strong RTL like U+200F), but it is not escaped, so the "no bidi controls / safe to log" claim does not hold. The spec's list leaves it out, so the builder followed the spec, but the docs claim more.
  evidence: scratch-breaker/probe9/P9.java: a 400 with the body "user ؜admin؜ logged in" gives `excerpt contains raw U+061C: true; directionality of U+061C: 2 (RIGHT_TO_LEFT_ARABIC), type FORMAT=true`.
  fix_hint: Add U+061C to mustEscape, or narrow the docs to the listed characters.

Also checked, with no defect found:
- A full `java Build.java` in a copy (scratch-breaker/fw) is green: spola.client 295/295, BUILD OK, exit 0.
- Connections are not reused after cut or over-limit bodies (probe2).
- Timeouts, a connect to a blackhole address, connectTimeout and close() return promptly after aborted calls, also during a 20 MB upload to a server that does not read (probe3, probe10).
- Header names that the JDK refuses are all on the module's list (probe1).
- Javadoc doclint: only private serial fields lack comments.
- Main code imports only java.* and spola.json.
- The Build.java diff is limited to MODULES and the test layer.
- On the 200-thread test: the 5 s stall happens in 8/8 rounds against spola.http and in 0/8 against a raw socket server (probe4), so it comes from spola.http, not the client.
