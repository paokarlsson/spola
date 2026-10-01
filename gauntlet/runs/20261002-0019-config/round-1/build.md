# Round 1 build: spola.config

## Changes
- `framework/modules/config/src/module-info.java`: new module `spola.config`, `exports spola.config`, no `requires`.
- `framework/modules/config/src/spola/config/`
  - `Source.java`: sources as values created in code: `map`, `file`, `optionalFile`, `environment(prefix[, env])`, `environmentTable(table[, env])`, `systemProperties`, `arguments`. Sources are read at `build()`, files as UTF-8 through `Properties.load(Reader)`. The exact env prefix rule is in the Javadoc. Two env vars that give the same key are an error. A blank prefix is refused. `--key` without `=` is an error.
  - `Config.java`: immutable `Config` (`get`, `sourceOf`, `keys`, `profiles`, `isSensitive`, `describe`, `toString`, `bind`) and `Config.Builder` (`add`, `activeProfiles`/`activeProfilesFrom`, `addForEachProfile`, `sensitive(keys)`/`sensitive(predicate)`, `build`). Profile sources go exactly where `addForEachProfile` is called. `build()` collects every source, profile and placeholder problem into one `ConfigException`.
  - `Placeholders.java` (package-private): handles `${k}`, `${k:default}` (the default may nest placeholders) and the literal `$${`. It detects cycles with the full chain, reports each root cause once, and taints values built from sensitive values.
  - `Section.java`: the view a binding function reads from. It covers the built-in types (String, int, long, boolean, double, Duration, URI, Path, enum, list, Optional), custom converters and nested `section`. Reads record problems and return stand-ins. `bind` throws one `ConfigException`. A section cannot be used after its binding has finished.
  - `Converter.java`: a record holding a type name and a `Function<String,T>`. It provides the built-in constants and `forEnum(Class)`, which uses `Enum.valueOf` only. Durations accept `30s/500ms/5m/2h/1d/10us/7ns` or ISO-8601. Booleans must be exactly true/false.
  - `ConfigException.java` and `ConfigProblem.java` (sealed: `SourceError`, `PlaceholderError`, `MissingKey`, `InvalidValue`).
- `framework/modules/config/test/spola/config/`: `ConfigTests` (entry point), `SourceTests`, `ProfileTests`, `PlaceholderTests`, `BindingTests`, `SecretTests` and the helper `TempDir`. Every config file is written to `Files.createTempDirectory` and deleted afterwards.
- `framework/modules/config/README.md`: Spring Boot (`application.properties`, `@ConfigurationProperties`, `@Value`, `@Profile`, `spring.profiles.active`) next to Spola. Also covers wiring into `spola.di` with `toInstance`, the env prefix rule, the `$${` escape and the priority rules.
- `framework/Build.java`: added `spola.config` with `TestEntry("spola.config.ConfigTests", "suite")` to `MODULES`.
- `framework/ARCHITECTURE.md`: new section "Konfiguration (`spola.config`)" covering the API table and the decisions: priority order = code order, no relaxed binding (prefix/table rules), profile placement = where `addForEachProfile` is called, profile key semantics, files, arguments, placeholders, how errors are collected during binding (stand-ins plus collector, alternatives rejected), secrets/taint, immutability. The directory listing also gains the README.
- `framework/PROGRESS.md`: added mission 04 under Klart, changed Återstår to 05–20, added known config limitations.

## Verification
`java Build.java` in `framework/`, exit 0:
```
modules in build order: [spola.guardrails, spola.test, spola.di, spola.config]
compiled spola.config (8 files)
packaged out/spola.config.jar
spola.guardrails: OK - 17 tests, 17 ok, 0 failed ...
spola.test: OK - 77 tests, 77 ok, 0 failed ...
spola.di: OK - 48 tests, 48 ok, 0 failed ...
spola.config: OK - 63 tests, 63 ok, 0 failed, 0 skipped, 0 suite fixtures failed, in 91 ms
BUILD OK in 13355 ms
exit=0
```
Other checks:
- `grep -rn "java.lang.reflect\|java.lang.invoke\|requires" modules/config/src`: no hits apart from Javadoc prose. `module-info.java` has no `requires`.
- `find framework -name "*.properties" -not -path "*/out/*"`: nothing. `Allowlist.DOCUMENT_EXTENSIONS` is still `Set.of(".md")` and untouched.
- `ls -d /tmp/spola-config-test*` after the build: 0, so the temp dirs are cleaned up.
- Guardrails (annotations, dependencies, files) are green as part of the build.

Criteria to tests:
- AC3: `sources/the same key in four sources...` plus parameterized `every pair of sources...`, which covers all 12 ordered pairs, each case its own test.
- AC4: `environment prefix: removed, rest lower-cased...` (includes `DB_POOL_SIZE`, `PATH` and `app_lower`, which are not read), `environment table: ...`. Both use an injected `Map`.
- AC5: `profiles/two active profiles: later profile wins over earlier, both over the base file`, plus order and placement tests.
- AC6: `a missing optional file is ignored`, `a missing required file is an error with the path`.
- AC7: `placeholders/with and without default`, `$${ is a literal ${`, `a cycle a -> b -> a is an error with the whole chain`.
- AC8: `binding/a record with a nested record, built-in types and a custom converter` (all 12 built-ins plus the `Color` converter and a nested `Pool`).
- AC9: `two missing keys and one invalid value give one exception with all three` (checks the structured problems and the message: key, type, file source).
- AC10: `secrets/an invalid sensitive value is not in the error message or stack trace`, `the dump masks sensitive values and shows the winning source of every key` (exact dump text).

## Addressed
Round 1, so no findings yet.

## Rebuttals
None.

## Known gaps
- Each `bind` call produces its own report. To get a single report for the whole application, bind a root record and use `section` for its parts (README and ARCHITECTURE say so).
- Unknown or misspelled keys in files are not detected.
- Placeholders inside the profile key's value are not resolved (documented).
- If the binding function throws after problems were recorded, its exception is dropped on purpose to avoid leaking values (documented). That can hide an unrelated bug until the config errors are fixed.
- Enum names match exactly and are case-sensitive. List elements cannot contain commas.
- `Converter.forEnum` uses `Class.getSimpleName()` for the type name in messages. That is not `java.lang.reflect`, and the reason is documented in ARCHITECTURE.
- `Config.get` returns the real value of sensitive keys, by design: only display paths are masked.
