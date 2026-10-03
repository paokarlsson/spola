VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/ConnectionInput.java:107
  problem: The `| 1` sentinel trick is used again to keep a deadline from being 0. Round 2 replaced the same trick in the handshake code with an explicit `handshaking` flag, so the two places are now inconsistent.
  evidence: `readDeadline = mustReturnBy | 1; // never 0`
  fix_hint: Use a separate volatile `reading` flag, as `handshaking` does, or document the 1 ns rounding next to the field.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/ConnectionInput.java:91-101
  problem: `fill()` computes the timeout and the deadline through two variables, `timeoutMillis` and `timeout`. The deadline-mode branch then overrides both, so the logic is harder to follow than it needs to be.
  evidence: `long mustReturnBy = now + timeoutMillis * 1_000_000L;` is overwritten in the `deadlineMode` branch.
  fix_hint: Pull the timeout and deadline calculation into a small helper, or use an if/else that assigns each value once.
- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:177-212
  problem: Over TLS, a cut at the header timeout still goes through `sendError(408)`, which can only fail quietly because the socket is already closed. A reader has to know about the guard to understand why the 408 does nothing.
  evidence: The `catch (SocketTimeoutException e)` branch calls `sendError(408)`. The build report says there is no 408 over TLS.
  fix_hint: Add a one-line comment at that branch saying the TLS cut closes the socket and the 408 write is a no-op.
