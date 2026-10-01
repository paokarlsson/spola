```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/test/src/spola/test/TestTool.java:29-32
  problem: The "Fail closed" paragraph in the class Javadoc has one line that is much longer than the rest, and it breaks the wrapping.
  evidence: "If anything asks the JVM to exit before the run is over (a test calling {@code System.exit(0)}, say), the hook"
  fix_hint: Re-wrap the paragraph.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/test/src/spola/test/TestTool.java:60-75
  problem: The PrintWriter overload runs `copy` twice on the normal path. The try-with-resources closes the streams, then the finally block runs `copy`. If the guard also ran `copy`, the output could be duplicated, but the guard halts, so that cannot happen today. The correctness depends on that and nothing documents it.
  evidence: "Runnable copy = ...; return run(outStream, errStream, copy, args); } finally { copy.run(); }"
  fix_hint: Add a comment saying that `copy` runs exactly once, because the guard halts before the finally block can run.
- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/test/src/spola/test/TestTool.java:83
  problem: The private `run` overload is overloaded with the two public ones, and the only difference is the `Runnable` parameter. This is easy to confuse when reading.
  evidence: "private int run(PrintStream out, PrintStream err, Runnable beforeHalt, String... args)"
  fix_hint: Rename it to `runEntry`.
```
