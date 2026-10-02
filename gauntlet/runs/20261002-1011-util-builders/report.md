# Report: 20261002-1011-util-builders

- **Status:** PASS
- **Title:** Util part 2: record builders and with-methods via generated source
- **Mission:** uppdrag/05b-util-builders.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 4

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 8 minor) | green | b4faeaa |

## What was built
- The new module `spola.codegen` parses a record's source with the Compiler Tree API and generates a checked-in sealed `<Record>With` interface with `withX`, `toBuilder()`, `builder()` and a nested `Builder`.
- `java Build.java codegen [file Record]` generates the files. Every build regenerates them in memory before javac runs and turns red on stale or hand-edited files.
- `OrderLine` and `Summary.Rejection` in `spola.demo` use the generated code.
- README, ARCHITECTURE and PROGRESS are updated, and row 05 is complete.

## Remaining findings (MINOR, not verified by a judge)
- BRK-1: a private member type used as a component type generates code that doesn't compile. It should be rejected with a clear error.
- BRK-2: unused on-demand imports and `import module` imports are copied into the generated file. This is a latent risk of `spola.*` imports leaking in (AC2).
- BRK-3: a syntax error in a record file is reported as a stale generated file.
- AUD-1: the with-methods are used only in demo tests.
- MNT-1 to MNT-4: long methods in `RecordReader` and `Renderer`, duplicated I/O helpers, and an outdated Javadoc about imports.

## Assumptions and deviations
- The builder drops single-type imports that no component type uses (a deviation from "same imports as the source file") so that AC2 holds.
- Known gap: on a `core.autocrlf=true` checkout, every generated file looks stale (see round-1/build.md).

## Stash
–
