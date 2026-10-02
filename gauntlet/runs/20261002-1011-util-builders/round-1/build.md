# Round 1 build: spola.codegen (record builders and with-methods)

## Changes

New module `framework/modules/codegen/` (`spola.codegen`, `requires jdk.compiler` only):
- `src/module-info.java`: exports `spola.codegen`, `provides ToolProvider with CodegenTool`.
- `RecordCodegen` (public API): `generate(Path, String) -> String`, `targetFile`, `interfaceName`, `SUFFIX`.
- `GeneratedFiles` (public API): `header`, `write`, `find`, `check`, `regenerate`; records `Problem`, `Written`, `Regeneration`.
- `CodegenException`: the message names the file and the record and fits on one line.
- `CodegenTool`: `ToolProvider` named `spola-codegen` with `generate FILE RECORD`, `regenerate DIR...` and `check DIR...`. Errors are printed without a stack trace. Exit codes: 0 ok, 1 error or stale file, 2 usage.
- Package-private: `RecordReader` parses with `JavacTask.parse()`, runs every check, qualifies member types and prunes imports. `RecordModel` holds the model and `Renderer` produces the fixed-layout text.
- `README.md`: the workflow (generate, implement, use, change the record and regenerate), generated code side by side with Lombok's `@Builder`, `@Builder(toBuilder = true)` and `@With`, what is supported, the errors and the limitations.
- Tests (`test/spola/codegen/`): `CodegenTests` (entry), `GenerateTests`, `ShapeTests`, `ErrorTests`, `CheckTests`, plus the helpers `InMemory` (javax.tools in-memory compile with `-Xlint:all -Xdoclint:all/protected -Werror`, a ClassLoader over the bytes, run) and `Workspace` (a temp dir).

`framework/Build.java`:
- `spola.codegen` is added to `MODULES` with its test entry `spola.codegen.CodegenTests::suite`.
- In `build()`, `spola.codegen` is compiled first. Then `checkGeneratedFiles()` runs the tool's `check` on `src/` and `test/` of every module in `MODULES`. Only after that are the other modules compiled. The build stops if `spola.codegen` requires a project module.
- `java Build.java codegen` regenerates every generated file, and `java Build.java codegen FILE RECORD` generates one. Both compile only `spola.codegen` into `out/codegen/` and run the tool from a fresh module layer through ServiceLoader, with no reflection.
- An unknown first argument is now an error. Before this change it was silently ignored.

`spola.demo`:
- `OrderLine implements OrderLineWith`. `OrderLine.parse` now builds with `OrderLineWith.builder()...build()`, so a builder is used in production code.
- `Summary.Rejection implements RejectionWith`.
- `OrderLineWith.java` and `RejectionWith.java` are generated and meant to be checked in.
- `PartsTests` has a new test that uses builders, `withQuantity`, `withLine`, `withReason` and `toBuilder`, and the compact constructor via `withX` and `build()`.

Docs:
- `ARCHITECTURE.md`: new section "Util del 2" with the API and these decisions: opt-in via `implements` plus a sealed interface, access, everything going through the canonical constructor, source parsing with member qualification and import pruning, name clashes, the header as the only way generated files are recognized, the check in every build and why it runs before compilation, explicit generation, deterministic output, and the use of reflection in tests only. The rejection of reflection and of an annotation processor is also recorded. The build section and directory tree are updated.
- `PROGRESS.md`: new section 05b, which notes that row 05 is complete. The 05b line under "Återstår" is removed and the known codegen limitations are added.
- `modules/util/README.md` links to `modules/codegen/README.md` in two places.

## Verification

`java Build.java` in `framework/` (final run):
```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config, spola.util, spola.codegen, spola.demo]
compiled spola.codegen (8 files)

generated files:
2 generated file(s) up to date
...
guardrails: no violations
...
spola.codegen: OK - 41 tests, 41 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 829 ms
spola.demo: OK - 43 tests, 43 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 421 ms
BUILD OK in 36608 ms
exit 0
```
All the other modules are also OK: guardrails 17, test 77, di 48, config 68, util 80.

AC7, done by hand. Each case was restored afterwards:
- Renaming `sku` to `article` in `OrderLine` (so the stale `OrderLineWith.java` no longer compiles) gives exit 1, and the error comes before any javac run:
  ```
  generated files:
  modules/demo/src/spola/demo/OrderLineWith.java: out of date: it no longer matches its record (the record changed, or this file was edited by hand); first difference at line 44: expected "     * Returns a copy of this record with {@code article} replaced.", found "     * Returns a copy of this record with {@code sku} replaced.". Regenerate it with: java Build.java codegen
  1 generated file(s) do not match their records; regenerate with java Build.java codegen (in framework/)
  BUILD FAILED: generated files do not match their records (see above); regenerate them with `java Build.java codegen` in framework/
  BUILD RED in 682 ms
  ```
- Adding a component (`String note`) gives the same message (line 64) and exit 1.
- Hand-editing `RejectionWith.java` (`return this;` changed to `return null;`) gives exit 1 and the message `first difference at line 77: expected "            return this;" ...`.

AC8: `java Build.java codegen` on the current tree prints `2 generated file(s), 0 rewritten`. The md5 and mtime of both generated files are unchanged before and after.

AC6 through Build.java:
- `java Build.java codegen modules/demo/src/spola/demo/Stock.java Stock` prints `modules/demo/src/spola/demo/Stock.java: cannot generate StockWith for record Stock: Stock is a class, not a record` and `CODEGEN RED`, exit 1, with no stack trace.
- Same for a missing file (`... the file does not exist`).

AC2 and AC10 by search:
- `grep -ln "^// Generated by spola.codegen" -r modules | xargs grep -n "^import"` finds nothing. The demo's generated files have no imports at all; `Summary`'s unused `java.util` imports are pruned.
- `grep -rn "java.lang.reflect\|java.lang.invoke\|enable-preview" modules/codegen/src modules/demo/src/spola/demo/*With.java` finds nothing.

## How critics can try AC7 (from `framework/`)

1. Rename a component without regenerating. Run `sed -i 's/String sku, int quantity) implements/String article, int quantity) implements/' modules/demo/src/spola/demo/OrderLine.java`, then `java Build.java; echo $?`. Expect `OrderLineWith.java: out of date ...`, `java Build.java codegen` and exit 1, with no javac error.
2. Add a component the same way, for example `int quantity, String note) implements`. It should fail the same way.
3. Edit a generated file by hand. Change any character in `modules/demo/src/spola/demo/RejectionWith.java` (a trailing space or `\r\n` is enough) and run `java Build.java`. Expect exit 1, the file's path and the command.
4. Break the header (first line) of a generated file. Expect `the spola.codegen header on its first line is damaged`.
5. Remove `implements OrderLineWith` from `OrderLine`. Expect `record OrderLine does not implement OrderLineWith yet; add ...`.
6. Restore with `git checkout -- framework/modules/demo` or by reverting your edit. After a record change, `java Build.java codegen` regenerates the file and the build is green again.

The same scenarios are covered automatically against the API in `CheckTests`. These cover an added or renamed component, three kinds of hand edit, a damaged header, a wrongly named copy, a renamed or removed record, a record that does not implement the interface, the tool's output containing the path and the command, and regenerating twice.

## Addressed
Round 1, so there are no findings yet.

## Rebuttals
None.

## Known gaps / judgement calls
- **Import pruning.** The mission says the generated file gets "samma import-deklarationer som källfilen". I keep every module, on-demand and static on-demand import. Single-type imports (also `import static`) are dropped when no component type or bound uses their simple name. A single-type import affects only its own simple name, so name resolution is unchanged. Without pruning, a record like `AuditSettings` (which imports `spola.util.ToString` for its `toString`) would make the generated file import `spola.*` from another module, which conflicts with AC2 and with "genererad kod beror bara på JDK:n och det egna recordet". This is documented in ARCHITECTURE.md and the README.
- **Production use of with-methods.** In production code `spola.demo` uses a builder (`OrderLine.parse`). The with-methods are used in `PartsTests` (test code inside `spola.demo`). I found no place in the demo's production code where a with-method would be natural without changing behaviour. AC9 says "koden i spola.demo"; the mission's example puts the builder in a test and the with-method in production, and I have done it the other way round.
- **Extra rejections beyond the spec's list.** The generator also rejects:
  - a component named `equals`
  - a record that declares its own `toBuilder()` or `withX(...)` with one parameter, even with a different type, because it would silently replace or overload the generated method
  - a component type or type parameter named `Builder`
  - the default package
  - non-UTF-8 files
  
  All of these are documented.
- **Member types inherited from supertypes** cannot be seen in a parse-only tree, so they are not qualified (documented limitation).
- **CRLF checkouts.** A checkout with `core.autocrlf=true` makes every generated file look stale, because the comparison is exact as the spec requires. A `.gitattributes` would be outside `framework/` and outside scope. This is documented in PROGRESS.md.
- **Generated files are recognized only by their header.** If someone deletes the header, the file is no longer checked (documented).
- **Test reflection.** The in-memory tests use `Class.forName(...).getConstructor().newInstance()` to create the compiled check class. This is test code only (AC10 covers the main and generated code) and is documented in ARCHITECTURE.md.
