# Spec: 20261002-0019-config

## Task
Build `spola.config`: type-safe configuration replacing Spring Boot's `@ConfigurationProperties`,
`@Value`, `application.properties` and profiles. Values from properties files, environment,
system properties, command-line arguments and maps, merged in an explicit priority order and
bound to records through user-written functions (no reflection). All binding errors are reported
together at startup. Full mission: `mission.md` (from `uppdrag/04-config.md`) — its Krav section
is binding in full.

## Ground rules
Everything in `framework/REGLER.md` applies. A violation is a BLOCKER.

## Work product
`framework/` (new module under `framework/modules/config/`, plus `Build.java`, `PROGRESS.md`,
`ARCHITECTURE.md`).

## Verification command
`java Build.java` in `framework/` — must exit 0.

## Acceptance criteria
1. `java Build.java` in `framework/` exits 0, and `spola.config` with its test entry point is in `MODULES` in `Build.java`.
2. The module's main code imports nothing from `java.lang.reflect` or `java.lang.invoke` and has no `requires` on other framework modules (checkable by searching the source and `module-info.java`). `spola.test` only for tests.
3. A test shows the priority order with the same key in four sources (map default, file, environment, argument) and that the argument wins; one test per pair shows that the order follows the order in which sources were added.
4. A test shows the environment prefix rule (`APP_DB_POOL_SIZE` → `db.pool.size`: prefix removed, rest lowercased, `_` → `.`), that variables without the prefix are not read, and the explicit table form. The environment is injected as a `Map` in the tests.
5. A test shows profiles: two active profiles, profile files chosen via the user's function, and that the later profile wins over the earlier and both over the base file.
6. A test shows that a missing optional file is ignored and that a missing required file gives an error with the path in the message.
7. A test shows placeholders with and without default, a literal `${`, and that a cycle `a -> b -> a` gives an error with the whole chain in the message.
8. A test binds a record with a nested record and at least six of the built-in types (including `Duration`, enum, list and `Optional`) plus a custom converter. Built-ins required: `String`, `int`, `long`, `boolean`, `double`, `Duration`, `URI`, `Path`, enum (via `Class<E>` + `Enum.valueOf` only), lists (comma-separated), `Optional`, custom `Function<String,T>`.
9. A test shows that a binding with two missing keys and one invalid value produces **one** exception listing all three errors, with key, expected type and source for the invalid value.
10. A test shows that a sensitive value does not appear in the error message for an invalid value or in the diagnostic dump, and that the dump shows which source won for each key.
11. `README.md` in the module shows an example next to the equivalent Spring Boot code (`application.properties`, `@ConfigurationProperties`, `@Value`, `@Profile`/`spring.profiles.active`) and how the config record is wired into `spola.di` with `toInstance`.
12. No configuration files are checked in under `framework/`, and `Allowlist.DOCUMENT_EXTENSIONS` is unchanged.
13. No annotations beyond the allowlist, no dependencies beyond the JDK (guardrails green).
14. `PROGRESS.md` and `ARCHITECTURE.md` are updated, including the decisions on environment variables, profiles' place in the priority order, and how errors are collected during binding.

Additional binding requirements from the mission (Krav): the configuration is immutable once built and thread-safe to read; public API has Javadoc; the env prefix rule is stated in Javadoc and README; the literal-`${` escape is documented.

## Out of scope
YAML/TOML/JSON; hot reload and remote sources; automatic binding via reflection or codegen; value validation beyond type conversion (a simple per-value predicate is allowed, not required); `spola.di` integration beyond the README example.

## Assumptions
- Config files used by tests are written to temp directories at test time.
- "Source" of a value in error messages means a human-readable source description (e.g. file path, "environment", "arguments").
