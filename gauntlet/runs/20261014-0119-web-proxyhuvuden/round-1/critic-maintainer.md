VERDICT: PASS
FINDINGS:
- id: MNT-1
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/WebRequest.java:78-97 (8-arg constructor next to the private 10-arg one)
  problem: The short package-private constructor has the same first 8 parameters as a copy would pass, and it silently resets scheme and clientAddress to the connection's values. A future copy method (a `withPath` in the style of `withAttribute`) that calls it fails open: the client reverts to the proxy's internal address.
  evidence: "WebRequest(Request http, String path, ..., Map<AttributeKey<?>, Object> attributes) { this(..., http.scheme(), http.remoteAddress().getAddress()); }". The source test only restricts `withClient(` and does not restrict `new WebRequest(`.
  fix_hint: Make the short form a named factory (`WebRequest.forConnection(...)`) used only by Router, or keep a single constructor. Alternatively, extend the source rule so `new WebRequest(` appears only in Router and WebRequest.

- id: MNT-2
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/ProxyNetworks.java:80-113 and IpLiteral.java:52
  problem: `parse(entry, problems)` returns null and also adds to an out-parameter list, which is a hidden two-channel protocol. It also detects "IPv4-mapped" a second way, `literal.indexOf(':') >= 0 && address instanceof Inet4Address`, although `IpLiteral.isMapped(bytes)` exists for that. `isMapped` is used only by `sameAddress` and by tests.
  evidence: ProxyNetworks.java:90 versus IpLiteral.isMapped. The method does five things: split, literal, mapped check, prefix, mask check with message.
  fix_hint: Check mapped entries with `IpLiteral.parse` plus `isMapped`, so there is one definition. Split `parse` into steps that throw an internal exception, or return a record, so `of` collects the messages.

- id: MNT-3
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/IpLiteral.java:161-199 and IpLiteralTests (format table plus 20 000 random addresses)
  problem: A complete RFC 5952 formatter (about 35 lines of main code plus its tests) exists only to write the "did you mean" hint for `10.0.0.1/8`. It is the most intricate main-code method in the diff for a message nicety.
  evidence: The only caller is ProxyNetworks.java:109, `IpLiteral.format(network) + "/" + prefix`.
  fix_hint: Make the hint simpler (IPv4 only, or the masked address via `InetAddress.getHostAddress()`), or keep it and note in the Javadoc that it is only for messages.

- id: MNT-4
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/ForwardedHeaders.java:177-201
  problem: The backward scan with a `limit` variable, where -1 means the field is done, and with `lastIndexOf(',', limit - 1)` is dense. Its rationale is not given anywhere. ARCHITECTURE only says "utan att dela upp hela listan i förväg", but the header section is already size-limited.
  evidence: "int limit = field.length(); // the end of the element; -1 when the field is done"; "limit = comma;"
  fix_hint: Add one sentence saying why splitting forward is not used. Otherwise extract an `elements(field)` helper that yields the trimmed elements from the right.

- id: MNT-5
  severity: MINOR
  where: /home/user/spola/framework/modules/web/src/spola/web/ForwardedHeaders.java:151-152 and ARCHITECTURE.md section "Bakom en omvänd proxy"
  problem: The order of the checks is visible to callers: if both headers are invalid, the 400 names X-Forwarded-Proto. That order is not in the Javadoc or in ARCHITECTURE, and I found no test for it. build.md lists it as a deviation "documented in ARCHITECTURE.md where relevant", but it is not there.
  evidence: "var scheme = scheme(http); var client = client(http.headers().all(FOR), sender);" and build.md "X-Forwarded-Proto is checked before X-Forwarded-For".
  fix_hint: Add one sentence to the "400" section of the Javadoc and to ARCHITECTURE, and add a case with both headers invalid.

- id: MNT-6
  severity: MINOR
  where: /home/user/spola/framework/modules/web/test/spola/web/ForwardedReadmeTests.java:94 and ForwardedForTests.java:99-103, ForwardedProtoTests.java:68-74
  problem: The tests are coupled to presentation. The README check contains a line break and indent (`"Dockers\n  userland-proxy"`), so a reflow of the README paragraph breaks the build with an opaque "the section lacks ...". Two tests read the data out of the table cases' display names (`names.contains("X-Forwarded-For: \"" + value + "\"")`).
  evidence: "\"Dockers\\n  userland-proxy\""
  fix_hint: Normalize whitespace in `section()` before `contains`. Make the table assertions on `Case.values()` instead of on the `toString()` text.

- id: MNT-7
  severity: MINOR
  where: /home/user/spola/framework/ARCHITECTURE.md:3309-3314 and ForwardedLeakTests.java:94,100
  problem: Hard-coded counts will rot when a row is added. ARCHITECTURE says "32 giltiga och 34 ogiltiga ..., 8 giltiga och 13 ogiltiga". The leak test uses unexplained arithmetic, `MARKED.size() * 3 * 4` and `MARKED.size() * 3 + 1`, where 3 is the number of targets and 4 is the messages per mapper call.
  evidence: The numbers match the tables today but are not derived from anything.
  fix_hint: Drop the counts from the prose. Name the constants (`TARGETS`, `MESSAGES_PER_400`) in the test.

- id: MNT-8
  severity: MINOR
  where: /home/user/spola/framework/modules/web/test/spola/web/ForwardedSupport.java:25,28
  problem: `TRUSTING` and `NOT_TRUSTING` do not say what is trusted. `NOT_TRUSTING` actually means a proxy set that does not include the sender 127.0.0.1, and the names suggest the opposite of what the second one is.
  evidence: "static final TrustedProxies NOT_TRUSTING = TrustedProxies.of(\"10.0.0.0/8\");"
  fix_hint: Rename to something like `SENDER_AND_PRIVATE` and `ONLY_10_8`, or `PROXIES_WITH_SENDER` and `PROXIES_WITHOUT_SENDER`.

- id: MNT-9
  severity: MINOR
  where: /home/user/spola/framework/modules/web/test/spola/web/SourceRulesTests.java:62 and /home/user/spola/framework/modules/http/test/spola/http/RemoteAddressTests.java:97
  problem: The regex that strips comments, `replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "")`, is copy-pasted once more in each module. It is now the third copy in the web and http test directories, and it will drift if the comment rules change.
  evidence: Same line at SourceRulesTests.java:50 and :62, and in RemoteAddressTests.java.
  fix_hint: Add a `strippedSource(Path)` helper next to `mainSources()` in each module's SourceRulesTests. Moving them to a shared test helper is out of scope.

Summary: No MAJOR or BLOCKER findings. There is no hidden wiring, reflection, or naming convention. The main code is small and cleanly split (`ForwardedHeaders` filter, `TrustedProxies` interface, `ProxyNetworks` implementation, `IpLiteral` parser), and the Javadoc and ARCHITECTURE record the non-obvious decisions (why 400, why `ofLiteral` is not enough, the order in the chain). The findings above are about small structural risks and test brittleness.
