# Round 1 — build

This run continues 20261001-2101 (EXHAUSTED). `framework/` already held the full work product, which every round of that run had marked as meeting criteria 1–10. In this round I closed the one MAJOR still blocking it (round 5 BRK-1). The fix follows the judge's binding document ruling: **documents are never analysed as Java**. I also took the cheap MINORs from that round.

## Changes
- `LauncherCheck.java`
  - Rewritten around the ruling. A `.md` document that javac parses in *any* reading (any JDK charset, `#!` line blanked as the launcher does) at *any* release gets exactly one `UNCHECKABLE_SOURCE`. Its subject is `document that javac parses as Java (read as <charset>, --source <N>)`, naming the first reading found. `analyze` and every call into `SourceCheck` are gone.
  - Readings are grouped per file. Once any reading of a document parses, the document's other readings are dropped (old MNT-1/MNT-2).
  - `OLDEST_RELEASE = 8` is removed (old MNT-3). The release loop now goes down from the running JDK until javac itself rejects the release with `compiler.err.option.removed.source`. Any other error that is not tied to a unit is still thrown (fail-closed).
- `SourceCheck.java`: removed the support for analysing without modules at `--source 8` (the `modules` flag, the `java.*` package fallback, `jdkType`) and the package-private `runAnalysis`/`Analysis`. Only `.java` files reach it now. It is back to one `analyze` method.
- `Guardrails.java`: renamed `analyzeLauncherSources` to `checkOtherFiles`, because the method no longer analyses anything. Updated the Javadoc.
- `Rule.java`: updated the `UNCHECKABLE_SOURCE` Javadoc and description ("no document may parse as Java").
- `Fixture.java`: updated the `LAUNCHER` Javadoc.
- `Fixtures.java`
  - `launcher-source` now expects one violation per Java document, with the expected reading pinned: `release.md`, `wire.md` and `latin1.md` as UTF-8 at the current release, `em.md` as ISO-8859-1, `old.md` at `--source 8`.
  - `LATEST = Runtime.version().feature()`.
- `SelfTest.java` (old BRK-2 MINOR): a `FILES` fixture's `tree.txt` that javac parses as Java in any reading is reported as an unexpected violation, so the self-test goes red.
- Fixtures `launcher-source/docs/`:
  - New: `release.md`. This is the breaker's round-5 payload. It prints `false` under `java --source 25` and `true` under `--source 8`.
  - Removed: `unresolved.md`. It tested attribution, and documents are no longer attributed.
  - Comments in `latin1.md`, `old.md`, `wire.md` and `notes.md` updated. Code and line structure are unchanged.
- `PROGRESS.md` and `ARCHITECTURE.md`: describe the new rule and the release discovery, and list the known gaps (fixture content, hard-coded line numbers, and that an `.md` which is a bare code sample turns the build red).

## Verification
`java Build.java` in `framework/`, exit 0:
```
guardrails: source       18 .java files
guardrails: source       3 other files: 0 not allowed, 3 documents (0 parse as Java)
guardrails: no violations
...
guardrails: fixture ok   launcher-source (9 expected violations)
... (all 10 fixtures ok)
BUILD OK in 3554 ms
```
Every Java doc fixture really runs with the JDK: `wire.md` prints "inject present: true", `latin1.md` prints "ran", `release.md` prints "false" (and "true" with `--source 8`), `em.md` with `-Dfile.encoding=ISO-8859-1` prints "ran 1", and `old.md` with `--source 8` runs. `notes.md` gives "no class declared".

Mutation and criteria tests, each in a fresh `/tmp` copy of `framework/` without `out/` (copies deleted afterwards):
- BRK-1 payload `docs/release.md` placed in `framework/docs/`: exit 1, `docs/release.md: UNCHECKABLE_SOURCE ... document that javac parses as Java (read as UTF-8, --source 25)`, BUILD RED.
- The round-5 breaker's `foreign-jar/tree.txt` (Java hidden behind `#!` and `/* */`): exit 1, `fixture foreign-jar: reported but not expected: tree.txt: UNCHECKABLE_SOURCE ... document that javac parses as Java`.
- `old.md` replaced with code that does not parse: exit 1, `expected but not reported: docs/old.md ... (read as UTF-8, --source 8)`. The `--source 8` expectation also shows that the release loop reaches 8 and stops cleanly at javac's rejection of 7.
- `em.md` with its class line commented out: exit 1, expected `em.md` violation not reported.
- Criterion 3 (`@Deprecated` on a method in `Allowlist.java`): exit 0.
- Criterion 4 (`Bad.java` with `@interface`): exit 1, `modules/guardrails/src/spola/guardrails/Bad.java:2: ANNOTATION_DECLARATION`.
- Criterion 5: `requires java.desktop;` gives exit 0. `requires foo.bar;` gives exit 1 (`module-info.java:14: requires foo.bar is neither a JDK module nor ...`).
- Criterion 6 (`lib/x.jar` empty): exit 1, FOREIGN_JAR plus UNCHECKABLE_SOURCE.

## Addressed (findings from the previous run, round 5)
- BRK-1 [MAJOR]: fixed as the ruling requires. A document that parses in any reading at any release is reported and never analysed. The payload is now the `docs/release.md` fixture.
- BRK-2 [MINOR]: the self-test now rejects any `tree.txt` that parses as Java. The remaining unpinned fixture content (the deliberately runnable `tools/*` and `docs/*.md`) is listed as a known gap, as the judge's line requires.
- MNT-1 [MINOR]: `run` is short now. Per-file grouping lives in `javaReadings`, and there is no UTF-8-first assumption anymore.
- MNT-2 [MINOR]: readings are dropped per file once one parses. The mix of identity and value lookups is limited to the javac unit map in `parsing`.
- MNT-3 [MINOR]: the hard-coded oldest release is gone. javac itself tells the check where to stop.

## Rebuttals
- (none)

## Known gaps
- jshell input inside an `.md` document. jshell runs text line by line, and detecting this would mean forbidding prose. This is documented.
- The content of the `launcher-source` fixtures (`tools/*`, `docs/*.md`) is pinned only by their expected violations, per the judge's round-5 line. Review covers it.
- Expected line numbers in `Fixtures.ALL` are hard-coded.
- Indirect reflection (MethodHandles or string lookup) is not detected. This is documented.
- The release discovery depends on javac's diagnostic code `compiler.err.option.removed.source`. If a future JDK renames it, the check throws and the build goes red. It fails closed, not open.
