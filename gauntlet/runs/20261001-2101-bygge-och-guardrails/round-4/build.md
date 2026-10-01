# Round 4 — build

## Changes
- `SourceCheck.java`: new `analyzeLauncherSource(root, file)`. It reads a file without the `.java` extension the way the source launcher does: strict UTF-8, a leading `#!` line blanked with the newline kept (so line numbers are exact), and a `SimpleJavaFileObject` under the file's own URI with `isNameCompatible = true`, so the implicit class is named after the file as in the launcher. Options are `--release 25 --enable-preview`. The file goes through a parse-only javac task first. If that succeeds, it gets the full attributed source check. The old `analyze` now delegates to a private `analyze(root, units-fn, ...)` that returns structured diagnostics, which gives the error line numbers.
  - `.jsh` → `UNCHECKABLE_SOURCE`.
  - A `#!` line naming `java` plus non-UTF-8 content or a parse error → `UNCHECKABLE_SOURCE` (fail-closed).
  - Any file that parses but fails attribution → its violations + `UNCHECKABLE_SOURCE` at the first error line.
  - A non-shebang file that does not parse is not Java and is skipped. The launcher could not run it either.
- `Guardrails.java`: new `analyzeLauncherSources(root, notChecked, log)`. It offers every non-`.java` regular file outside `out/` and the excluded fixture dirs to the method above, including files inside module source dirs. It is called from `analyzeSources`.
- `Rule.java`: new `UNCHECKABLE_SOURCE` (§1, §2).
- `FileCheck.java`:
  - Fail-closed on `ZipException`: a file starting with `PK\3\4`/`PK\5\6` that `ZipFile` cannot open or read, whether damaged or BZip2, is `FOREIGN_JAR`. This is BRK-2.
  - New `BUILD_FILE_EXTENSIONS` (`.gradle`, `.gradle.kts`, `.sbt`, `.bzl`), plus `project.clj` and `deps.edn`. This is BRK-3.
- `TreeSpec.java` (new): `materialize`/`content`/`splitTopLevel` moved out of SelfTest, which goes from 415 to 320 lines. Added `truncated-zip(...)` content. This is MNT-3.
- `Fixture.java`: `uses(String)` is replaced by `inputs(Path dir)`, now the single definition of which files each kind reads. SelfTest uses it both to run fixtures and in the layout check (MNT-2). Fixed the stale `FILES` Javadoc (MNT-1). New kind `LAUNCHER`, which runs `Guardrails.analyzeLauncherSources` on the fixture dir, the same selection as the main check.
- New fixture `fixtures/launcher-source/tools/`, 9 expected violations:
  - `wire` (shebang, `@Retention`, `@interface`, its use, `isAnnotationPresent`)
  - `generate.txt` (non-shebang Java, `@BeanProperty`)
  - `broken` (shebang, does not parse)
  - `unresolved` (shebang, needs a class path → `FOREIGN_IMPORT` + `UNCHECKABLE_SOURCE`)
  - `setup.jsh`
  - `run.sh` and `notes.txt` (not Java, expected to be skipped)
- `foreign-jar/tree.txt`: + `lib/damaged.zip = truncated-zip(data/a.txt)`.
- `foreign-build-file/tree.txt`: + `gradle/deps.gradle`, `scripts/publish.gradle.kts`, `project/plugins.sbt`, `bazel/deps.bzl`, `project.clj`, `deps.edn`, and `project/build.properties` (which must stay allowed).
- `SelfTest.checkLayoutCheck`: + `launcher-source/tools/Tool.java` must be reported as unused.
- `PROGRESS.md`, `ARCHITECTURE.md`: launcher sources, fail-closed zip, new build-file names, `Fixture.inputs`/`TreeSpec`, fixture list. Known gaps added: compressed/encoded content (by the judge's line), old-`--source`-only non-shebang files, and false positives for shebang scripts needing a class path or other files.

## Verification
`java Build.java` in `framework/`:
```
guardrails: source       17 .java files
guardrails: source       0 of 3 other files are Java source (launcher programs, jshell scripts)
guardrails: no violations
guardrails: layout check ok (9 stray or binary files found)
guardrails: exclusion ok (only listed fixture directories are left out)
guardrails: fixture ok   launcher-source (9 expected violations)
guardrails: fixture ok   foreign-jar (14 expected violations)
guardrails: fixture ok   foreign-build-file (20 expected violations)
... (all 10 fixtures ok)
BUILD OK in 2105 ms   (exit 0)
```
Mutations, each run on a fresh copy of `framework/` under /tmp (`/tmp/mut.sh`, copies deleted afterwards):

| Mutation | Exit | Reported |
|---|---|---|
| `tools/wire` (judge's BRK-1 repro: shebang, `@interface Inject`, `isAnnotationPresent`) | 1 | `tools/wire:7` `@Retention`, `:8` `@interface wire.Inject`, `:11` `@wire.Inject`, `:13` `isAnnotationPresent` |
| `tools/t` (shebang, explicit class with `@BeanProperty`) | 1 | `tools/t:2 ANNOTATION_NOT_ALLOWED` |
| `tools/gen.txt` (no shebang, `@interface Gen`) | 1 | `tools/gen.txt:1 ANNOTATION_DECLARATION` |
| `tools/p.txt` (preview primitive patterns + `@BeanProperty`) | 1 | `tools/p.txt:2 ANNOTATION_NOT_ALLOWED` |
| `setup.jsh` | 1 | `setup.jsh: UNCHECKABLE_SOURCE jshell script` |
| `run.sh` (`#!/bin/sh`) | 0 | — (not Java) |
| `tool` with `#!/usr/bin/env myjava` + prose | 1 | `UNCHECKABLE_SOURCE launcher script javac cannot parse` (fail-closed) |
| `lib/d.zip` truncated zip (no end record) | 1 | `FOREIGN_JAR` |
| `lib/deps.zip` with BZip2 entries (judge's repro) | 1 | `FOREIGN_JAR` |
| `project/plugins.sbt`, `project/build.properties`, `gradle/deps.gradle`, `bazel/deps.bzl`, `project.clj`, `deps.edn` | 1 | all except `project/build.properties` → `FOREIGN_BUILD_FILE` |
| `@interface Inject` → `interface Inject` in the fixture `launcher-source/tools/wire` | 1 | self-test FAILED: expected `:8 @interface wire.Inject` not reported, plus unexpected entries |
| `@Deprecated` on `Allowlist.isAllowedModule` | 0 | — |
| empty `lib/x.jar` | 1 | `FOREIGN_JAR` |

`git ls-files | grep '\.jar$'` → 0. The jars under `round-*/scratch-breaker/` are critic artifacts, ignored by `.gitignore:1`.

## Addressed
- BRK-1 [MAJOR]: shebang files, and any non-`.java` file javac can parse, now get the full source check. Line numbers are exact because the shebang line is blanked, not removed. `.jsh` and uncompilable `#!…java` scripts are `UNCHECKABLE_SOURCE`. Covered by the new fixture `launcher-source` and by the mutation table.
- BRK-2 [MINOR]: fail-closed on `ZipException` for files with zip magic. Fixture entry `lib/damaged.zip`. Compressed/encoded content is documented as a known gap in PROGRESS.md. I did not add gzip unwrapping, since it is out of scope by the judge's line.
- BRK-3 [MINOR]: matching by extension, plus `project.clj` and `deps.edn`. `project/build.properties` stays allowed, and the fixture asserts that.
- MNT-1 [MINOR]: the `Kind.FILES` Javadoc now points to `TreeSpec`.
- MNT-2 [MINOR]: `Fixture.inputs(Path)` is the single selection, used by both `runFixture` and `layoutProblems`.
- MNT-3 [MINOR]: `TreeSpec` extracted.
- Carry-overs (hard-coded line numbers, indirect reflection): unchanged and still documented.

## Rebuttals
- none

## Known gaps
- A non-shebang file that only compiles at an older `--source` (e.g. uses `record` as an identifier) does not parse under javac 25 and is skipped. Documented in PROGRESS.md.
- Shebang scripts are analysed alone and with no class path. A multi-file or class-path script gives `UNCHECKABLE_SOURCE`. That is fail-closed, but it can be a false alarm. The same goes for a `#!` line where `java` is only part of a longer name.
- A damaged zip with data in front of it is not recognised. The JVM cannot load it either.
- Every non-`.java` file outside `out/` and the fixtures now goes through a javac parse. Build time went from about 1.5 s to about 2.0 s.
