VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/client/src/spola/client/RestRequest.java:292-298 (addUserPass), and the row "Basic with an empty password" in SecretTests.secretRows()
  problem: When the API key is the Basic user-id, which is a common convention (Stripe `sk_live_…:` with an empty password, BambooHR/Freshdesk/Teamwork `apikey:x`), the key is never added to `secrets()`. Only `user:` / `user:x` and the password are added, so a 401 that names the key shows it in getMessage(), toString(), the stack trace and safeExcerpt(). This misses the mission's goal ("API-nycklar i Authorization ska aldrig synas i det modulen skapar"). The placeholder password `x` is hidden everywhere instead, which garbles the message.
  evidence: Probe at round-1/scratch-breaker/probe2/Probe2.java. The client classes were compiled from the current src, and a local HttpServer answers 401 `{"error":"API key <user-id> has expired"}`.
    `defaultHeader("Authorization", "Basic " + b64("sk_live_51KEYMARK:"))` →
      getMessage : GET http://127.0.0.1:46433/v1/charges: status 401: a client error: {"error":"API key sk_live_51KEYMARK has expired"}
      safeExcerpt: {"error":"API key sk_live_51KEYMARK has expired"}
    `"Basic " + b64("c0ffee51KEYMARK:x")` →
      getMessage : ...: status 401: a client error: {"error":"API key c0ffee51KEYMARK has e<hidden>pired"}
    The test table pins this behaviour: the row "Basic with an empty password" expects exactly [`Basic YW5uYTo=`, `YW5uYTo=`, `anna:`], with no `anna`. The spec's literal K1 rule (user:lösenord plus the password) is met. But for these schemes the user-id is the secret, and the security exception in the grading rules applies.
  fix_hint: In addUserPass, also add the user-id (the text before the first `:`), at least when the password is empty or much shorter than the user-id. Add rows and a Failures kind for the key-as-username echo.
- id: BRK-2
  severity: MINOR
  where: RestClient.java:132-137 (the "Never turn on the JDK's logging" paragraph) and modules/client/README.md:257-261
  problem: The K3 warning names only `jdk.httpclient.HttpClient.log` and `jdk.internal.httpclient.debug`. But JSSE's `-Djavax.net.debug=all` (or `ssl:record:plaintext`), a common TLS-debugging switch, also dumps the plaintext HTTP request with the `Authorization` value to stdout. The docs give the impression that the two named properties are the only JDK logging that leaks keys.
  evidence: Probe at round-1/scratch-breaker/tls/Tls.java: RestClient against a local HttpsServer with `Authorization: Bearer TLSLOGMARK`. `java -Djavax.net.debug=all ...` → all.log:1215 shows `0070: ... json..Authorizat` / `0080: 69 6F 6E 3A 20 42 65 61 72 65 72 20 54 4C 53 4C  ion: Bearer TLSL` / `0090: 4F 47 4D 41 52 4B ...  OGMARK..` under "Plaintext before ENCRYPTION". With `javax.net.debug=ssl` the marker does not appear.
  fix_hint: Add `javax.net.debug` (`all`, `ssl:record:plaintext`) to the warning in the Javadoc and README, or narrow the wording to "these properties, among others".
