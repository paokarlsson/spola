VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/Guardrails.java:44-106
  problem: `check` does module grouping, loose-file grouping, the unlisted `module-info` guard and all four checks in one method of about 60 lines.
  evidence: Steps 1 to 4 are inline, and the source step alone holds the module walk, the loose walk, the `unlisted` guard and the per-directory grouping.
  fix_hint: Extract `analyzeSources(project, violations)` so that `check` reads as four calls.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/Fixtures.java
  problem: Expected line numbers are hard-coded and detached from the fixture files, so editing a fixture breaks the self-test in a way that is hard to trace.
  evidence: build.md "Known gaps": "line numbers in `Fixtures.ALL` are still hard-coded". The builder only added a maintenance note.
  fix_hint: Put `// expect: RULE` markers in the fixture sources and derive the expectations from them.
- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:241-259
  problem: `readsAnnotations` mixes a return-type heuristic with two hard-coded method-name special cases, so the rule is only partly explained by the type check.
  evidence: `name.equals("isAnnotationPresent")` and `name.equals("getDefaultValue")` sit before the return-type test.
  fix_hint: Move the two named exceptions into a small named constant or helper, and say in the Javadoc why each one is needed.
