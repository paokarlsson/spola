VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/test/src/spola/test/Assertions.java:316-318 and :371-372, plus Values.describeBoth
  problem: The rule "show element classes when elements read the same" is written out three times, each with its own detection logic, so a fourth collection assertion will probably copy it again.
  evidence: `Function<Object, String> show = confusing ? Values::describeTyped : Values::describe;` appears in both assertContains and assertAnyOrder. `describeBoth` has a third version of the same check.
  fix_hint: Add one helper in Values, for example `Function<Object,String> describer(boolean confusing)` or `describeAll(Object... values)`, and call it from all three places.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/Build.java:86-99 and /workspace/framework/modules/test/src/spola/test/TestTool.java:94-111
  problem: Two exit-guard hooks are layered, and they race. The exit code is 1 or 3 depending on which hook halts first.
  evidence: build.md, "Which one wins (1 or 3) is a race"
  fix_hint: Keep the race. Add a one-line comment in each hook saying why the other hook exists, so a future maintainer does not remove one as redundant.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/test/src/spola/test/TestTool.java:94-111
  problem: `run(PrintStream, PrintStream, String...)` now parses arguments, loads the entry, installs the guard and runs the suite. The guard setup is inline and would read better in a small named method.
  evidence: The anonymous guard `Thread` and the try/finally that removes the hook sit inline in `run`.
  fix_hint: Extract `installExitGuard(out, err, entry)`, returning the thread, and call it from `run`.
