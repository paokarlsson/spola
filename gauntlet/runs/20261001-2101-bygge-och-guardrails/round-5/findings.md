# Round 5 — verdict: FAIL

Judge's verification: `java Build.java` in `framework/` gave exit 0, BUILD OK, with all 10 fixtures passing. I copied the breaker's scratch trees `fw`, `fw2` and `fw3` to /tmp with `out/` removed. Each differs from the current `framework/` only in the one file the breaker changed or added. All three built with exit 0, BUILD OK and "no violations". I also ran each payload with the JDK:
- `fw3/docs/release.md`: `java --source 25` printed `false` and `java --source 8` printed `true` (reflective read of @Deprecated).
- `fw/.../foreign-jar/tree.txt`: `java --source 25 tree.txt` printed "ran with Evil: true".
- `fw2/.../launcher-source/tools/wire`: printed "evil true".
The /tmp copies were deleted afterwards.

Round 4's BRK-1, BRK-2 and BRK-3 are fixed for the inputs that round named, and no critic disputes that. BRK-4 and MNT-1–4 are addressed as build.md describes. Design (b), the allowlist, is accepted as the fail-closed decision for non-document files.

## Ruling on documents (binding, final)
In this round the evasion chain moved into the one remaining allowed type, `.md`. LauncherCheck tries to analyse Java inside a document *correctly*: it picks one release and analyses the text at that release. That is a promise to mirror the launcher's whole compile model (release API, charset, preview), and BRK-1 shows the mirror is still inexact. I am taking a stand once and for all. **A `.md` document that javac parses as Java, in any reading and at any release, is `UNCHECKABLE_SOURCE`. The check does not analyse it.** The builder's own known gap already says it: "Java does not belong in documents". This removes the last analyse-and-trust path, and it ends the chain. Later rounds and missions must not reopen document analysis.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] LauncherCheck.java:104-122 (`run` analyses the UTF-8 reading only at `parsedAt`, the newest parsing release) and :256-260 (`sourceOptions` uses `--source N`, which attributes against the JDK 25 API). A `.md` file that the launcher runs with `java --source 8` binds to the release-8 API, so overload resolution differs and a reflective annotation read gets through with a green build. I reproduced this. It is a fail-open bypass of code the JDK runs directly, which is MAJOR under the round-3 line and the round-4 ruling. — fix_hint: Follow the document ruling above. In `run`, any document with at least one parsing reading gets one `UNCHECKABLE_SOURCE` violation ("document that javac parses as Java"), and `analyze` is not called for documents. `newestParsingRelease` can become "parses at any release". Update the `docs/*.md` fixtures and their expected violations (one per document), and update PROGRESS.md and ARCHITECTURE.md. Keep `docs/notes.md` as the prose case that is skipped.

## Rejected
- (none)

## Severity adjusted
- BRK-2 MAJOR → MINOR. The reproduction is correct. But the spec requires the listed fixture dirs to be excluded from the main check, and those dirs already hold runnable Java that breaks REGLER on purpose (`launcher-source/tools/wire`, `docs/*.md`, `annotation-declaration/fx/Tag.java`). The tree.txt trick adds no capability that the existing fixtures lack. Nothing in `framework/` references or loads fixture files, and the only effect is unpinned extra content inside a deliberately violating test-data dir. Round 2's ruling concerned files *outside* the listed fixture dirs, and that is fixed. **Binding line:** the self-test pins each fixture's expected violation set. Byte-level integrity of fixture content beyond that is enforced by review, and must be listed as a known gap.

## Duplicates
- BRK-3 → BRK-2. Same root cause: the content of fixture inputs is not pinned beyond their expected violations.
- AUD-1 → round-4 BRK-4 and the carried-forward MINORs (jshell in `.md`, hard-coded line numbers). These are already documented gaps.

## MINOR (optional for the builder)
- BRK-2 [MINOR] Fixture.java:29 / FILES fixtures (tree.txt) and non-document LAUNCHER inputs. Fixture content is not pinned. If you want to harden it, run LauncherCheck's "parses as Java" test on every `tree.txt` and expect no hit, or pin the non-document launcher inputs by checksum in Fixtures.java. Either way, list this as a known gap in PROGRESS.md.
- MNT-1 [MINOR] LauncherCheck.java:92-125. `run` mixes four jobs, and `utf8` relies on the readings being ordered UTF-8 first. The BRK-1 fix removes most of this. Extract a per-document helper.
- MNT-2 [MINOR] LauncherCheck.java:108-110. Readings are rescanned once per document, and identity and value semantics are mixed (`IdentityHashMap`/`HashSet`). Group by file once.
- MNT-3 [MINOR] LauncherCheck.java:55-62. `OLDEST_RELEASE = 8` is not verified. Comment where it was verified, or assert at startup that javac accepts it.
- (carried forward) Hard-coded line numbers in Fixtures.ALL. Indirect reflection (MethodHandles or string lookup) is a documented gap. jshell snippets inside `.md` are a documented gap.
