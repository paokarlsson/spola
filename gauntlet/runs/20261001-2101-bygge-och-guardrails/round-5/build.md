# Round 5 — build

## Changes
I used the ruling's design (b)+(a). Files without the `.java` extension now go through an allowlist, and every allowlisted document is still read the way the launcher reads it. The old content guess ("does this look like Java? if not, skip") is gone.

- `framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java` (new). This class checks every non-`.java` file outside `out/` and outside the fixture dirs.
  - Any file that is not `.java` and not a document (`Allowlist.DOCUMENT_EXTENSIONS` = `.md`) gets `UNCHECKABLE_SOURCE`, whatever it contains. That covers `#!` scripts, `.jsh`, jshell scripts under any name, and `gen`/`old.txt`/`setup.txt`-style files.
  - A document is decoded with **every** `Charset` the JDK has, with malformed input replaced as the launcher's reader does. A `#!` first line is handled like the launcher: it skips the two bytes `#!`, decodes the rest, and drops the first line up to `\r`, `\n` or `\r\n`, so line numbers stay exact. Each reading is then parsed at every release `--source 25` (with `--enable-preview`) down to `--source 8`, using one batched javac task per release with `-Xmaxerrs` raised so every unit reports its own errors.
  - If the UTF-8 reading parses, it is analysed at the newest release that parses it. If a different reading parses, the document gets `UNCHECKABLE_SOURCE` ("document that javac parses when read as <charset>"). A document that no reading parses is skipped.
  - A reading with no `;`, `{` or `\` cannot contain a declaration or import, so it is skipped without parsing.
- `SourceCheck.java`: removed `analyzeLauncherSource`, `firstLine`, `parseErrors` and `LauncherSource` (MNT-1). Renamed the private `analyze` overload to `runAnalysis`, which is now package-private (MNT-2). The Scanner now works without modules (`--source 8`): whatever resolves comes from the JDK or the file, because there is no class path, and `java.*` packages count as `java.base`. The `//` skip in `annotationKeywordLine` now also stops at `\r`.
- `Allowlist.java`: added `DOCUMENT_EXTENSIONS` and `isDocument`.
- `FileTree.java`: added `isLauncherCandidate`, the one shared predicate (MNT-4). `Guardrails.analyzeLauncherSources` and `Fixture.inputs(LAUNCHER)` both use it.
- `Guardrails.java`: `analyzeLauncherSources` now delegates to `LauncherCheck`. I corrected the class Javadoc (BRK-4: it no longer claims that jshell scripts are recognised; it describes the allowlist instead).
- `Rule.java`: the `UNCHECKABLE_SOURCE` Javadoc and description now describe the allowlist.
- `SelfTest.java`: inputs of `LAUNCHER` fixtures may be any bytes; the UTF-8 text requirement still applies to the other kinds.
- `Fixtures.java` and `fixtures/launcher-source/` were rebuilt. Every file in the new set except `docs/notes.md` was run with the JDK and actually executes:
  - `tools/wire`, `run.sh`, `setup.jsh` and `setup.txt` (jshell with no `.jsh`) are each reported as not a document.
  - `docs/wire.md`: a `#!` script with **CR-only** line endings (BRK-1), analysed with exact line numbers. It runs with `java --source 25 docs/wire.md` and prints "inject present: true".
  - `docs/latin1.md`: one Latin-1 byte (BRK-2). The UTF-8 reading is analysed, and the ISO-8859-1 reading is reported.
  - `docs/old.md`: uses `_` as a method name, so only `--source 8` parses it (BRK-3). It is analysed at release 8, including `isAnnotationPresent`.
  - `docs/em.md`: Java only under `-Dfile.encoding=ISO-8859-1`, reported.
  - `docs/unresolved.md`: an attribution failure, which fails closed.
  - `docs/notes.md`: prose with `;` and `{`, skipped.
- `PROGRESS.md` and `ARCHITECTURE.md`: describe the new design and its known gaps.

## Verification
`java Build.java` in `framework/` → exit 0:
```
guardrails: source       18 .java files
guardrails: source       3 other files: 0 not allowed, 3 documents (0 parse as Java)
...
guardrails: no violations
guardrails: fixture ok   launcher-source (17 expected violations)
... (all 10 fixtures ok)
BUILD OK in 3526 ms
```
I made a fresh copy of `framework/` in /tmp with `out/` removed and put the round-4 breaker inputs (`wire`, `gen`, `gen.txt`, `gen2.txt`, `old.txt`, `setup.txt`) plus `.md` variants of the attacks into `tools/`. The result was `BUILD RED`, with 17 violations. Each breaker file gets "neither a .java source nor a document". `old.md` is analysed at `--source 8`, `wire.md` (CR-only) has the right line numbers, and `em.md` and `latin1.md` are reported "read as ISO-8859-1".

Other checks, all in /tmp copies:
- A UTF-16BE `.md` that runs with `java -Dfile.encoding=UTF-16BE --source 25` was reported as "document that javac parses when read as UTF-16". Red.
- Criteria 3–6 still hold. `@Deprecated` gives OK; an `@interface` gives red with `Bad.java:2`; `requires java.desktop` gives OK; `requires foo.bar` gives red; `lib/x.jar` gives red (FOREIGN_JAR, plus not a document).
- Mutation test: setting `OLDEST_RELEASE = 25` makes the self-test red, with the three `docs/old.md` violations reported as "expected but not reported".
- Launcher behaviour I checked directly: JDK 25 honours `-Dfile.encoding` for the source launcher (ISO-8859-1, GBK, UTF-16 all take effect; EBCDIC falls back to UTF-8). A compact source file whose name does not end in `.java` fails with "bad file name", so the doc fixtures declare their classes. The check mirrors this because it compiles under the real file name.

## Addressed
- BRK-1: the `#!` line now ends at `\r`, `\n` or `\r\n`, as `readLine` ends it, after the bytes `#!`. Non-`.md` `#!` scripts are rejected outright. Fixture: `docs/wire.md` (CR-only).
- BRK-2: no decoding error ever leads to "not Java". Non-documents are rejected. Documents are decoded leniently with every charset, the UTF-8 reading is analysed, and any other parsing reading is reported. Fixtures: `docs/latin1.md` and `docs/em.md`.
- BRK-3: parsing is retried at `--source 25..8`, and the document is analysed at the newest release that parses it. `SourceCheck` was made to work without modules. Fixture: `docs/old.md`.
- Ruling (fail-closed): only `.java` and `.md` may exist. A `.md` is skipped only when no reading in any JDK charset at any release 8..25 parses.
- BRK-4: closed for every name except `.md` (by the allowlist). The Javadoc is corrected, and the remaining `.md`+jshell case is listed in PROGRESS.md.
- MNT-1: the launcher logic moved out of `SourceCheck` into `LauncherCheck`, split into `readings`, `launcherText`, `mayDeclare`, `newestParsingRelease`/`parsing` and `analyze`.
- MNT-2: `analyze` overload renamed to `runAnalysis`.
- MNT-3: the `contains("java")` heuristic is gone. The release comes from `Runtime.version().feature()`, with a comment explaining why: it is the launcher of the running JDK, and only that release takes `--enable-preview`.
- MNT-4: `FileTree.isLauncherCandidate` is now shared.

## Rebuttals
- (none)

## Known gaps
- jshell input inside a `.md` document. jshell runs any text line by line, so a `.md` file can carry snippets that `jshell X.md` executes. Detecting this would mean forbidding prose, so it is documented in PROGRESS.md.
- An `.md` file that is real Java and contains non-ASCII characters is always reported, because its ISO-8859-1 reading differs. This is intentional (fail-closed). Java does not belong in documents.
- Any new file type in `framework/` (resources, test data, editor swap files) turns the build red until `Allowlist.DOCUMENT_EXTENSIONS` is extended on purpose.
- The document check adds about 1 s to the build.
- Carried forward: hard-coded fixture line numbers, and indirect reflection (MethodHandles or string lookup).
