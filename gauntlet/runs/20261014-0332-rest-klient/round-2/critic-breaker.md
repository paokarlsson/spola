VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/client/src/spola/client/RestRequest.java:221-242 (secrets), Text.java:99-135 (hide); README.md:239-243 ("Hela undantaget i loggen")
  problem: With the need's own OpenAI header form `defaultHeader("Authorization", "Bearer " + key)`, a server that names the refused token on its own ("Invalid token: <key>") puts the key into getMessage(), toString() and the printed stack trace. Only the whole value "Bearer <key>" is a secret, never the key alone.
  evidence: scratch-breaker/p1/P1.java (a 401 server that answers {"error":{"message":"Invalid token: <token>"}}):
    `message: POST http://127.0.0.1:44283/v1/chat/completions: status 401: a client error: {"error":{"message":"Invalid token: sk-proj-KEYMARK-0123456789abcdef","type":"invalid_request_error"}}`
    `marker in getMessage: true, in printed stack trace: true`
    The Javadoc's "whole values only" limit covers this in general terms. But the README sentence "klienten skriver aldrig nyckeln i ett undantag, och när servern skickar tillbaka den döljer getMessage() varje hel förekomst av ett huvudvärde" reads as if a key that is sent back gets hidden, and for a Bearer key that does not happen. It is MINOR because the large APIs mask the token (OpenAI, DeepSeek) or do not echo it (Anthropic).
  fix_hint: In secrets(), also add the credentials after the auth scheme of Authorization and Proxy-Authorization (the text after the first space). Or say in the README and Javadoc that for "Bearer <key>" only the whole header value is hidden.
- id: BRK-2
  severity: MINOR
  where: RestClient.java:111-113, RestClientResponseException.java:41-43, modules/client/README.md:242-243, PROGRESS.md:966, the ARCHITECTURE.md section "REST-klient" ("Gränsen")
  problem: The docs say the spola.json cause "can quote up to 40 characters of the response". In fact a JsonMappingException lists up to 100 problems, each quoting up to 40 characters of a value, and quotes up to 200 characters of the exception message from a `map` function or a constructor. Its message can be up to 2 000 characters long, and it reaches the printed stack trace.
  evidence: scratch-breaker/p2/P2.java: a 200 whose three `role` values do not fit an enum codec gives
    `cause: 3 problems: $.messages[0].role: unknown value "Jag mar daligt idag och vill prata QBODY"... (49 characters); ...; $.messages[2].role: unknown value "Min mamma ar sjuk och jag ar orolig hela"... (61 characters); ...`
    `characters of the response quoted by the cause: 120`. All three markers appear in the printed stack trace. See also JsonProblem.java:11 ("at most 40 characters of a value ... (200 of an exception message)") and JsonMappingException.MAX_MESSAGE = 2000.
  fix_hint: Narrow the wording, for example: "up to 40 characters per quoted value, in up to 100 problems, and up to 200 characters of an exception message from a map or a constructor (at most 2 000 characters)".

What I tried, with no defect found:
- A full `java Build.java` in an exact copy (scratch-breaker/fw) is green: guardrails "no violations", spola.client 330/330, BUILD OK, exit=0.
- BRK-1/2/3 fixes from round 1 hold against variants (current source compiled into scratch-breaker/client):
  - A 200 echo over maxResponseSize(1024) gives `{"input":<hidden>...` (p4).
  - Chunked 400 echoes cut at maxResponseSize 1023/1024/1025/1026, where the cut falls inside "å"/"ä", show no body marker and no partial "Jag m" in the message or the trace (p4).
  - A refused connection still shows `java.net.ConnectException` (p3).
- I checked the JDK sources (java.net.http in /opt/jdk-25/lib/src.zip). Every SocketException, SSLException and HttpTimeoutException that java.net.http creates has fixed text, or wraps connection-phase errors before the request is sent. So Causes.textIsSafe cannot keep server-sent request values.
- Ordinary HTTP edge cases behave (p3): a 200 with a UTF-8 BOM parses; 103 Early Hints followed by 200 works; a 500 that trickles without end times out at 5 s with timedOut; a complete 8 KiB 400 gives a 1 003-character excerpt.
- JDK header validation is looser than HeaderRules, so the JDK's value-quoting IllegalArgumentException cannot be reached. Codecs cannot be implemented outside spola.json, so retrieve() cannot leak a non-RestClientException from decode.
- Annotations in the client module are @Override, @Serial and @FunctionalInterface; the Spring ones appear only in string literals. There are no non-.java/.md files.
