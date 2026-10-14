```
VERDICT: FAIL
FINDINGS:
- id: BRK-1
  severity: MAJOR
  where: framework/modules/security/src/spola/security/BCryptPasswordHasher.java:199-202 (needsRehash = stored cost < cost) together with the unchanged Authenticator.java:13-19 guarantee; undocumented in framework/PROGRESS.md (known limitations, ~line 881-899), framework/modules/security/README.md section "BCrypt och hashar från Spring" and ARCHITECTURE.md:3110-3114
  problem: When the stored bcrypt cost is higher than the hasher's cost, e.g. hashes from Spring's `new BCryptPasswordEncoder(12)` checked with the README's `BCryptPasswordHasher.defaults()` (cost 10), a known user with a wrong password takes about 4x longer than an unknown user (the dummy hash is cost 10). needsRehash never lowers the cost, so this username-enumeration timing oracle never goes away. That breaks Authenticator's documented guarantee "Unknown users cost the same ... the same as for a known user with a wrong password". The known limitations only cover the temporary cross-algorithm case, and no doc tells a migrating developer to set the cost to at least the stored cost.
  evidence: Probe gauntlet/runs/20261013-2333-security-bcrypt/round-1/scratch-breaker/probe/spola/security/Timing.java (compiled against a copy of the module sources; `java -cp out spola.security.Timing` with JDK 25). Setup: user "ada" stored as "{bcrypt}" + BCryptPasswordHasher.of(12).hash("hemligt"); hasher = PasswordHasher.delegating("bcrypt", Map.of("bcrypt", BCryptPasswordHasher.defaults(), "pbkdf2", Pbkdf2PasswordHasher.defaults())); Authenticator.of(users, hasher, users.upgrader(hasher)). Output:
      login: Authenticated[identity=Identity[name=ada, roles=[USER]]]; stored still cost 12: true
      known user, wrong password: 304 ms; unknown user: 80 ms
      known user, wrong password: 342 ms; unknown user: 77 ms
      known user, wrong password: 307 ms; unknown user: 80 ms
      known user, wrong password: 311 ms; unknown user: 74 ms
      known user, wrong password: 305 ms; unknown user: 76 ms
    A successful login does not upgrade the user (needsRehash is false for cost 12 > 10), so the gap stays. `grep -n -i "högre kostnad\|higher cost"` over PROGRESS.md, ARCHITECTURE.md, the security README and the sources finds only "a higher cost gives false" statements, nothing about the timing consequence.
  fix_hint: needsRehash semantics and Authenticator are fixed by the mission, so document it. In the BCryptPasswordHasher Javadoc, the README section and the PROGRESS known limitations, say that the hasher's cost must be at least the highest stored cost (for example `of(12)` for hashes from `new BCryptPasswordEncoder(12)`). Otherwise known users can be told apart from unknown ones by timing, permanently. Optionally put the same advice next to the README's `defaults()` example and the comparison-table row.
```
