# Round 3 — verdict: FAIL

Judge's verification: I made fresh copies of the current `framework/` under /tmp (deleted afterwards) and added the breaker's artifacts. All five reproduce. Each gave BUILD OK, exit 0:
- `tools/wire`, the shebang Java script containing `@interface Inject` and `isAnnotationPresent`
- `lib/acme.jar.gz`
- `lib/deps.tgz`
- `lib/deps.zip` with BZip2 entries
- the build-file set: `project/plugins.sbt`, `project/build.properties`, `gradle/deps.gradle`, `bazel/deps.bzl`, `project.clj`, `deps.edn`

Each extra-file copy also passed every self-check, so the baseline is green. Round 2's BRK-1 and BRK-2 are fixed according to build.md's mutation table, and no critic disputes that.

## Where the evasion line is drawn (binding from now on)
Rounds 1–3 have each brought a new way to hide code from the file and archive checks (symlink, renamed jar, now shebang/gzip/tar). No checker can cover every possible encoding, so this is the line, and later rounds are judged against it:
- **In scope (MAJOR if bypassed):** anything the JDK can run or load **directly, with no unpack or decode step**. That covers `.java` files, Java source run by the source launcher (shebang files), zip/jar by content, and class files by content.
- **Out of scope for the machine check (MINOR at most):** content that has to be unpacked or decoded first (gzip, tar, xz, 7z, base64, encrypted, and so on). REGLER §1 still applies to such content, but it is enforced by review, not by guardrails. It must be listed as a known gap in PROGRESS.md.
- **Fail-closed:** if the checker cannot read a file it is examining, it must not count the file as clean. Treating a parse error as "allowed" is a design flaw (MINOR here, because ZipFile cannot load the file either).

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/guardrails/src/spola/guardrails/Guardrails.java:88-91 (`analyzeSources` picks Java source by the `.java` extension only) — A Java program in the source launcher's shebang format (`#!/usr/bin/env -S java --source 25`, JEP 330, with no `.java` extension) is runnable code inside `framework/`, yet it escapes the source check entirely. I reproduced it: `@interface Inject`, a non-allowlisted annotation and `isAnnotationPresent` all passed with BUILD OK, exit 0. It falls inside the scope above, since the JDK runs it directly, and REGLER §3 encourages compact source files, so such scripts are a realistic way to write tools in this project. — fix_hint: Pick source files by content as well as by name. A regular file outside `out/` whose first line starts with `#!` and contains `java` should be run through SourceCheck, for example by copying it to a scratch `X.java` without the shebang line and mapping the line numbers back. Or report it simply as a violation if that turns out more robust. Also consider `.jsh` (jshell scripts). Add a fixture case.

## Rejected
- (none)

## Severity adjusted
- BRK-2 MAJOR → MINOR — The gz/tgz part is correct and reproducible. But a gzipped jar or a jar in a tar.gz cannot be loaded by the JVM without an unpack step, so it falls outside the machine-check scope above. That is a deliberate choice, made to stop an endless chain of encodings. The BZip2 zip cannot be opened by ZipFile, and so not by the JVM's class loading either. The real defect is that the check fails open (`catch (ZipException) → false`, FileCheck.java:113). See MINOR below.

## Duplicates
- MNT-4 → round-2 MNT-2 (hard-coded line numbers in Fixtures.ALL, accepted for now)
- AUD-1 → round-2 carry-over AUD-1 (indirect reflection, a documented gap)

## MINOR (optional for the builder)
- BRK-2 [MINOR] FileCheck.java:78,113 — Fail-open on ZipException: a file with a zip signature (`PK\3\4`/`PK\5\6`) that ZipFile rejects should be reported as FOREIGN_JAR, not as harmless. Optionally unwrap gzip (1F 8B) and scan the content. In any case, document compressed and encoded archives as a known gap in PROGRESS.md.
- BRK-3 [MINOR] FileCheck.java BUILD_FILES — Match by extension `*.sbt`, `*.gradle`, `*.gradle.kts`, `*.bzl`, and add `project.clj` and `deps.edn`. `project/build.properties` is too generic to flag on its own.
- MNT-1 [MINOR] Fixture.java:42 — The `Kind.FILES` Javadoc is stale ("created as an empty file"). Point to the tree.txt syntax (links, `= zip(...)`).
- MNT-2 [MINOR] Fixture.java:20 / SelfTest.java:109 — The file selection for each kind is defined in two places. Share it in one helper or cross-reference the two.
- MNT-3 [MINOR] SelfTest.java — About 415 lines doing five jobs. Extract `TreeSpec` (`materialize`, `content`, `splitTopLevel`).
- (carried forward) Hard-coded line numbers in Fixtures.ALL. Indirect reflection (MethodHandles or string lookup) is a documented gap.
