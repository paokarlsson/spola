```
VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/TlsOptions.java:61-65, 269
  problem: Weak-suite detection (substring blacklist) and TLS 1.3 detection (`!suite.contains("_WITH_")`) depend on cipher-suite naming conventions, and the "why" is only partly commented.
  evidence: `boolean tls13 = !suite.contains("_WITH_");` and the `WEAK` list of name fragments
  fix_hint: Add a test or comment tying the lists to the JDK's suite names, or use a small explicit set of the TLS 1.3 suite names.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java:138
  problem: The `deadline | 1` trick avoids the 0 sentinel in `handshakeDeadline` and is easy to misread. It also changes the deadline by 1 ns.
  evidence: `handshakeDeadline = deadline | 1; // never 0`
  fix_hint: Use a separate `volatile boolean handshaking`, or an explicit `NO_DEADLINE` constant.

- id: MNT-3
  where: /workspace/framework/modules/http/src/spola/http/TlsMaterial.java:70
  severity: MINOR
  problem: A fully-qualified `java.util.Collections.list` is used inline while every other type is imported.
  evidence: `for (var alias : java.util.Collections.list(store.aliases()))`
  fix_hint: Import `Collections`.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/http/src/spola/http/Connection.java (fields `socket`, `ssl`, `handshake()`, `closeIfIdle`, `closeSocket`)
  problem: `Connection` now handles both plain and TLS in the same class, with `ssl != null` and `server.tls() == null` checks spread across five places.
  evidence: `if (server.tls() == null)` in `closeIfIdle`, `if (ssl != null)` in `lingerBeforeClose` and `closeSocket`.
  fix_hint: If a further transport feature is added, pull the plain and TLS shutdown behaviour into one small strategy or helper.
```
