```
VERDICT: PASS
FINDINGS: none
```

I found no reproducible BLOCKER, MAJOR or MINOR defect.

**Build and rules**
- `java Build.java` in `/workspace/framework` exits 0 (`BUILD OK`).
- `module-info.java` has no new `requires`.
- Nothing under `modules/web/src` imports `java.lang.reflect` or `java.lang.invoke`.
- The only change to existing tests is four suite registrations added to `WebTests.java`.
- Nothing in `spola.http` was changed.

**Forms, over a real server on 127.0.0.1** (scratch code is in `/workspace/gauntlet/runs/20261003-1102-web-cookies-och-formular/round-1/scratch-breaker/src/brk/`)
- These Content-Types are accepted: `charset="UTF-8"` in quotes, and `Application/X-WWW-Form-URLEncoded; Charset=utf-8` in mixed case.
- `a=%E2%82%AC&b=x+y&c&&d=` decodes correctly: empty pairs are skipped and `c` and `d` get empty values.
- `%ZZ=1` gives 400 without quoting anything from the body. An empty body gives an empty form.
- A 5000-byte body with `maxFormSize(100)` gives 413. The next request pipelined on the same keep-alive connection (`readForm(10_000)`) is still answered correctly, so the leftover body is drained.
- A chunked body over `HttpLimits.maxBodySize(50)` gets spola.http's own 413 (plain text), so `RequestBodyException` is not swallowed.

**Cookies, over a real server**
These all parse as the spec says:
- `a=1;b=2`
- tab and space trimming
- `a==b` gives `=b`
- `$Version=1` stays a plain name
- empty pieces `; ;` and a trailing `;` are ignored
- `a="x y"` is skipped
- `a=1, b=2` is skipped entirely, as decided in the mission
- `%41` is not decoded and `"q"` keeps its quotes

`cookie("n", Param.INT)` with 60 digits gives 400 with the value cut to 40 characters plus `...`. With `+5` it gives 400.

**Code review**
- **Locking and sharing:** `CookieState` uses synchronized lazy parsing, keeps a failed parse, and is shared through `withAttribute`.
- **Read-once body:** `BodyState` claims the body with `compareAndExchange`. `body()` or `bytes()` followed by `readForm` throws `IllegalStateException`, as does any second `readJson` or `readForm`.
- **`ResponseCookie` validation:**
  - Every check runs in `build()`, so the order of setters does not matter.
  - The `__Host-`/`__Secure-` prefix rules, `SameSite=None` without `Secure`, and fractional or negative `maxAge` all give `IllegalArgumentException`.
  - The domain check handles a leading dot (`.` alone is rejected).
  - Messages are escaped with `Text.quote`.
- **Test sizes:** the test tables are larger than required: 38 cookie-parsing cases, 22 valid and 29 invalid `ResponseCookie` cases.
- **Docs:** `ARCHITECTURE.md` covers every item in criterion 16. The README deviation table has the new rows.

**Known limit, not counted as a finding**
A Java client using `java.net.CookieManager` reads a cookie that has `Max-Age` but no `Expires` as an RFC 2965 cookie. It then sends back `$Version="1"` and the value in quotes, which Spola keeps. This follows from two binding decisions in the mission (no `Expires`, quotes kept). It is documented in ARCHITECTURE.md, and a test (`ResponseCookieTests.java:221`) pins the behaviour. ARCHITECTURE.md says PROGRESS.md also lists it under known problems; I did not check that.
