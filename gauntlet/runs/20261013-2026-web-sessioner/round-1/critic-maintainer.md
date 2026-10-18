VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: framework/modules/web/src/spola/web/Sessions.java (whole class, 567 lines) and SessionEntry.java:92-103,150
  problem: Sessions does five jobs (public static facade, store with limit counter and two time trackers, id generation and hashing, cookie template, filter), and SessionEntry reaches back into it through seven package-private methods, so the entry, the state and the facade depend on each other in a circle.
  evidence: The package-private methods are `now()`, `idleTimeout()`, `claim()`, `unlink()`, `release()`, `newId()` and `keyOf()`. SessionEntry.renew calls `owner.newId()`, `owner.claim(..)` and `owner.unlink(..)`, and `end()` calls `owner.release(..)`. Sessions also holds `nextSweep`, `nothingExpiresBefore`, `count` and the `sweeps` counter.
  fix_hint: Before 11b and 11c add to this file, move store, count, sweep, claim/unlink/release and id/key creation into a package-private `SessionStore`. Let `Sessions` keep the facade, the filter and the cookie template, and let `SessionEntry` depend only on the store.

- id: MNT-2
  severity: MINOR
  where: SessionEntry.java:58,121,131; Session.java:118-124
  problem: Methods whose names read as queries change state, and one name hides its return value, so a maintainer can break the removal invariants without seeing it.
  evidence: `alive(now)` calls `end()` and so removes the session from the store and lowers `count`. `visible(view)` goes through `alive`, and `Session.toString()` calls `visible()`, so printing a Session can remove an expired one. `expireIfDue(now)` returns the next expiry `Instant` (or null), which the name does not say.
  fix_hint: Rename to say what happens (`endIfExpired`, `liveAt`) or split the check from the ending. Rename `expireIfDue` to something like `endIfExpiredElseExpiry`. Make `toString()` not call the mutating path, or note that it can.

- id: MNT-3
  severity: MINOR
  where: Session.java:41-42 and SessionEntry.java:100
  problem: `Session.generation` is a mutable package-private field that SessionEntry writes and reads under the entry's lock. The invariant lives in one field comment, and a later edit that reads it outside that lock would race without any warning.
  evidence: `long generation;` with the comment "guarded by the entry's lock", and `view.generation = generation;` inside `SessionEntry.renew`.
  fix_hint: Make the field private. Give the entry-side code accessors that are documented as "call with the entry's lock held", or keep the view's generation inside SessionEntry.

- id: MNT-4
  severity: MINOR
  where: SessionLeakTests.java:70-75, SessionSupport.java:38-44, SessionTests.java:7
  problem: The leak test depends on static state that other test files fill and on suite order, so running it alone or moving it fails with a message that does not say why.
  evidence: `assertTrue(ids.size() > 10_000, "only " + ids.size() + " ids recorded")` holds only after SessionScaleTests has run. IDS, TEXTS and LOG are `static final` in SessionSupport. The order requirement appears only as the "leak test last" comment and the order in `SessionTests.tests()`.
  fix_hint: Make the failure message name the dependency, for example "SessionLeakTests must run after SessionScaleTests". Or build the large id set inside the leak test itself.

- id: MNT-5
  severity: MINOR
  where: Sessions.java:309-321 (`invalidate` Javadoc) and SessionOptions.java:35-44 (`cookie` Javadoc)
  problem: Two behaviours that a user can run into appear only in ARCHITECTURE.md and PROGRESS.md, not in the public Javadoc.
  evidence: `getOrCreate` and `renew` document the ISE after the response is built. `invalidate` says nothing, but it then ends the session and sends no removal cookie (SessionState.invalidate: `cleared = !finished`). PROGRESS.md says that a `maxAge` set by the cookie function makes the JDK `CookieManager` send the id back quoted, so it is never looked up. The `cookie` parameter doc does not mention this.
  fix_hint: Add one sentence to the `invalidate` Javadoc and one to the `SessionOptions.cookie` parameter doc.

- id: MNT-6
  severity: MINOR
  where: Sessions.java:133 and :180-198
  problem: Small readability slips in Sessions.java.
  evidence: Line 133 is a Javadoc line of about 150 characters in a file wrapped at about 100. The field `template` and the static method `template(SessionOptions)` share a name, and the static `attributes(..)` shares a name with the `attributes` field in SessionEntry. The Javadoc "How many cleanups ran, for tests" appears on both the field and the accessor.
  fix_hint: Rewrap the line. Rename the method to `buildTemplate` and `attributes` to `copyAttributes`. Keep the comment on one of the two.
