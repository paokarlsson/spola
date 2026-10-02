# Round 2 — verdict: FAIL

Verified myself: `java Build.java` in `framework/` exits 0 (spola.demo 40/40, guardrails clean). Against the freshly built `out/classes` in a temp dir, I reproduced all three breaker findings. Round-1 BRK-1 and BRK-2 are fixed: a BOM in the order file is accepted, and invisible SKU characters are rejected with the line number.

## Upheld findings (the builder must address these)
- BRK-1 [MAJOR] framework/modules/config/src/spola/config/Source.java:119 (file source), seen through spola.demo's `inventory.properties` — A UTF-8 BOM at the start of the program's configuration file becomes part of the first key, and the setting is silently ignored. Reproduced: `\xEF\xBB\xBFrules.max.quantity=5` + `apple;50` gives "processed 1 orders, rejected 0", exit 0. The same file without the BOM rejects the order ("exceeds max quantity 5"). This is the same ordinary input class that round 1 upheld as MAJOR for the order file (Windows PowerShell 5.1 `-Encoding UTF8`, older Notepad, some editors), and the result is silently wrong, with no error. The program is the deliverable, and the mission explicitly covers friction in the core modules, so this is in scope. — fix_hint: Either (a) skip a leading U+FEFF in spola.config's file source, a small additive change, with a test in spola.config, an end-to-end test in spola.demo using a BOM-prefixed `inventory.properties`, and README/Javadoc notes, recording it as **Åtgärdat** under Friktion; or (b) list it honestly as a **Kvar** friction item with a code snippet and in PROGRESS "Återstår". Option (a) is preferred, because it is small and the failure is silent.

## Rejected
- AUD-1 — Not a defect. It is a note on how thorough the audit was, and the critic says no fix is needed.

## Duplicates
- (none)

## MINOR (optional for the builder)
- BRK-2 [MINOR] AuditTrail.java:68-83, InventoryApp.java:105 — Setting `--audit.file=inventory.properties` overwrites the configuration file and exits 0 (reproduced). Like round-1 BRK-3, this needs an unusual configuration mistake. fix_hint: also reject audit.file when it is the config/profile file, or write the audit to a temp file and move it into place.
- BRK-3 [MINOR] OrderProcessor.java:73-74, InventoryApp.java:125 — A Windows-1252 order file gives exit 74 with "java.nio.charset.MalformedInputException: Input length = 1" and no line number (reproduced). It fails loudly with a non-zero exit, and the README already says the order file is UTF-8 and maps unreadable files to 74, so it is not silently wrong. fix_hint: catch CharacterCodingException and report "orders.txt is not valid UTF-8" (exit 65), ideally with the line number.
- MNT-1 [MINOR] InventoryApp.java:121-127 — The UncheckedIOException-to-74 mapping appears twice. fix_hint: add a small private helper.
- MNT-2 [MINOR] AuditTrail.java:47-51 / InventoryApp.java:100 — The overwrite check runs both as a pre-check and in the constructor, which is not explained. fix_hint: add a comment saying the constructor check is a safety net.
- MNT-3 [MINOR] OrderLine.java:36-45 — `Integer.parseInt(group(2))` runs twice. fix_hint: parse into a local variable once.
- MNT-4 [MINOR] OrderProcessor.java:46-77 — The loop body does several jobs. fix_hint: extract a helper only if it grows further.
- Carried over from round 1, MNT-2 (shared sources in bootstrap): the builder's reason for not fixing it, that it is the bootstrap friction already listed as Kvar, is accepted.
