VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:133-193
  problem: `exchange` does five jobs in one 60-line method: build the body, call the handler, decide whether to close, write the response, and handle writer failure. The close decision is spread over four separate `if` blocks.
  evidence: `close` is set at lines 153, 156, 159, 162 and 165, and `exchange` also holds the `BodyWriterFailed` recovery logic.
  fix_hint: Extract `shouldClose(head, body, failure)` and `handleWriterFailure(...)`, so a future close rule has one place to go.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:41-44
  problem: `guarded`, `in`, `wire` and `writer` are non-final fields assigned in `run()`. `checkWrite` reads `guarded` from the guard thread without `volatile`, which is a hidden threading assumption.
  evidence: `private GuardedOutput guarded;` is read in `checkWrite` (line 257), while the neighbouring `thread` and `aborted` fields are `volatile`.
  fix_hint: Create these in the constructor or make `guarded` volatile, and comment which thread may touch which field.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:99,126,239,278
  problem: The state machine uses `compareAndSet(X, Y) && state.get() != Z` combinations that are hard to read. The valid transitions are not written down anywhere.
  evidence: `if (!state.compareAndSet(State.IDLE, State.ACTIVE) && state.get() != State.ACTIVE)` and the double `compareAndSet` into LINGERING at lines 278-279.
  fix_hint: Add a small `transition(from..., to)` helper and a transition table in the `State` Javadoc.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:302 and /workspace/framework/modules/http/src/spola/http/HttpServer.java:277
  problem: Two `closeQuietly` implementations do the same job.
  evidence: One closes a Socket and the other closes an AutoCloseable.
  fix_hint: Keep one package-private helper, for example in `Syntax` or a small `Io` class.

- id: MNT-5
  severity: MINOR
  where: /workspace/framework/modules/http/src/module-info.java
  problem: `requires static java.net.http` exists only for tests, so the production descriptor carries a test dependency. The build report says this is deliberate and documented, but `SourceRulesTests` has to police it to keep it from leaking into main code.
  evidence: build.md decision 3.
  fix_hint: When another module needs a test-only read edge, add a test-only read mechanism to `Build.java`.
