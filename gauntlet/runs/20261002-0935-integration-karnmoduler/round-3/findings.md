# Round 3 — verdict: PASS

Verified myself: `java Build.java` in `framework/` exits 0 (BUILD OK, spola.demo 42/42, guardrails clean, and no module other than the boundary test text mentions `requires spola.demo`). Round-2 BRK-1 (a BOM in `inventory.properties` silently ignoring the first key) is fixed with option (a): `Source.skipByteOrderMark` plus tests in spola.config and spola.demo, and Friktion point 8 is marked **Åtgärdat**. The breaker also confirmed the fix for the profile file through `optionalFile`.

## Upheld findings (the builder must address these)
- (none at BLOCKER/MAJOR)

## Rejected
- (none)

## Duplicates
- AUD-1 → round-2 BRK-2 (MINOR, carried over; the builder has stated it as a known gap)
- MNT-4 → round-2 MNT-4 (carried over)

## MINOR (optional for the builder)
- BRK-1 [MINOR, downgraded from MAJOR] framework/modules/demo/src/spola/demo/OrderProcessor.java:46-47 — The Javadoc of `process()` says the orders before a non-UTF-8 line "are decided". That is false. BufferedReader decodes ahead in blocks, so valid lines in the same block are never decided or audited. I reproduced it with `printf 'apple;1\napple;2\ncaf\xe9;1\n'`: exit 65, "orders.txt line 3: not valid UTF-8", and audit.log has "closed entries=0". Why the downgrade: the input is ordinary, but what the user sees is correct. The run fails loudly with exit 65, the reported line number is right, no summary is printed, and the audit trail truthfully records the entries it has. The user-facing README (lines 58-60) promises only exit 65 and the first bad line, and that holds. The false claim is in the Javadoc of a package-private method, and no spec criterion depends on how much of a failed run gets decided. Per rule 6, narrowing an overstated doc claim is an acceptable fix. — fix_hint: Narrow the Javadoc to "if the file is not valid UTF-8, none or only some of the earlier orders are decided". Or, for consistent semantics, check the whole file for UTF-8 before deciding any order (the code already reads all the bytes in `firstLineNotUtf8`).
- MNT-2 [MINOR] OrderProcessor.java:91-108 — `firstLineNotUtf8` counts only `\n`, while `readLine` also ends a line on a lone `\r`. Only old Mac line endings combined with invalid UTF-8 are affected, which is pathological. fix_hint: count a lone `\r` too, or document the assumption, and comment on `bytes.position()`.
- MNT-1 [MINOR] OrderProcessor.java:24-61 / Source.java:151 — The BOM-skipping rule is implemented twice. fix_hint: add a comment saying the duplication is deliberate, since the config helper is private.
- MNT-3 [MINOR] Source.java:86, 151 — Fully qualified `java.util.Collections` and `java.io.BufferedReader` are used inline. fix_hint: import them.
- MNT-4 [MINOR] OrderProcessor.process() does several jobs (carried over). fix_hint: extract a helper only if it grows.
- AUD-1 / round-2 BRK-2 [MINOR] `--audit.file=<config file>` overwrites the configuration file. This is still an unusual configuration mistake, and the builder disclosed it as a known gap. fix_hint: thread the config path through and reject it, or list it under Kvar.
