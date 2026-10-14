VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/RestClient.java:317-336 and RestRequest.java:177-190
  problem: The mapping from a response to a RestClientResponseException is split between RestClient.exchange (status and size) and RestRequest.parse/unusable (JSON and codec), and exchange handles BodyReader.Ending with an if-chain in which COMPLETE (and the impossible CUT for 2xx) fall through to the success path.
  evidence: "if (body.ending() == BodyReader.Ending.DECLARED_TOO_LARGE) {...} if (body.ending() == BodyReader.Ending.TOO_LARGE) {...}" and then "return new Success(...)". A new Ending constant would silently be treated as a successful body. A switch over Ending without default would be checked by the compiler, which REGLER 4 prefers ("Kompileringsfel framför runtime-fel").
  fix_hint: Use an exhaustive switch over Ending without default in exchange. Optionally put every place that builds a RestClientResponseException in one package-private class.
- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/Uris.java:33 and :89; Text.java:101-116
  problem: Text.limit says its input is "already ASCII", but Uris.pathProblem and Uris.baseProblems call limit before ascii, the opposite order from Causes.describe and RestClientException.message. The \u-escape guard in limit uses an unexplained 6.
  evidence: Uris.java:89 `Text.ascii(Text.limit(path.substring(0, pathEnd), MAX_QUOTED_PATH))`. A path with 200 non-ASCII characters passes limit uncut and becomes about 1 200 characters in the IllegalArgumentException message. Text.java:112 `backslash > end - 6` has no comment saying that 6 is the length of \uXXXX.
  fix_hint: Call ascii first and limit second in Uris, or make limit apply ascii itself. Name the constant (ESCAPE_LENGTH = 6).
- id: MNT-3
  severity: MINOR
  where: RestClient.java:435-436 and Causes.java:30-45; HeaderRules.java:85-98 and Uris.java:143-148; Text.java:41-56 and :85-99
  problem: Small duplications that need to be changed together. Causes.isClean runs twice for a failing call, once in transportFailure and once inside Causes.safe. describe(char) is copied in HeaderRules and Uris. Text.escape and Text.ascii repeat the same loop skeleton.
  evidence: `boolean clean = Causes.isClean(failure, secrets); var cause = clean ? failure : Causes.safe(failure, secrets);` where safe() starts with `if (isClean(failure, secrets))`.
  fix_hint: Let Causes return one result (the cause plus a clean flag) or have safe take the already computed flag. Move describe(char) to Text. Let escape and ascii share one loop with a predicate.
- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/client/src/spola/client/BodyReader.java:120-225
  problem: Collector repeats the block "synchronized { if (done) return; done = true; take and clear subscription }" four times (onError, onComplete, finish, abort), and onSubscribe has a branch that is never reached. If someone fixes a race in one copy, the others can be forgotten.
  evidence: `if (limit == 0) { // never: maxResponseSize is at least 1 finish(Ending.CUT); return; }` and the same lock block in onError, onComplete, finish and abort.
  fix_hint: Extract a private `Flow.Subscription markDone()` that returns the subscription or signals that the body was already done. Remove the dead limit == 0 branch, or replace it with an assert that explains the invariant.
- id: MNT-5
  severity: MINOR
  where: Causes.java:44 (300), BodyReader.java:88 (18), BodyReader.java:111-112 (8_192, 16), Uris.java:33 (20), RestClient.java:139 vs Text.java:16 (two 8 KiB constants)
  problem: Several limits are unnamed literals, while others in the same module are named constants (MAX_QUOTED_NAME, MAX_QUOTED_PATH, MAX_CHECKED, MAX_MESSAGE_URI). RestClient.MAX_ERROR_BODY (8 << 10) and Text.EXCERPT_BYTES (8_192) are two separate 8 KiB values with different roles and no reference to each other.
  evidence: `Text.limit(Text.ascii(message), 300)`, `value.length() > 18`, `Math.min(limit, 8_192)`
  fix_hint: Name the literals. Document in Text.EXCERPT_BYTES why it is not MAX_ERROR_BODY: it also guards the excerpt of a 2xx body of up to 4 MiB.
- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/client/test/spola/client/Support.java:43-44 and ConcurrencyTests.java:52-53
  problem: Support.FAST sets every spola.http timeout to 5 s. The 200-thread test depends on that idleTimeout to finish, because of the stall that PROGRESS says was observed and not explained. Nothing in the code says so. Raising FAST above the client's 30 s timeout would make the test fail intermittently.
  evidence: PROGRESS.md "ett eller två anrop klara först när servern stänger en ledig anslutning (idleTimeout)" against `static final HttpTimeouts FAST = new HttpTimeouts(Duration.ofSeconds(5), ...)` with no comment, and a test run time of about 5 s.
  fix_hint: Add a comment on FAST that points to the known problem in PROGRESS, or give the concurrency test its own named timeouts.
- id: MNT-7
  severity: MINOR
  where: RestClient.java:102-105 and RestClientException.java:40-41 (Javadoc); test/spola/client/Failures.java:79
  problem: The Javadoc promises more than Causes does. It says that no header value appears in any cause the module creates. Causes only recognizes whole values, and a server that echoes part of a value still shows it. That limit is only in PROGRESS. The Javadoc of Failures.thrown refers to a parameter `call` that does not exist.
  evidence: Builder report "Causes recognises whole values only"; RestClientTransportException.java:24-28 describes the stand-in cause but not that limit; Failures.java:79 `/** Runs {@code call} on {@code request} ... */` for the method `thrown(RestRequest request, boolean json)`.
  fix_hint: Add one sentence about "whole values only" to the Javadoc of RestClientTransportException. Fix the Javadoc of Failures.thrown.
- id: MNT-8
  severity: MINOR
  where: /home/user/spola/framework/modules/client/test/spola/client/AiClients.java (shown verbatim in README.md:134-216)
  problem: The README example mixes Swedish and English comments inside one code block. The test requires the README block to equal the source, so a reader sees the mix.
  evidence: README.md:182 "// Samma klient för OpenAI, med ett Bearer-huvud ..." and :196 "// Felhantering som appen behöver ..." next to :139 "// the app's logger", :163 "// the API adds members over time: read the ones the app uses" and :201 "// a timeout or a refused connection goes on up".
  fix_hint: Use one language for comments in the example sources, preferably the one used in code comments elsewhere (English), and update the README block with the same text.
