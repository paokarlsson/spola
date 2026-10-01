# Spec: Bygge och guardrails

## Task
Build a JDK-25-only build system (`framework/Build.java`, source launcher) and a `guardrails`
module that machine-verifies the ground rules on every build (source annotations, bytecode
annotations, dependencies/foreign build files), self-tested against fixtures.
Full mission text: `mission.md` (source: `uppdrag/01-bygge-och-guardrails.md`). All "Krav" in
mission.md are binding requirements.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/`

## Verification command
`java Build.java` in `framework/` (exit 0 = green).

## Acceptance criteria
1. `java Build.java` in `framework/` exits with code 0.
2. The repo contains no files from Maven, Gradle or other build tools, and no `.jar` files
   outside `framework/out/`.
3. Adding `@Deprecated` to a method in any module still gives a green build.
4. Adding a custom `@interface` in any module gives a red build, with file and line in the error.
5. Adding `requires java.desktop;` is allowed; adding `requires foo.bar;` (not a project
   module) gives a red build.
6. Placing an empty file `framework/lib/x.jar` gives a red build.
7. Fixtures exist for: disallowed annotation in source, annotation declaration, disallowed
   annotation in bytecode, disallowed `requires`, foreign jar file, build file from an
   external tool. The build verifies every fixture.
8. Removing a fixture's expected violation (e.g. changing it to valid code) gives a red build.
9. No packages other than the JDK's are imported anywhere.
10. `PROGRESS.md` and `ARCHITECTURE.md` exist and describe what has been built.

Additionally binding (from mission "Krav"): module list explicit in Java code (no directory
scanning for modules); compile via `javax.tools.ToolProvider` in dependency order derived from
`module-info.java`; one jar per module in `framework/out/` via `ToolProvider.findFirst("jar")`;
source check uses Compiler Tree API (`JavacTask`, `Trees`) with attribution; bytecode check uses
`java.lang.classfile` incl. parameter and type annotations; every violation reports file, line
(for source) and broken rule; fixtures are not compiled into any artifact and are excluded from
the main check.

## Out of scope
Test runner (next mission); incremental builds, caching, parallel compilation; publishing artifacts.

## Assumptions
- Module naming follows CLAUDE.md: JPMS module `spola.guardrails`, packages under `spola.guardrails`.
- Round limit: 5 (from mission.md).
