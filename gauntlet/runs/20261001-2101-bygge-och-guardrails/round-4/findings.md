# Round 4 — verdict: FAIL

Judge's verification: I made fresh copies of the current `framework/` under /tmp, with `out/` removed, and put the breaker's inputs from `round-4/scratch-breaker/t/` into `tools/`. All four builds gave `BUILD OK`, exit 0, with every self-check fixture passing:
- `wire` (CR-only line endings): "1 of 4 other files are Java source", no violations.
- `gen` and `gen2.txt` (one Latin-1 byte): "0 of 5".
- `old.txt` (`_` as a method name): "0 of 4".
- `setup.txt` (a jshell script): "0 of 4".

I also ran each file directly with the JDK. All of them printed `inject present: true`: `java --source 25 wire`, `java --source 25 gen`, `java --source 25 gen2.txt`, `java --source 8 old.txt` and `jshell setup.txt`. The copies were deleted afterwards.

Round 3's BRK-1, BRK-2 and BRK-3 and MNT-1–3 are fixed as build.md describes, and no critic disputes that. The auditor's criteria check is accepted.

## Ruling on the evasion chain (binding)
This is the fourth round in a row where the breaker has found a new way to hide Java from the check. BRK-1–3 below all share one root cause. `analyzeLauncherSource` decides whether a file is Java with a test that is **narrower than the launcher's own**: only `\n` ends the shebang line, decoding is strict UTF-8, and parsing is tried at one release only. When the test fails, the file is **skipped as not Java**. That is fail-open, and the round-3 ruling says fail-open is a defect.

Patching each case on its own will not end the chain. Round 5 is the last round, so the fix has to make the decision fail-closed. It must not just add three more special cases.
- **Required property:** a regular non-`.java` file outside `out/` and the excluded fixture dirs may be skipped as "not Java" only when it **cannot possibly** be launcher input. Anything that is doubtful is either analysed or reported as `UNCHECKABLE_SOURCE`.
- **Acceptable designs:** either of these satisfies the requirement, and so does a combination.
  - (a) Mirror the launcher exactly. End the first line at `\r`, `\n` or `\r\n`. Decode leniently, the way the launcher does (UTF-8 with REPLACE; if strict UTF-8 fails but a lenient or ISO-8859-1 decode parses, analyse it or report it as UNCHECKABLE). Retry the parse at every release from `SourceVersion` 8..25 before concluding "not Java".
  - (b) Invert the check into an allowlist of non-`.java` files permitted in `framework/`, by name or extension, for example `*.md` and `fixtures/**/tree.txt`. Every other non-`.java` file becomes a violation, or goes through (a).
- If the builder chooses (b), it also closes BRK-4.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] SourceCheck.java:134-138 and `firstLine` at :159-165. The shebang line is cut at the first `'\n'` only. A `#!…java` script with CR line endings counts as a launcher script, but it reaches javac as `""` and gets zero violations. The launcher ends the line at `\r` and runs the whole file, including the `@interface`, the annotation use and `isAnnotationPresent`. This is in scope under the round-3 line, and it is a residual bypass of round-3's BRK-1. — fix_hint: Split lines the way `BufferedReader.readLine` does (`\r`, `\n`, `\r\n`) in both `firstLine` and the blanking step, and keep line numbers exact. Add a CR-only file to `fixtures/launcher-source/`, built through `TreeSpec` if git would normalise the line endings. Also see the ruling above.
- BRK-2 [MAJOR] SourceCheck.java:124-133. If strict UTF-8 decoding fails on a non-shebang file, the file is skipped (`Optional.empty()`). The launcher decodes leniently, so a file with a single invalid byte in a comment still runs with forbidden annotations and reflection. I reproduced this for both a compact source file (`gen`) and an explicit class (`gen2.txt`). — fix_hint: Never return "not Java" because of a decoding error. Decode leniently and parse. If the result parses, analyse it or report it as `UNCHECKABLE_SOURCE`. Add a fixture.
- BRK-3 [MAJOR] SourceCheck.java:140-147. The parse is tried only at `--release <runtime>`, and a parse failure on a non-shebang file means the file is skipped. `java --source 8 old.txt` runs the file. build.md lists this as a "known gap", but the round-3 ruling says that anything the JDK runs directly is in scope and that a bypass of it is MAJOR. Documenting a bypass does not rebut it. — fix_hint: Before concluding "not Java", retry at each supported lower release (`SourceVersion` / `--release 8..24`). If any release parses, analyse at that release, or report `UNCHECKABLE_SOURCE`. Add a fixture, for example a file that uses `_` as an identifier.

## Rejected
- AUD-1. This is not a defect. Fail-closed false alarms for shebang scripts that need a class path are intended, and build.md and PROGRESS.md already document them. No action is needed.

## Duplicates
- (none)

## MINOR (optional for the builder)
- BRK-4 [MINOR] SourceCheck.java:112 and the Guardrails.java:21-22 Javadoc. jshell scripts are recognised only by `.jsh`, yet jshell runs a script with any file name. I kept this MINOR: jshell accepts nearly any snippet text, so recognising its scripts by content has no clear end, and jshell is an interactive tool, not a loader. **However, the Javadoc's claim that "jshell scripts … are reported" is false and must be corrected or qualified.** Also list the case as a known gap in PROGRESS.md. Design (b) in the ruling closes it outright.
- MNT-1 [MINOR] SourceCheck.java:110-157. `analyzeLauncherSource` does too many jobs at once. Split out `decodeStrict`/`decodeLenient` and `blankShebang`, and build the "not UTF-8" violation lazily. This fits naturally with the BRK-1/2 fix.
- MNT-2 [MINOR] SourceCheck.java:76/:209. Rename the private `analyze` overload to something like `runAnalysis`.
- MNT-3 [MINOR] SourceCheck.java:122,141. Name or comment the `contains("java")` shebang heuristic. The `--release` value comes from `Runtime.version().feature()`, which only matters on a JDK other than 25. Either pin it to 25, matching the spec, or explain why it is not pinned. If BRK-3 is fixed with a release loop, this becomes part of that design.
- MNT-4 [MINOR] Fixture.java:30 / Guardrails.java:133. The same `!hasExtension(".java")` predicate is written twice. Share one named predicate, for example `FileTree.isLauncherCandidate`, so that `Fixture.inputs` really is the single definition.
- (carried forward) Hard-coded line numbers in Fixtures.ALL. Indirect reflection (MethodHandles or string lookup) is a documented gap.
