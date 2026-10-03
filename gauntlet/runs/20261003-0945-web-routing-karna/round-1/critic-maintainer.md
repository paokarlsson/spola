VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/RouterBuilder.java:249-290
  problem: The private static `route(Registration, validPrefixes, problems)` does five things in about 40 lines (token check, prefix chain, full-pattern assembly, pattern parsing, filter collection). It also shares its name with the public `route(String, String, WebHandler)`, which makes it harder to find and change.
  evidence: "private static Route route(Registration registration, Map<Scope, Boolean> validPrefixes, List<String> problems)" next to "public RouterBuilder route(String method, String pattern, WebHandler handler)"
  fix_hint: Rename it to `resolve`. Split out `fullPattern(chain)` and `collectFilters(chain)`.

- id: MNT-2
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/Router.java:95-131
  problem: `handle` builds `new WebRequest(http, ..., query, Map.of())` in five places and builds a throwing terminal handler in four. The 404, 405 and 400 branches are hard to scan, and a new `WebRequest` field would have to be added in all five.
  evidence: "new WebRequest(http, valid.decoded(), valid.segments(), null, query, Map.of())" repeated
  fix_hint: Add a small helper such as `unrouted(http, path, segments, query)` and a `failWith(WebException)` terminal.

- id: MNT-3
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/Router.java:136,140,145; RouteTable.java:126
  problem: Fully qualified names are used inline instead of imports.
  evidence: "@java.io.Serial", "spola.http.Headers.of", "new java.util.ArrayList<Filter>", "java.util.Collections.unmodifiableSortedSet"
  fix_hint: Import them.

- id: MNT-4
  severity: MINOR
  where: /workspace/framework/modules/web/src/spola/web/RouterBuilder.java:46-56
  problem: `Scope` is a package-private class with mutable public-ish fields, including `open`, and `RouteGroup` reaches into it. The closed-group rule is only enforced through the scattered `checkOpen` calls.
  evidence: "boolean open = true;" and "scope.open = false;" in a `finally` block
  fix_hint: Add a comment on `Scope` saying who may mutate it, or give it `close()` and `add(Filter)` methods.
