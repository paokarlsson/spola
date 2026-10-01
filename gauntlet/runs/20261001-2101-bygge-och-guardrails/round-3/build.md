# Round 3: build

## Changes
- `Build.java`: the check now gets `--fixtures modules/guardrails/fixtures` in place of `--exclude modules/guardrails/fixtures`.
- `GuardrailsTool.java`: removed `--exclude`. `--fixtures DIR` excludes only `Fixtures.directories(DIR)`, meaning the directories of the fixtures listed in `Fixtures.ALL`. Usage text updated.
- `Fixtures.java`: new `directories(Path)`. The `bytecode-annotation` expectations gain the 17 source violations of its `.java` files, because every fixture `.java` is now source-checked. `foreign-jar` goes from 5 to 13 expected violations (content-based). `foreign-build-file` goes from 8 to 14 (BRK-3).
- `Fixture.java`: new `uses(String)` lists the files the self-test reads for each kind: `.java` for SOURCE/BYTECODE, `src/**.java` for DEPENDENCIES, `tree.txt` for FILES. Kind Javadoc updated.
- `SelfTest.java`:
  - `layoutProblems(fixturesDir)` fails on: a file directly under `fixtures/`, an unlisted directory, a file in a fixture directory that its fixture does not use, and a used file that is not UTF-8 text (for example class bytes named `.java`).
  - BYTECODE fixtures are now source-checked as well. DEPENDENCIES fixtures are source-checked (module source path) and also bytecode-checked.
  - Two self-checks of the self-test: `checkLayoutCheck` runs `layoutProblems` on a scratch tree holding 8 kinds of stray or binary file and requires the exact list. `checkOnlyListedFixturesAreExcluded` runs `Guardrails.check` on a scratch project with an `@interface` in three places: directly in `fixtures/` (must be reported), in an unlisted directory (must be reported) and in a listed fixture directory (must not be reported).
  - `tree.txt` gains generated content: `path = zip(entry, name = CONTENT, ...)`, `jmod(...)`, `script+zip(...)`, `class`. No real archive or class file is committed.
- `FileCheck.java` (BRK-2, BRK-3):
  - A file outside `out/` is `FOREIGN_JAR` either by name (as before) or by content. The content rule matches a zip file (opened with `ZipFile`, which also handles a script or jmod header in front) that holds a `.class` entry, an entry starting with `CAFEBABE`, `META-INF/MANIFEST.MF`, an archive-named entry, or a nested zip with any of these (up to 8 levels deep; deeper nesting is reported).
  - Zip files without Java code are allowed.
  - New build files: `BUCK`, `.buckconfig`, `pants.toml`, `build.mill`, `build.sc`, `libs.versions.toml`. Every file under `.mvn/`, `.gradle/`, `.mill/` and `.bsp/` is also flagged.
- `Guardrails.java`:
  - MNT-1: source analysis is extracted into `analyzeSources`, so `check` now reads as four steps.
  - The bytecode check reads files ending in `.class` and also any file that starts with `CAFEBABE`, so a renamed class file is checked too.
- `FileTree.java`: new `startsWith(Path, byte[])`.
- `SourceCheck.java`:
  - MNT-3: `isAnnotationPresent`/`getDefaultValue` are now named constants, each with Javadoc explaining why.
  - Violations are de-duplicated. javac's generated record members share the component's annotation tree, so before this change a record component annotation was reported twice in the source check. This showed up once BYTECODE fixtures were source-checked.
- `Rule.java`: the `FOREIGN_JAR` description now mentions content-based detection.
- `Project.java`: Javadoc for `excluded`.
- `ARCHITECTURE.md` and `PROGRESS.md`: updated with the exclusion model, content detection, fixture syntax and limitations.

## Verification
`java Build.java` in `framework/`, starting from a clean `out/`:
```
modules in build order: [spola.guardrails]
compiled spola.guardrails (15 files)
packaged out/spola.guardrails.jar
guardrails: source       16 .java files
guardrails: bytecode     23 .class files
guardrails: dependencies 1 module-info.class files
guardrails: files        57 files
guardrails: no violations
guardrails: allowlist ok (6 annotations match REGLER.md)
guardrails: layout check ok (8 stray or binary files found)
guardrails: exclusion ok (only listed fixture directories are left out)
guardrails: fixture ok   source-annotation (2 expected violations)
guardrails: fixture ok   annotation-declaration (6 expected violations)
guardrails: fixture ok   bytecode-annotation (37 expected violations)
guardrails: fixture ok   foreign-requires (1 expected violations)
guardrails: fixture ok   foreign-import (5 expected violations)
guardrails: fixture ok   reflective-annotation-read (10 expected violations)
guardrails: fixture ok   foreign-jar (13 expected violations)
guardrails: fixture ok   foreign-build-file (14 expected violations)
guardrails: fixture ok   symbolic-link (5 expected violations)
BUILD OK in 1796 ms
EXIT=0
```
Each mutation ran in its own throwaway copy of `framework/` under `/tmp`, deleted afterwards (script `/tmp/r3mut.sh`, also deleted).

| Experiment | Result |
|---|---|
| BRK-1: `fixtures/Evil.java` with `@interface Inject {}` + `@javax.annotation.processing.Generated` | exit 1: `fixtures/Evil.java:1: ANNOTATION_DECLARATION ... @interface Inject` and `:2: ANNOTATION_NOT_ALLOWED ...Generated` |
| `fixtures/hidden/H.java` (unlisted directory) containing `@interface` | exit 1: `fixtures/hidden/H.java:1: ANNOTATION_DECLARATION` |
| `@interface` in a new file `source-annotation/fx/Evil.java` | exit 1: `reported but not expected: fx/Evil.java:2: ANNOTATION_DECLARATION` |
| `@interface` in `foreign-requires/src/fixture.app/.../Evil.java` | exit 1: source + bytecode: `reported but not expected` (2 lines) |
| `getClass().getAnnotations()` added to `bytecode-annotation/fx/Marked.java` | exit 1: `reported but not expected: fx/Marked.java:18: ANNOTATION_REFLECTION` |
| `foreign-requires/X.java` (outside `src/`) | exit 1: `fixture layout: foreign-requires/X.java: not used by fixture foreign-requires (DEPENDENCIES)` |
| `bytecode-annotation/fx/Pre.class` | exit 1: `fixture layout: ... Pre.class: not used by fixture bytecode-annotation (BYTECODE)` |
| `foreign-jar/extra.txt` | exit 1: `fixture layout: foreign-jar/extra.txt: not used ...` |
| BRK-2: `out/spola.guardrails.jar` copied to `lib/thing.zip` | exit 1: `lib/thing.zip: FOREIGN_JAR` |
| BRK-2: the same jar copied to `lib/thing` (no extension) | exit 1: `lib/thing: FOREIGN_JAR` |
| BRK-2: breaker-style `lib/deps.zip` (compiled RUNTIME `@interface Inject` + annotated class) | exit 1: `lib/deps.zip: FOREIGN_JAR` |
| Jar with `#!/bin/sh` prepended, as `lib/run` | exit 1: `lib/run: FOREIGN_JAR` |
| `testdata/data.zip` holding only `a.txt` | exit 0 |
| Compiled `Inject.class` renamed to `lib/Inject.bin` | exit 1: `lib/Inject.bin: ANNOTATION_DECLARATION` + `ANNOTATION_NOT_ALLOWED @...Retention` |
| BRK-3: `.mvn/jvm.config` + `BUCK` | exit 1: one `FOREIGN_BUILD_FILE` per file |
| AC3: `@Deprecated` on `Rule.description()` | exit 0 |
| AC4: `@interface Marker` in spola.guardrails | exit 1: `.../Marker.java:3: ANNOTATION_DECLARATION` (+ bytecode) |
| AC5: `requires java.desktop;` / `requires foo.bar;` | exit 0 / exit 1 `module-info.java:15: requires foo.bar ...` |
| AC6: empty `lib/x.jar` | exit 1: `lib/x.jar: FOREIGN_JAR` |
| AC8: `lib/thing = zip(fx/A.txt)` (harmless) in `foreign-jar/tree.txt` | exit 1: `expected but not reported: lib/thing: FOREIGN_JAR` |
| AC8: `.mvn` line removed from `foreign-build-file/tree.txt` | exit 1: `expected but not reported: .mvn/jvm.config` |
| AC8: `@interface Visible` changed to `class Visible` in Marker.java | exit 1 (bytecode-annotation fixture fails) |

## Addressed
- BRK-1 [MAJOR]:
  - The main check now excludes only the directories of the fixtures listed in `Fixtures.ALL`. A file directly in `fixtures/`, or in an unlisted directory, is checked like any other file (verified above).
  - The self-test fails on any file inside a fixture directory that its fixture does not read (`Fixture.uses`), and on binary content in used files.
  - Every fixture `.java` now goes through the source check with exact expectations, BYTECODE and DEPENDENCIES included, and compiled DEPENDENCIES fixtures also go through the bytecode check. Nothing in a fixture directory escapes both checks.
  - The self-test proves both halves on every build (`layout check ok`, `exclusion ok`).
- BRK-2 [MAJOR]:
  - Archives are now recognised by content as well as name. This covers a renamed jar (`.zip`, no extension, prepended script, jmod header), manifest-only zips, class bytes under any entry name, archive-named entries and nested zips. Zips without Java code are still allowed.
  - Eight new expected violations in the `foreign-jar` fixture, including a jar renamed to `.zip` and a jar with no extension, plus four allowed controls. The content is generated from `tree.txt`.
  - Also closed the matching hole for loose class files: the bytecode check now picks class files by content.
- BRK-3 [MINOR]: Mill, Buck, Pants and `libs.versions.toml` are added, and the `.mvn/`, `.gradle/`, `.mill/` and `.bsp/` directories are flagged as a whole. The fixture is extended, with a `docs/mvn/` control.
- MNT-1 [MINOR]: `analyzeSources` extracted.
- MNT-2 [MINOR]: not changed (accepted for now per the judge). Line numbers for the new bytecode-annotation source expectations follow the same convention.
- MNT-3 [MINOR]: `IS_ANNOTATION_PRESENT` and `GET_DEFAULT_VALUE` are named constants, each with Javadoc giving the reason.
- AUD-1 (carried forward): indirect reflection is still documented as a gap in PROGRESS.md.

## Rebuttals
- None.

## Known gaps
- Indirect reflection (`MethodHandles`, string lookup) is still not detected.
- Nested archives that have data in front of them (script + zip *inside* another zip) are not recognised, because `ZipInputStream` reads from the start. At the top level they are recognised. Documented in PROGRESS.md.
- Classes inside a foreign archive are not bytecode-checked. The archive itself is already a violation.
- Line numbers in `Fixtures.ALL` are still hard-coded (MNT-2). `bytecode-annotation` now has 17 more of them.
- `round-1/` and `round-2/scratch-breaker/` (owned by the critics, untracked) contain `.jar`, `BUILD` and `Makefile` files from earlier experiments. They lie outside `framework/` and I have not touched them.
