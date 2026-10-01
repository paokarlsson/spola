```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/SourceCheck.java:244-273
  problem: annotationKeywordLine hand-rolls a mini lexer (whitespace, // and /* */ comments) just to find the line of the @interface keyword, and this is hard to maintain.
  evidence: "while (i < text.length()) { ... else if (c == '/' && ... '*') { int end = text.toString().indexOf(\"*/\", i + 2);" (text.toString() is also called inside the loop)
  fix_hint: Report the line of the class's start position, or of the name position, and drop the lexer. Otherwise move it into a small, tested helper.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/Fixtures.java:15-95
  problem: Expected violations are a hard-coded list of file:line pairs kept apart from the fixture sources, so editing a fixture file means renumbering the list by hand.
  evidence: "source(\"fx/Tag.java\", 9, ...), source(\"fx/Tag.java\", 10, ...), source(\"fx/Tag.java\", 11, ...)"
  fix_hint: Mark the expected lines in the fixture sources, for example with a comment such as `// EXPECT: ANNOTATION_NOT_ALLOWED`. Alternatively, add a header comment in Fixtures explaining that line numbers must be updated when a fixture changes.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/Build.java:119 and /workspace/framework/modules/guardrails/src/spola/guardrails/DependencyCheck.java
  problem: The rule "requires must be a JDK module or a project module" is implemented twice, once in Build.java via ModuleFinder.ofSystem() and once in guardrails, so the two copies can drift apart.
  evidence: "Build.java: isJdkModule(name) && MODULES.stream().noneMatch(...)" next to "Allowlist.isAllowedModule(module, projectModules)" in SourceCheck
  fix_hint: Add a one-line comment in both places pointing at the other, or let Build.java depend only on guardrails for this check. The Build.java header already says "guardrails checks the compiled module-info.class as well", so a comment in DependencyCheck would be enough.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/SelfTest.java:28-30 and 141-158
  problem: The self-test finds the allowlist by matching a regex against the markdown table in REGLER.md, so reformatting the table breaks the build with a confusing "allowlist mismatch" message.
  evidence: "ALLOWLIST_ROW = Pattern.compile(\"^\\\\|\\\\s*`([\\\\w.]+)`\\\\s*\\\\|\\\\s*(SOURCE|CLASS|RUNTIME)\\\\s*\\\\|\")"
  fix_hint: Mention in the failure message that the table format in REGLER.md §2 is parsed, and add a short comment to ARCHITECTURE.md. The coupling is intentional but not obvious.

- id: MNT-5
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/GuardrailsTool.java:136-156
  problem: WriterOutputStream is a byte-to-char bridge that only flushes when flush() is called, and it builds a String from the buffered bytes; this is fragile and is needed only because the core code takes PrintStream.
  evidence: "ToolProvider requires this overload; delegate via PrintStreams that write to the writers."
  fix_hint: Make the internals use PrintWriter and wrap the PrintStream overload instead, or keep the bridge and add a note on why it only decodes the buffer on flush.
```
