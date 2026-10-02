```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:185-202
  problem: body.skipRest() is called for its side effect in a boolean chain (&& !body.skipRest()). It is called again as the return value of exchange(), so what true and false mean differs between the two uses.
  evidence: "&& !awaitsContinue(head, body) && !body.skipRest()) {" and "return body.skipRest(); // after a streaming response: the body framing decides"
  fix_hint: Pull the pre-response skip into a named method such as skipUnreadBodyOrFail(...), and give skipRest() a Javadoc line saying it is idempotent.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/HttpServer.java:256
  problem: A sentinel is encoded with a bit trick (deadline | 1) so that 0 can mean "not closing". The deadline is then off by up to 1 ns, and the code only works because of the comment.
  evidence: "shutdownDeadline = deadline | 1; // never 0"
  fix_hint: Derive "closing" from the closing flag, or keep a separate boolean or Long, instead of using 0 as a sentinel.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:341-344
  problem: lingerBeforeClose() does half-close, the state transition and the drain loop in one method, and the CAS condition is hard to read. The Javadoc state table does not mention that it is only reached when linger is set.
  evidence: "if (!state.compareAndSet(State.ACTIVE, State.LINGERING) && !state.compareAndSet(State.IDLE, State.LINGERING))"
  fix_hint: Extract enterLingering() and drainInput(deadline), and compute the deadline in a helper.
```
