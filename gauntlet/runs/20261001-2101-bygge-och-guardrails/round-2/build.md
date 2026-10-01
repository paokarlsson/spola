# Round 2 – build

## Changes
- `Rule.java`: new rule `SYMBOLIC_LINK` (REGLER §1). `FOREIGN_JAR` now covers `.jar/.jmod/.war/.ear`.
- `FileTree.java`: new `filesAndLinks(root)`. It walks without following links and returns regular files **and** symbolic links, including links to directories and dangling links. `files()` is unchanged and still returns regular files only.
- `FileCheck.java`: reports every symbolic link. Added the archive extensions `.jmod/.war/.ear`. Added the build files `BUILD`, `WORKSPACE.bzlmod`, `.bazelrc`, `.bazelversion`, `Makefile` and `GNUmakefile` (all matched case-insensitively).
- `Guardrails.java`: the file check uses `filesAndLinks`. Loose (non-module) `.java` files are now analysed one directory at a time (BRK-7).
- `Build.java`: `compile()` refuses symbolic links anywhere in a module's source tree with a `BuildFailure` that names the rule, and only compiles regular files. This means no linked file can end up in a jar. Lint is now `-Xlint:all,-deprecation,-removal`. Added a cross-reference comment to DependencyCheck.
- `SourceCheck.java`: the reflection check no longer uses a fixed list of names. A call or method reference to a `java.base` method counts as an annotation read if its erased return type (array components included) is `java.lang.annotation.Annotation` or `java.lang.reflect.AnnotatedType` (or a subtype), or if it is `AnnotatedElement.isAnnotationPresent` or `Method.getDefaultValue`. Added a comment on the `@interface` keyword lexer (MNT-1).
- `BytecodeCheck.java` / `DependencyCheck.java`: a malformed `.class` file gives `IllegalStateException("<file>: cannot read class file (...)")`. That means exit 2 with the path and no usage text.
- `SelfTest.java`: `tree.txt` now supports `dir/` and `path -> target` (symbolic links). The FILES fixtures use `filesAndLinks`. The allowlist mismatch message points to `ALLOWLIST_ROW`.
- `GuardrailsTool.java`: Javadoc explains why the WriterOutputStream bridge is safe (MNT-5).
- `Fixtures.java`: maintenance note on line numbers (MNT-2). Extended `reflective-annotation-read` (3 → 10), `foreign-jar` (2 → 5) and `foreign-build-file` (5 → 8). New fixture `symbolic-link` (5).
- Fixtures: `reflective-annotation-read/fx/Reader.java` gained new cases and negative controls (`getParameterTypes`, `getReturnType`, `isAnnotation`). Two `tree.txt` files were extended. New `fixtures/symbolic-link/tree.txt` covers a linked `.java` inside a module, a linked `.jar`, a linked directory and a dangling link.
- `PROGRESS.md`, `ARCHITECTURE.md`: updated (the symlink decision, the reflection criterion, loose-file grouping, lint flags, fixture syntax, and the REGLER table coupling).

## Verification
`java Build.java` in `framework/` (clean `out/`):
```
compiled spola.guardrails (15 files)
packaged out/spola.guardrails.jar
guardrails: source       16 .java files
guardrails: bytecode     22 .class files
guardrails: dependencies 1 module-info.class files
guardrails: files        56 files
guardrails: no violations
guardrails: allowlist ok (6 annotations match REGLER.md)
guardrails: fixture ok   source-annotation (2) / annotation-declaration (6) / bytecode-annotation (20)
guardrails: fixture ok   foreign-requires (1) / foreign-import (5) / reflective-annotation-read (10)
guardrails: fixture ok   foreign-jar (5) / foreign-build-file (8) / symbolic-link (5)
BUILD OK in 1609 ms
EXIT=0
```
I ran each mutation experiment in a throwaway copy of `framework/` under /tmp and deleted the copy afterwards.

| Experiment | Result |
|---|---|
| BRK-1a: `ln -s /tmp/r2/real.jar lib/x.jar` | exit 1: `lib/x.jar: FOREIGN_JAR ...` + `lib/x.jar: SYMBOLIC_LINK ...` |
| BRK-1b: `lib -> /tmp/r2/vendor` (directory that contains v.jar) | exit 1: `lib: SYMBOLIC_LINK ...` |
| BRK-2: Evil.java with `@javax.annotation.processing.Generated`, symlinked into `modules/guardrails/src/spola/guardrails/` | exit 1: `BUILD FAILED: symbolic link in the sources of spola.guardrails: modules/guardrails/src/spola/guardrails/Evil.java (REGLER.md 1. ...)` |
| BRK-2b: the same file symlinked as `framework/Evil.java` (loose) | exit 1: `Evil.java: SYMBOLIC_LINK ...` |
| Symlinked directory inside module src | exit 1: Build.java refuses it |
| Symlink inside the fixtures directory | exit 1: `modules/guardrails/fixtures/source-annotation/fx/Evil.java: SYMBOLIC_LINK ...` |
| BRK-3: `m.getParameterAnnotations()[0].length` in a new module class | exit 1: `.../Evil3.java:6: ANNOTATION_REFLECTION ...: java.lang.reflect.Method.getParameterAnnotations` |
| BRK-4: `@Deprecated(forRemoval = true)` on `FileTree.hasExtension` (used by 6 call sites in the module) | exit 0 |
| AC3: `@Deprecated` on `Rule.description()` | exit 0 |
| BRK-5: `BUILD`, `modules/Makefile`, `x.jmod` | exit 1: one violation per file |
| BRK-6: `junk/Bad.class` containing text | exit 1: `guardrails: junk/Bad.class: cannot read class file (Bad magic number)`, no usage text |
| BRK-7: `scripts/a/Tool.java` + `scripts/b/Tool.java` (compact sources) | exit 0, "source 18 .java files" |
| AC4: `@interface Marker` | exit 1: `.../Marker.java:3: ANNOTATION_DECLARATION ...` (+ bytecode) |
| AC5: `requires java.desktop;` / `requires foo.bar;` | exit 0 / exit 1 with `module-info.java:15` |
| AC6: empty `lib/x.jar` | exit 1: `lib/x.jar: FOREIGN_JAR ...` |
| AC8: link line removed from `symbolic-link/tree.txt` | exit 1: `expected but not reported: lib/x.jar: FOREIGN_JAR ...` + `... SYMBOLIC_LINK ...` |
| AC8: `getParameterAnnotations` replaced with `getParameterCount` in Reader.java | exit 1: `expected but not reported: fx/Reader.java:34: ...getParameterAnnotations` |
| AC8: `Makefile` removed from `foreign-build-file/tree.txt` | exit 1: `expected but not reported: Makefile: FOREIGN_BUILD_FILE ...` |

## Addressed
- BRK-1 [MAJOR]: the file check now sees every symbolic link through `FileTree.filesAndLinks` and reports it as `SYMBOLIC_LINK`, whatever it points to (file, directory or nothing). A linked jar is also reported as `FOREIGN_JAR` because of its name. New fixture `symbolic-link`, which the self-test creates with `Files.createSymbolicLink` from `tree.txt`.
- BRK-2 [MAJOR]: closed twice over. (1) `Build.java compile()` refuses links in module source trees before javac runs, and only compiles regular files. (2) Guardrails reports every link (also outside modules, e.g. loose `.java`). There is no longer any file that gets compiled without being checked.
- BRK-3 [MAJOR]: the reflection check now matches on the return type instead of a list of names. That covers `getParameterAnnotations`, the whole `getAnnotated*` family (`Method/Constructor/Field/Parameter/RecordComponent/Class/TypeVariable/AnnotatedType`) and future JDK methods. It also covers `isAnnotationPresent` and `Method.getDefaultValue`. The fixture has 7 new expected violations (calls and method references) plus negative controls.
- BRK-4 [MINOR]: `-removal` added to lint. Verified exit 0 with `forRemoval = true`.
- BRK-5 [MINOR]: Bazel `BUILD`/`WORKSPACE.bzlmod`/`.bazelrc`/`.bazelversion` and `Makefile`/`GNUmakefile` added to the build-file list. `.jmod/.war/.ear` count as jar archives. The fixtures are extended. `.zip` is deliberately not included, because a zip is not a Java artifact per se and can contain test data in the future.
- BRK-6 [MINOR]: a malformed class file is an analysis error that includes the path (exit 2, no usage text).
- BRK-7 [MINOR]: loose sources are grouped per directory.
- AUD-1 [MINOR]: still documented in PROGRESS.md (indirect reflection is not detected).
- MNT-1 [MINOR]: kept, with a comment explaining why the lexer is needed (the tree API has no position for `@interface`).
- MNT-2 [MINOR]: maintenance note in the Fixtures Javadoc and ARCHITECTURE.md. Markers inside the fixtures were not introduced (see Known gaps).
- MNT-3 [MINOR]: cross-reference comments in `Build.readRequires` and `DependencyCheck`, plus a note in ARCHITECTURE.md.
- MNT-4 [MINOR]: the failure message names the pattern and `ALLOWLIST_ROW`. Documented in ARCHITECTURE.md.
- MNT-5 [MINOR]: Javadoc explains the flush semantics (decoded per auto-flushed line, never byte by byte).

## Rebuttals
- None.

## Known gaps
- Indirect reflection (`MethodHandles`, string-based lookup) is still not detected (AUD-1, documented).
- MNT-2: line numbers in `Fixtures.ALL` are still hard-coded. I chose a maintenance note over in-fixture markers so as not to rebuild the self-test in a fix round.
- The `symbolic-link` fixture requires that the OS allows `createSymbolicLink`. That holds on Linux/macOS, but on Windows it needs developer mode or admin rights. Documented in PROGRESS.md.
- `round-1/scratch-breaker/` (gitignored, owned by the critic) contains `.jar`, `BUILD` and `Makefile` from round 1's experiments. They are outside `framework/` and untracked, and I have not touched them, but a repo-wide `find` will hit them until the orchestrator cleans up.
