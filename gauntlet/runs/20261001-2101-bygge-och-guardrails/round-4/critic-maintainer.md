```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:110-157
  problem: analyzeLauncherSource does five jobs in one 48-line method: the .jsh rule, UTF-8 decoding, shebang handling, the parse gate and the attribution gate. It has seven exit points, and the "unchecked" result is built before it is known to be needed.
  evidence: `var unchecked = Optional.of(List.of(new Violation(... "launcher script that is not UTF-8 text" ...)))` is only used in the catch block at line 133.
  fix_hint: Split out `decodeStrict(bytes)` and `blankShebang(text)`. Build the "not UTF-8" violation only in the branch that returns it.
- id: MNT-2
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:76 and :209
  problem: Two overloads are both named `analyze`. One is public-facing and returns Outcome. The other is private and takes a Function and returns Analysis. Their roles differ, so call sites are easy to confuse.
  evidence: Line 80 `analyze(root, fileManager -> ..., options, projectModules)` versus line 149 `analyze(root, fileManager -> List.of(source), options, Set.of())`.
  fix_hint: Rename the private one to `runAnalysis`.
- id: MNT-3
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:122 and :141
  problem: The `#!` script heuristic is an unexplained substring match on "java" in the first line. The `--release` value is taken from the running JDK, so the checker's behaviour changes with the JDK. The Javadoc and the spec say 25.
  evidence: `firstLine(bytes).toLowerCase(Locale.ROOT).contains("java")` and `String.valueOf(Runtime.version().feature())`.
  fix_hint: Add a comment, or a named constant, that states the heuristic and its known false positives. Say why the runtime version is used instead of a fixed 25.
- id: MNT-4
  severity: MINOR
  where: framework/modules/guardrails/src/spola/guardrails/Fixture.java:25-30
  problem: The LAUNCHER file filter repeats the selection in `Guardrails.analyzeLauncherSources` (`!hasExtension(".java")`). The comment says Fixture is the single definition, but this selection exists in two places.
  evidence: Fixture.java:30 `f -> !FileTree.hasExtension(f, ".java")` and Guardrails.java:133, the same lambda.
  fix_hint: Share one predicate, for example `FileTree.isLauncherCandidate`.
```
