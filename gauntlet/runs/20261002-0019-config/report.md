# Report — 20261002-0019-config

- **Status:** PASS
- **Title:** Config: type-safe configuration from files, env, args and profiles (spola.config)
- **Source:** uppdrag/04-config.md (moved to uppdrag/klara/)
- **Rounds:** 1 of 5

| Round | Verdict | Build | Commit |
|---|---|---|---|
| 1 | PASS (0 blocker, 0 major, 7 minor) | green | 886a8f6 |

## What was built
New module `spola.config` (no framework dependencies): sources (required/optional properties files, injected environment via prefix or explicit table, system properties, `--key=value` args, maps) merged with later source winning; profiles via user-supplied path function; `${key}` / `${key:default}` placeholders with cycle detection and `$${` escape; reflection-free record binding with built-in converters and custom `Function<String,T>`; all errors collected into one `ConfigException`; sensitive-key masking and a diagnostic dump showing the winning source. README compares with Spring Boot and shows `spola.di` wiring via `toInstance`. 63 new tests.

## Remaining findings (MINOR)
- BRK-1: very deep placeholder chains (~2000+) cause StackOverflowError instead of ConfigException.
- AUD-1: `forEnum` uses `Class.getSimpleName()`.
- MNT-1..5: sentinel identity compare in Placeholders; long `Builder.build()`; duplicated swallow-exception rule; fully qualified `java.net.URI`; masking scope for source errors undocumented.

## Assumptions
- Judge skipped: all critics PASS with no BLOCKER/MAJOR.
- Builder-noted design choice: binder exceptions are dropped when config problems were already recorded (to avoid leaking values).

## Stash
–
