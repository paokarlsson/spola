# Round 1 – build

## Changes
- `framework/Build.java` (new): a compact source file run with `java Build.java`. It holds an explicit `MODULES` list and parses each `module-info.java` with `JavacTask.parse()` to get the `requires` and check the module name. It fails early (with file:line and the rule) on a `requires` that is neither a JDK module nor a listed module. It then sorts the modules topologically (cycles are an error), deletes `out/` and compiles each module in dependency order via `javax.tools.ToolProvider` (`--release 25 -proc:none -Xlint:all,-deprecation -Xdoclint:all/protected -Werror`), and writes one jar per module with `java.util.spi.ToolProvider.findFirst("jar")`. Finally it loads `out/spola.guardrails.jar` into a `ModuleLayer`, gets the module's `ToolProvider` (`spola-guardrails`) through `ServiceLoader`, and runs `check` followed by `selftest`. Exit code 0 means green, 1 means red.
- `framework/modules/guardrails/src/` (new module `spola.guardrails`):
  - `module-info.java`: `requires jdk.compiler`, exports `spola.guardrails`, `provides java.util.spi.ToolProvider with GuardrailsTool`.
  - Public API: `Guardrails`, `Project`, `Violation`, `Rule`, `GuardrailsTool` (all with Javadoc, which doclint enforces).
  - `SourceCheck`: `JavacTask.parse()` + `analyze()`, then `TreePathScanner` + `Trees.getElement`. It reports annotations whose FQN after attribution is outside the allowlist, unresolved annotations, `@interface`, imports from outside the JDK or the project, and reflective reads of annotations.
  - `BytecodeCheck`: `java.lang.classfile`. Covers visible and invisible annotations, parameter annotations and type annotations on class, field, method and record component, plus those inside `Code`. Also covers `AnnotationDefault`, nested annotations and `ACC_ANNOTATION` classes.
  - `DependencyCheck`: `requires` in `module-info.class` via `ModuleAttribute`.
  - `FileCheck`: `.jar` files (case-insensitive) outside `out/`, and build files from Maven, Gradle, Ant, Ivy, Bazel and sbt.
  - `SelfTest`, `Fixture`, `Fixtures`: an explicit fixture list with **exact** expected violations, compared as a multiset (anything missing or unexpected turns the build red). A fixture directory that isn't listed is also red, and so is a mismatch between the allowlist in the code and the table in REGLER.md.
- `framework/modules/guardrails/fixtures/` (8 fixtures): `source-annotation`, `annotation-declaration`, `bytecode-annotation`, `foreign-requires`, `foreign-import`, `reflective-annotation-read`, `foreign-jar`, `foreign-build-file`. The jar and build-file fixtures are `tree.txt` manifests. The self-test creates the files in a temporary directory, so the repo never contains a real `.jar` or `pom.xml` (AC2).
- `framework/PROGRESS.md` and `framework/ARCHITECTURE.md` (new).

## Verification
`java Build.java` in `framework/` after `rm -rf out`:
```
modules in build order: [spola.guardrails]
compiled spola.guardrails (15 files)
packaged out/spola.guardrails.jar
guardrails: source       16 .java files
guardrails: bytecode     22 .class files
guardrails: dependencies 1 module-info.class files
guardrails: files        55 files
guardrails: no violations
guardrails: allowlist ok (6 annotations match REGLER.md)
guardrails: fixture ok   source-annotation (2 expected violations)
guardrails: fixture ok   annotation-declaration (6 expected violations)
guardrails: fixture ok   bytecode-annotation (20 expected violations)
guardrails: fixture ok   foreign-requires (1 expected violations)
guardrails: fixture ok   foreign-import (5 expected violations)
guardrails: fixture ok   reflective-annotation-read (3 expected violations)
guardrails: fixture ok   foreign-jar (2 expected violations)
guardrails: fixture ok   foreign-build-file (5 expected violations)
BUILD OK in 1464 ms
EXIT=0
```
I ran each mutation experiment and reverted it afterwards. `git status` shows only new files.

| Experiment | Result |
|---|---|
| AC3: `@Deprecated` on a private static method (`FileTree.hasExtension`) | exit 0 |
| AC3: `@Deprecated` on a public, documented method that is used elsewhere (`Rule.description`) | exit 0 |
| AC4: new file `Marker.java` containing `@interface Marker` | exit 1: `modules/guardrails/src/spola/guardrails/Marker.java:3: ANNOTATION_DECLARATION (REGLER.md 2. Annoteringar): @interface spola.guardrails.Marker ...` (plus the bytecode finding) |
| AC4: nested `@interface Inner` in `FileTree` | exit 1: `.../FileTree.java:18: ANNOTATION_DECLARATION ... @interface spola.guardrails.FileTree.Inner` |
| AC5: `requires java.desktop;` | exit 0 |
| AC5: `requires static foo.bar;` | exit 1: `modules/guardrails/src/module-info.java:15: requires foo.bar is neither a JDK module nor a module listed in Build.java (REGLER.md 1. Inga tredjepartsberoenden)` |
| AC6: empty `framework/lib/x.jar` | exit 1: `lib/x.jar: FOREIGN_JAR (REGLER.md 1. ...)` |
| Jar inside the fixtures directory, `pom.xml` in `framework/` | exit 1 for each |
| Disallowed annotation in `Build.java` (`@javax.annotation.processing.Generated`) | exit 1: `Build.java:53: ANNOTATION_NOT_ALLOWED ...` |
| Disallowed annotation (SOURCE retention) in `package-info.java` | exit 1 with file:line |
| AC8: source fixture made valid; `@Param` removed from the bytecode fixture; `requires foo.bar` removed; `lib/x.jar` removed from tree.txt; `pom.xml` removed; `Tag` changed to `interface` | exit 1 for each, with `expected but not reported: ...` |
| Unlisted fixture directory / missing fixture directory | exit 1 for each |
| Unlisted module `modules/extra/src/module-info.java` | exit 1: `module-info.java outside every listed module (add the module to MODULES in Build.java)` |
| REGLER copy with `java.io.Serial` removed, passed to `selftest --rules` | exit 1: allowlist mismatch |
| Standalone: `java -p out/spola.guardrails.jar -m spola.guardrails/spola.guardrails.GuardrailsTool check ...` | exit 0. Unknown command gives exit 2 and usage. |
| Repo-wide `find` for jar and build files (outside `.git` and `framework/out`) | no hits |
| Non-JDK imports outside fixtures | no hits |
| `unzip -l out/spola.guardrails.jar` | only `spola/guardrails/*.class` + `module-info.class`, no fixtures |

## Addressed
- None (round 1).

## Rebuttals
- None (round 1).

## Known gaps
- **AC9 and the fixtures:** `fixtures/foreign-import/fx/Imports.java` imports `org.junit...` and `com.example.lib.*` on purpose, to test the import check. That is the same exemption the mission itself requires for `@interface` fixtures ("Fixtures ... are excluded from the main check"). No compiled code imports anything outside the JDK. Documented in ARCHITECTURE.md.
- Beyond the mission's three checks I added an import check (AC9) and a check for reflective reads of annotations (REGLER §2), each with its own fixture. Both enforce REGLER, which is binding, but a critic may see them as widening the scope.
- `-Xdoclint:all/protected -Werror` enforces Javadoc and lint-clean code in every future module. This is deliberate (REGLER §4 and §5), but it raises the bar for upcoming missions. `-deprecation` is switched off so that AC3 holds.
- The reflection check misses indirect calls (`MethodHandles`, string-based lookup).
- `--should-stop=ifError=FLOW` is a hidden javac flag (stable since JDK 9). It is only needed so that broken fixtures still get attributed in full.
- The file and source checks cover `framework/`, as the mission says, not the whole repo.
