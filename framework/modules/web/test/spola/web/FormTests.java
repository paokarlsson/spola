package spola.web;

import static spola.json.codec.JsonCodecs.JSON;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.Headers;
import spola.http.HttpServer;
import spola.http.Response;
import spola.json.JsonObject;
import spola.test.Suite;

/** Criteria 7–14: readForm, FormData, its status codes and limits, and the README example. */
final class FormTests {

    private FormTests() {
    }

    static final String CT_FORM = "Content-Type: application/x-www-form-urlencoded";

    static Support.Reply post(int port, String target, String body, String... headerLines) throws Exception {
        return JsonBodyTests.post(port, target, body, headerLines);
    }

    /**
     * One request that readForm must reject with 415.
     *
     * @param name    what is wrong
     * @param headers the header lines
     */
    record Unsupported(String name, List<String> headers) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Unsupported> UNSUPPORTED = List.of(
            new Unsupported("no Content-Type", List.of()),
            new Unsupported("application/json", List.of("Content-Type: application/json")),
            new Unsupported("multipart/form-data", List.of("Content-Type: multipart/form-data; boundary=x")),
            new Unsupported("text/plain", List.of("Content-Type: text/plain")),
            new Unsupported("a malformed Content-Type", List.of("Content-Type: application/")),
            new Unsupported("a malformed parameter", List.of("Content-Type: application/x-www-form-urlencoded; charset")),
            new Unsupported("charset=iso-8859-1", List.of(CT_FORM + "; charset=iso-8859-1")),
            new Unsupported("Content-Type twice", List.of(CT_FORM, CT_FORM)),
            new Unsupported("Content-Encoding: gzip", List.of(CT_FORM, "Content-Encoding: gzip")),
            new Unsupported("Content-Encoding: identity, deflate", List.of(CT_FORM, "Content-Encoding: identity, deflate")));

    /**
     * A body readForm must reject with 400 without quoting it.
     *
     * @param name what is wrong
     * @param body the body; holds the marker
     */
    record Invalid(String name, String body) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final String MARKER = "MARKER-f0rm";

    static String pairs(int count) {
        var body = new StringBuilder();
        for (int i = 0; i < count; i++) {
            body.append(i == 0 ? "" : "&").append("k").append(i).append('=').append(MARKER);
        }
        return body.toString();
    }

    static final List<Invalid> INVALID = List.of(
            new Invalid("invalid percent-encoding (%G1)", "a=" + MARKER + "%G1"),
            new Invalid("a truncated escape (%C)", MARKER + "=%C"),
            new Invalid("invalid UTF-8 after decoding (%C3%28)", "a=" + MARKER + "%C3%28"),
            new Invalid("an encoded surrogate (%ED%A0%80)", "a=%ED%A0%80" + MARKER),
            new Invalid("a raw byte above 0x7F (UTF-8 å)", "a=" + MARKER + "å"),
            new Invalid("a raw newline", "a=" + MARKER + "\nb=2"),
            new Invalid("a raw space", "a=" + MARKER + " b"),
            new Invalid("1 001 pairs", pairs(1_001)));

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        var log = new Support.CapturedLog();
        return suite("form bodies",
                test("the README example: fields with +, %20, %2B, UTF-8 and repeated names, typed, set a cookie", () -> {
                    try (var app = Support.serve(new ProfileApp().router())) {
                        var saved = post(app.port(), "/profile?name=FromQuery&age=1",
                                "name=Ada+Lovelace%20%2B%C3%A5&age=36&interest=math&interest=po%C3%A9sie&interest=",
                                CT_FORM);
                        assertEquals(200, saved.status(), saved.toString());
                        assertEquals("Saved Ada Lovelace +å.", saved.body());
                        assertEquals("profile=p1; Path=/; HttpOnly; SameSite=Lax",
                                saved.header("Set-Cookie"));
                        var shown = Support.send(app.port(), "GET", "/profile", "Cookie: profile=p1");
                        assertEquals("Ada Lovelace +å, 36, [math, poésie, ]", shown.body());
                        var missing = post(app.port(), "/profile?age=5", "name=Bo", CT_FORM);
                        assertEquals(400, missing.status(), "the query's age is not a form field");
                        assertEquals("Missing form field \"age\".", missing.json().string("detail"));
                        var typed = post(app.port(), "/profile", "name=Bo&age=old", CT_FORM);
                        assertEquals(400, typed.status());
                        assertEquals("Invalid form field \"age\": expected int, got \"old\".", typed.json().string("detail"));
                        var forgotten = Support.send(app.port(), "DELETE", "/profile", "Cookie: profile=p1");
                        assertEquals(204, forgotten.status());
                        assertEquals("profile=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax", forgotten.header("Set-Cookie"));
                    }
                }),
                test("FormData: first, all in order, get, required, asMap, and the query kept apart", () -> {
                    var reply = post(server.get().port(), "/echo?a=query&q=1", "a=1&b=x+y&a=2&c&a=3&d=", CT_FORM);
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("form={a=[1, 2, 3], b=[x y], c=[], d=[]} first=1 all=[1, 2, 3] int=1 query=query"
                            + " queryOnly=1 formQ=none", reply.body());
                }),
                test("an empty body is an empty form", () -> {
                    var reply = post(server.get().port(), "/echo", "", CT_FORM);
                    assertEquals(200, reply.status(), reply.toString());
                    assertTrue(reply.body().startsWith("form={} first=none all=[] int=none"), reply.body());
                    var noLength = Support.send(server.get().port(), "POST", "/echo", CT_FORM);
                    assertEquals(200, noLength.status(), "no Content-Length: no body, an empty form");
                    var separators = post(server.get().port(), "/echo", "&&", CT_FORM);
                    assertTrue(separators.body().startsWith("form={} "), separators.body());
                }),
                test("FormData values from Param: get, required, invalid, and the unit API", () -> {
                    var form = new FormData(Decoding.parseQuery("n=5&n=x&d=2024-02-29&e=", "The form body"));
                    assertEquals(5, form.get("n", Param.INT).orElseThrow());
                    assertEquals(java.time.LocalDate.of(2024, 2, 29), form.required("d", Param.LOCAL_DATE));
                    assertTrue(form.get("missing", Param.INT).isEmpty());
                    var e = assertThrows(WebException.class, () -> form.required("e", Param.INT));
                    assertEquals("Invalid form field \"e\": expected int, got \"\".", e.detail().orElseThrow());
                    var missing = assertThrows(WebException.class, () -> form.required("x\ny", Param.STRING));
                    assertEquals("Missing form field \"x\\x0ay\".", missing.detail().orElseThrow());
                    assertEquals(List.of("5", "x"), form.all("n"));
                    assertEquals(List.of(), form.all("none"));
                    assertThrows(UnsupportedOperationException.class, () -> form.asMap().put("z", List.of()));
                    assertThrows(UnsupportedOperationException.class, () -> form.all("n").add("y"));
                    assertEquals("FormData[\"n\", \"d\", \"e\"]", form.toString());
                    assertEquals(form, new FormData(Decoding.parseQuery("n=5&n=x&d=2024-02-29&e=", "The form body")));
                    assertFalse(form.isEmpty());
                }),
                parameterized("415 with Accept: application/x-www-form-urlencoded", UNSUPPORTED, c -> {
                    var reply = post(server.get().port(), "/echo", "a=1", c.headers().toArray(String[]::new));
                    assertEquals(415, reply.status(), reply.toString());
                    assertEquals("application/x-www-form-urlencoded", reply.header("Accept"));
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    assertEquals("Unsupported Media Type", reply.json().string("title"));
                }),
                test("Content-Encoding 415 also says Accept-Encoding: identity", () -> {
                    var reply = post(server.get().port(), "/echo", "a=1", CT_FORM, "Content-Encoding: gzip");
                    assertEquals("identity", reply.header("Accept-Encoding"));
                    var ok = post(server.get().port(), "/echo", "a=1", CT_FORM, "Content-Encoding: identity");
                    assertEquals(200, ok.status(), ok.toString());
                }),
                test("form types that are accepted: charset UTF-8 in any case, quoted, other parameters", () -> {
                    for (var type : List.of("application/x-www-form-urlencoded; charset=UTF-8",
                            "application/x-www-form-urlencoded;charset=\"utf-8\"", "APPLICATION/X-WWW-FORM-URLENCODED",
                            "application/x-www-form-urlencoded; v=1")) {
                        var reply = post(server.get().port(), "/echo", "a=1", "Content-Type: " + type);
                        assertEquals(200, reply.status(), type + ": " + reply);
                        assertTrue(reply.body().startsWith("form={a=[1]} "), reply.body());
                    }
                }),
                parameterized("400 without text from the body", INVALID, c -> {
                    var reply = post(server.get().port(), "/count", c.body(), CT_FORM);
                    assertEquals(400, reply.status(), reply.toString());
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    assertFalse(reply.body().contains(MARKER), reply.body());
                    assertTrue(reply.json().string("detail").startsWith("The form body "), reply.body());
                }),
                test("exactly 1 000 pairs are accepted", () -> {
                    var reply = post(server.get().port(), "/count", pairs(1_000), CT_FORM);
                    assertEquals(200, reply.status(), reply.toString());
                    assertEquals("1000", reply.body());
                    var details = post(server.get().port(), "/count", pairs(1_001), CT_FORM);
                    assertEquals("The form body has more than 1000 parameters.", details.json().string("detail"));
                }),
                test("a form over maxFormSize, many times larger and chunked, gets 413 and the response arrives", () -> {
                    var client = Support.client();
                    var sent = new AtomicLong();
                    long size = 4L << 20; // 4 MiB against a limit of 1 KiB, below maxBodySize (10 MiB)
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.get().port() + "/echo"))
                            .header("Content-Type", "application/x-www-form-urlencoded").timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new JsonBodyTests.Endless(size, sent)))
                            .build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(413, response.statusCode(), response.body());
                    assertEquals("Request body is larger than 1024 bytes.",
                            ((JsonObject) spola.json.Json.parse(response.body())).string("detail"));
                    client.close();
                }),
                test("readForm stops reading one byte past the limit (the body is never read whole)", () -> {
                    var sent = new AtomicLong();
                    var e = assertThrows(WebException.class, () -> FormBody.read(
                            Headers.of("Content-Type", "application/x-www-form-urlencoded"),
                            new JsonBodyTests.Endless(Long.MAX_VALUE, sent), 1_024));
                    assertEquals(413, e.status());
                    assertEquals(1_025L, sent.get());
                    var exact = "a=" + "x".repeat(1_022);
                    var form = FormBody.read(Headers.of("Content-Type", "application/x-www-form-urlencoded"),
                            new ByteArrayInputStream(exact.getBytes(StandardCharsets.US_ASCII)), 1_024);
                    assertEquals(1_022, form.first("a").orElseThrow().length(), "exactly at the limit is fine");
                }),
                test("readForm(maxBytes) goes before the router's maxFormSize", () -> {
                    var body = "a=" + "x".repeat(2_000);
                    assertEquals(413, post(server.get().port(), "/echo", body, CT_FORM).status(), "router: 1 KiB");
                    var large = post(server.get().port(), "/own-large", body, CT_FORM);
                    assertEquals(200, large.status(), "own: 64 KiB");
                    assertEquals("2000", large.body());
                    var small = post(server.get().port(), "/own-small", "a=" + "x".repeat(60), CT_FORM);
                    assertEquals(413, small.status(), "own: 50 bytes");
                    assertEquals("Request body is larger than 50 bytes.", small.json().string("detail"));
                    assertThrows(IllegalArgumentException.class, () -> Router.builder().maxFormSize(0));
                }),
                test("a body over HttpLimits.maxBodySize still gets spola.http's status", () -> {
                    var router = Router.builder()
                            .post("/form", r -> Response.text(200, r.readForm().toString()))
                            .onException(Exception.class, (e, r) -> Response.text(500, "swallowed"))
                            .build();
                    var options = Support.options().withLimits(Support.options().limits().withMaxBodySize(100));
                    try (var small = HttpServer.start(options, router)) {
                        var body = pairs(100);
                        var request = "POST /form HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                                + CT_FORM + "\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
                        var reply = Support.raw(small.port(), request.getBytes(StandardCharsets.ISO_8859_1), false);
                        assertEquals(413, reply.status(), reply.toString());
                        assertFalse(reply.body().contains("swallowed"));
                        assertEquals("text/plain; charset=utf-8", reply.header("Content-Type"));
                    }
                }),
                test("readForm after readJson, body() or readForm, and body() after readForm: 500", () -> {
                    assertEquals(500, post(server.get().port(), "/twice", "a=1", CT_FORM).status());
                    assertEquals(500, post(server.get().port(), "/after-json", "{}", "Content-Type: application/json")
                            .status());
                    assertEquals(500, post(server.get().port(), "/after-body", "a=1", CT_FORM).status());
                    assertEquals(500, post(server.get().port(), "/body-after", "a=1", CT_FORM).status());
                    assertEquals(500, post(server.get().port(), "/json-after", "a=1", CT_FORM).status());
                    var records = String.join("\n", log.records());
                    assertTrue(records.contains("already read by readForm()"), records);
                    assertTrue(records.contains("already read by readJson()"), records);
                    assertTrue(records.contains("already read by body()"), records);
                }),
                test("a mapper for WebException makes its own response for 415 and 400 from readForm", () -> {
                    var router = Router.builder()
                            .post("/form", r -> Response.text(200, r.readForm().toString()))
                            .onException(WebException.class, (e, r) -> Response.text(e.status(),
                                    "custom " + e.status() + " accept=" + e.headers().first("Accept").orElse("-")))
                            .build();
                    try (var own = Support.serve(router)) {
                        var unsupported = post(own.port(), "/form", "a=1", "Content-Type: text/plain");
                        assertEquals(415, unsupported.status());
                        assertEquals("custom 415 accept=application/x-www-form-urlencoded", unsupported.body());
                        var invalid = post(own.port(), "/form", "a=%G1", CT_FORM);
                        assertEquals(400, invalid.status());
                        assertEquals("custom 400 accept=-", invalid.body());
                    }
                }),
                test("200 concurrent clients with different forms and cookies get their own values", () -> {
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var start = new CountDownLatch(1);
                        var futures = new ArrayList<Future<String>>();
                        for (int i = 0; i < 200; i++) {
                            int n = i;
                            futures.add(executor.submit(() -> {
                                start.await();
                                return post(server.get().port(), "/mine?q=" + n,
                                        "name=client+" + n + "&n=" + n + "&tag=t" + n + "&tag=%C3%A5" + n, CT_FORM,
                                        "Cookie: session=s" + n + "; other=o" + (n * 3)).body();
                            }));
                        }
                        start.countDown();
                        for (int i = 0; i < 200; i++) {
                            assertEquals("client " + i + "|" + i + "|[t" + i + ", å" + i + "]|s" + i + "|o" + (i * 3)
                                    + "|" + i, futures.get(i).get());
                        }
                    }
                }),
                test("%0A, ESC and raw newlines from cookies and forms reach no log line or problem detail raw", () -> {
                    var targets = List.of(
                            post(server.get().port(), "/fails", "v=%0AFAKE%1B%5B2J", CT_FORM, "Cookie: c=%0AFAKE"),
                            post(server.get().port(), "/fails", "v=x", CT_FORM, "Cookie: c=%0D%0AFAKE%1B"),
                            post(server.get().port(), "/typed", "n=%0AFAKE%1B%5B31m", CT_FORM),
                            post(server.get().port(), "/typed", "n=1", CT_FORM, "Cookie: n=%0AFAKE"),
                            post(server.get().port(), "/typed", "n=1\nFAKE: forged", CT_FORM),
                            post(server.get().port(), "/typed", "n%0A%1B=1", CT_FORM));
                    for (var reply : targets) {
                        assertTrue(reply.status() == 400 || reply.status() == 500, reply.toString());
                        for (char c : reply.body().toCharArray()) {
                            assertFalse(c < 0x20 || c == 0x7F, "control character in " + reply.body());
                        }
                    }
                    assertEquals("Invalid form field \"n\": expected int, got \"\\x0aFAKE\\x1b[31m\".",
                            targets.get(2).json().string("detail"));
                    assertEquals("Invalid cookie \"n\": expected int, got \"%0AFAKE\".",
                            targets.get(3).json().string("detail"));
                    assertEquals("Missing form field \"n\".", targets.get(5).json().string("detail"));
                    var records = log.records();
                    LogTests.assertClean(records);
                    for (var record : records) {
                        for (var line : record.split("\n")) {
                            assertFalse(line.startsWith("FAKE"), record);
                        }
                    }
                    assertTrue(String.join("\n", records).contains("\\x0aFAKE\\x1b[2J"), records.toString());
                    var raw = Support.raw(server.get().port(), ("POST /typed HTTP/1.1\r\nHost: localhost\r\n"
                            + "Connection: close\r\nCookie: n=\u001b[31m\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1), false);
                    assertEquals(400, raw.status(), "spola.http refuses a raw ESC in a header");
                    LogTests.assertClean(log.records());
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .maxFormSize(1_024)
                        .post("/echo", r -> {
                            var form = r.readForm();
                            return Response.text(200, "form=" + form.asMap() + " first=" + form.first("a").orElse("none")
                                    + " all=" + form.all("a") + " int=" + form.get("a", Param.INT).map(String::valueOf)
                                            .orElse("none")
                                    + (r.query("a").isPresent() ? " query=" + r.query("a").orElseThrow() + " queryOnly="
                                            + r.query("q").orElseThrow() + " formQ=" + form.first("q").orElse("none") : ""));
                        })
                        .post("/count", r -> Response.text(200, String.valueOf(r.readForm(1 << 20).asMap().size())))
                        .post("/own-large", r -> Response.text(200,
                                String.valueOf(r.readForm(64 * 1_024).first("a").orElseThrow().length())))
                        .post("/own-small", r -> Response.text(200, r.readForm(50).toString()))
                        .post("/twice", r -> {
                            r.readForm();
                            return Response.text(200, r.withAttribute(ScaleTests.CLIENT, "x").readForm().toString());
                        })
                        .post("/after-json", r -> {
                            r.readJson(JSON);
                            return Response.text(200, r.readForm().toString());
                        })
                        .post("/after-body", r -> {
                            r.body();
                            return Response.text(200, r.readForm().toString());
                        })
                        .post("/body-after", r -> {
                            r.readForm();
                            return Response.text(200, String.valueOf(r.bytes().length));
                        })
                        .post("/json-after", r -> {
                            r.readForm();
                            return Response.text(200, r.readJson(JSON).toString());
                        })
                        .post("/mine", r -> {
                            var form = r.readForm();
                            Thread.sleep(10); // overlap the requests
                            return Response.text(200, form.first("name").orElseThrow() + "|" + form.required("n", Param.INT)
                                    + "|" + form.all("tag") + "|" + r.cookie("session").orElseThrow() + "|"
                                    + r.cookie("other").orElseThrow() + "|" + r.requiredQuery("q", Param.INT));
                        })
                        .post("/fails", r -> {
                            var form = r.readForm();
                            throw new IllegalStateException("cannot store " + form.first("v").orElse("") + " for "
                                    + r.cookie("c").orElse("") + " " + form.asMap());
                        })
                        .post("/typed", r -> {
                            var form = r.readForm();
                            r.cookie("n", Param.INT);
                            return Response.text(200, String.valueOf(form.required("n", Param.INT)));
                        })
                        .log(log)
                        .build())))
                .afterAll(() -> server.get().close());
    }

    /** The README shows ProfileApp.java as it is compiled and tested here. */
    static Suite readme() {
        return suite("README form and cookie example",
                test("the README shows ProfileApp.java as it is compiled and tested here", () -> {
                    var readme = Files.readString(Path.of("modules/web/README.md"));
                    var source = Files.readString(Path.of("modules/web/test/spola/web/ProfileApp.java"));
                    var header = "package spola.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(readme.contains(block), "the README's Spola form example differs from ProfileApp.java");
                }),
                test("the README's deviation table has the rows for cookies and forms", () -> {
                    var readme = Files.readString(Path.of("modules/web/README.md"));
                    for (var row : List.of("| Standardvärden för `ResponseCookie` |", "| `Expires` |",
                            "| Felaktiga cookies |", "| Citattecken i cookievärden |", "| Formulär och query |",
                            "| Tom formulärkropp |", "| Fel `Content-Type` på formulär |")) {
                        assertTrue(readme.contains(row), "missing row " + row);
                    }
                }));
    }
}
