```
VERDICT: PASS
FINDINGS:
- id: BRK-1
  severity: MINOR
  where: framework/modules/web/src/spola/web/RouteTable.java:163-175 (pick, HEAD falls back to GET per node) vs Router.java Javadoc and criterion 5
  problem: An explicit HEAD route loses to the automatic HEAD-via-GET whenever the GET route is more specific, while an explicit OPTIONS route in the same position wins over the automatic OPTIONS, so "an explicit HEAD or OPTIONS route takes precedence over the automatic one" holds for OPTIONS but not for HEAD.
  evidence: scratch-breaker/Exp3.java, routes GET /f/readme (X-H: auto), HEAD /f/{p...} (X-H: explicit), OPTIONS /f/{p...} (X-H: explicit). `java -p framework/out --add-modules spola.web Exp3.java` gives "HEAD -> X-H: auto" and "OPTIONS -> X-H: explicit". This only happens when the explicit HEAD route has a less specific pattern than the GET route, which is unusual.
  fix_hint: Either document that HEAD follows the most specific GET (as Spring does), or make HEAD and OPTIONS behave the same way.
- id: BRK-2
  severity: MINOR
  where: framework/modules/web/src/spola/web/WebRequest.java requiredQuery
  problem: `requiredQuery(name, Param.STRING)` returns "" for `?s=` or `?s`, but the mission says "requiredQuery utan värde ger 400" (requiredQuery without a value gives 400), and that wording can be read as covering an empty value.
  evidence: scratch-breaker/Exp.java, route GET /q -> requiredQuery("s", STRING). Raw socket "GET /q?s=" gives "200 OK | q:|", and "GET /q" gives 400. For non-string Params an empty value already gives 400 through the conversion.
  fix_hint: State in the Javadoc and README that an empty value counts as present (as Spring does), or reject empty values.
- id: BRK-3
  severity: MINOR
  where: framework/modules/web/src/spola/web/RouterDefinitionException.java problems() Javadoc, RouterBuilder.build()
  problem: The Javadoc says the problems come "in the order the routes, groups and mappers were registered", but all group-prefix problems are listed before every route problem.
  evidence: Router.builder().get("bad1",..).group("nogroupslash", g->{}).get("bad2",..).build() → problems() = [group prefix "nogroupslash": ..., GET "bad1": ..., GET "bad2": ...]
  fix_hint: Correct the Javadoc to say group prefixes come first, or collect the problems in registration order.
```

The only defects I found are the three minor ones above. Here is what I tried without finding a defect:
- **Build:** a full `java Build.java` in a fresh copy (scratch-breaker/fw) exits 0, and spola.web runs 288/288 tests.
- **Definition checks:** conflicts across groups (`/a/{x}` against group `/a` + `/{y}`, and `/a` against group `/a` + `/`) are both detected.
- **Path handling:** absolute-form targets work, including `http://x` and `http://x/files/%2e%2e`. A catch-all with a trailing slash gives 404 (documented). C1 characters in a path give 404, not 400.
- **Body errors:** a request body over `maxBodySize` gives 413 from spola.http even when there is a catch-all `Exception` mapper.
- **Exception mappers:** an `Exception` mapper also takes the router's own 404/405/400. That follows "closest class wins" and matches Spring.
- **HEAD and OPTIONS:** HEAD returns the same Content-Length with no body. Automatic OPTIONS gives the right `Allow`, and router filters run on it.
- **Logging:** messages containing CR/LF are escaped in the real JUL output.
- **Problem responses:** a lone surrogate in an extension is rejected by `JsonString.of`.
- **Code review:** I read the code for trie backtracking cost (each node is visited at most once), query decoding edge cases, `Param` strictness, filter and mapper step semantics, and thread-safety of `QueryState` and `WebRequest`.

Experiment files are in /workspace/gauntlet/runs/20261003-0945-web-routing-karna/round-1/scratch-breaker/ (Exp.java, Exp2.java, Exp3.java).
