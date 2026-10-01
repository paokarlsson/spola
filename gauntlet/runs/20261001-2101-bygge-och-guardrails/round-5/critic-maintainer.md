VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java:92-125
  problem: `run` does four things in one method (classify, read, choose the reading to analyse, report other readings), and the `utf8` variable is `null` when the first parsing reading is not UTF-8, which is easy to misread.
  evidence: `var utf8 = parsing.getFirst().charset().equals(StandardCharsets.UTF_8) ? parsing.getFirst() : null;`. This depends on `readings()` putting UTF-8 first, which is not stated at the use site.
  fix_hint: Extract a per-document method, and use `Optional` or a named helper such as `utf8Reading(parsing)`. Put a comment at the use site saying that UTF-8 is first by construction.
- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java:108-110
  problem: Each document re-scans the full readings list, which is quadratic. `Reading` is also used as a key in both an identity map and a value-equality set (`failed`, `removeAll`), so it is unclear which semantics matter.
  evidence: `readings.stream().filter(r -> r.file().equals(document) && parsedAt.containsKey(r))`, next to `IdentityHashMap<Reading, Integer>` and `HashSet<Reading> failed`.
  fix_hint: Group the readings by file once with `Collectors.groupingBy`. Use identity or value semantics consistently, and note which one.
- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/guardrails/src/spola/guardrails/LauncherCheck.java:55-56 and :62
  problem: `OLDEST_RELEASE = 8` is a hard-coded claim about what JDK 25 accepts, but nothing in the code verifies it. The self-test only mutates the value.
  evidence: The Javadoc says "The oldest release javac ... of JDK 25 accepts for --source". The `IllegalStateException` in `parsing` would surface a wrong value only as a confusing build failure.
  fix_hint: Add a comment saying where the number was verified, or a startup assertion that javac accepts `--source OLDEST_RELEASE`.
