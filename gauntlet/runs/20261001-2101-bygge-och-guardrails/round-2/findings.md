# Round 2 — verdict: FAIL

Judge's verification: I built a clean copy of `framework/` under /tmp (deleted afterwards). The baseline `java Build.java` gave BUILD OK, exit 0. All three round-1 MAJORs (symlinked jar, symlinked source, `getParameterAnnotations`) are fixed according to build.md's mutation table, and no critic disputes that. I reproduced two new bypasses myself:
- BRK-1: `modules/guardrails/fixtures/Evil.java` containing `@interface Inject {}` and `@javax.annotation.processing.Generated` gave "no violations" and BUILD OK, exit 0.
- BRK-2: `out/spola.guardrails.jar` copied to `lib/thing.zip` and to the extension-less `lib/thing` gave BUILD OK, exit 0. The breaker's `lib/deps.zip` (a compiled RUNTIME `@interface Inject` plus a class annotated with it) also gave BUILD OK, exit 0.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/Build.java:229-230 (`--exclude modules/guardrails/fixtures`) + Guardrails.java:46-57,87 + SelfTest (only checks that fixture *directories* are listed) — The whole fixtures root is exempt from the source and bytecode checks, not just the fixture directories in `Fixtures.ALL`. A `.java` file placed directly in `fixtures/` (or anywhere in it outside a listed fixture directory) holding a custom `@interface` or a disallowed annotation therefore stays unchecked in the repo, and the source launcher can still load it. This is a direct breach of AC4 ("custom `@interface` in any module → red") and of REGLER §2. The same hole lets stray `.class` files under fixtures/ escape the bytecode check if the self-test does not use them. — fix_hint: Exclude only the exact paths named in `Fixtures.ALL`. Report every other file under `fixtures/`, or at least every `.java`/`.class` file outside a listed fixture directory, as a violation. Also make the self-test fail on files inside a fixture directory that the fixture does not account for (for example a precompiled `.class` in `bytecode-annotation/`). Add a self-test case or fixture showing that a stray file at the fixtures root goes red.
- BRK-2 [MAJOR] framework/modules/guardrails/src/spola/guardrails/FileCheck.java (ARCHIVES is matched by file extension only) — A vendored jar becomes invisible once it is renamed (`.zip`, or no extension at all), and nothing inspects the archive's contents. That breaks REGLER §1 ("inga vendorade jar-filer"), and annotated or `@interface` bytecode inside the archive also gets past the §2 checks. Severity rises from round-1 BRK-5 (MINOR) because the breaker now shows real use of such a file (URLClassLoader in Build.java) and a jar with no extension, and because this is the same kind of bypass as round-1 BRK-1, which I upheld as MAJOR. The builder's reason for excluding `.zip` (future test data) is legitimate, so the fix should look at content, not name. — fix_hint: Read the first bytes of every file outside `out/`. A zip file (`PK\3\4`) that contains a `.class` entry or `META-INF/MANIFEST.MF` counts as FOREIGN_JAR whatever its name. Zip files without class entries can still be allowed. Add a fixture case for a jar renamed to `.zip` and for a jar with no extension. Optionally, bytecode-check the archive entries.

## Rejected
- (none)

## Duplicates
- AUD-1 → MNT-2 (hard-coded line numbers in Fixtures.ALL)

## MINOR (optional for the builder)
- BRK-3 [MINOR] FileCheck.java build-file list — Mill (`build.mill`, `build.sc`), Buck (`BUCK`), Pants (`pants.toml`), `.mvn/` and `gradle/libs.versions.toml` are not flagged. Consider flagging the `.mvn/` and `.gradle/` directories as a whole.
- MNT-1 [MINOR] Guardrails.java:44-106 — `check` is long. Extract `analyzeSources(...)` so that `check` reads as four steps.
- MNT-2 [MINOR] Fixtures.java — Expected line numbers are hard-coded. Consider `// expect: RULE` markers in the fixtures. This was raised in round 1, and the builder's maintenance note is accepted for now.
- MNT-3 [MINOR] SourceCheck.java:241-259 — Move the special cases `isAnnotationPresent`/`getDefaultValue` into a named constant and give each one a Javadoc reason.
- (carried forward) round-1 AUD-1 — Indirect reflection (MethodHandles, string-based lookup) is still a documented gap.
