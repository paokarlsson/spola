package spola.web;

import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.JSON;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.list;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import spola.http.Headers;
import spola.http.HttpServer;
import spola.http.Response;
import spola.json.JsonArray;
import spola.json.JsonLimits;
import spola.json.JsonObject;
import spola.json.codec.JsonCodec;
import spola.json.codec.JsonMappingException;
import spola.test.Suite;

/** Criteria 6–10: readJson with 415, 400, 413, errors, limits, read-once and mappers. */
final class JsonBodyTests {

    private JsonBodyTests() {
    }

    record Address(String city) {
    }

    record Line(String sku, int qty) {
    }

    record Order(String customer, Address address, List<Line> lines) {
    }

    static final JsonCodec<Address> ADDRESS = JsonCodec.object(Address::new, field("city", STRING, Address::city));
    static final JsonCodec<Line> LINE = JsonCodec.object(Line::new,
            field("sku", STRING, Line::sku), field("qty", INT, Line::qty));
    static final JsonCodec<Order> ORDER = JsonCodec.object(Order::new,
            field("customer", STRING, Order::customer),
            field("address", ADDRESS, Order::address),
            field("lines", list(LINE), Order::lines));

    record Note(String note) {
    }

    static final JsonCodec<Note> PLAIN_NOTE = JsonCodec.object(Note::new, field("note", STRING, Note::note));

    /** A string that the codec rejects with a message quoting it (escaped). */
    static final JsonCodec<String> NOTE = STRING.map(value -> {
        throw new IllegalArgumentException("bad note " + value);
    }, value -> value);

    /** A POST with Content-Length on its own connection. */
    static Support.Reply post(int port, String target, String body, String... headerLines) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        var request = new StringBuilder("POST ").append(target)
                .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: ").append(bytes.length)
                .append("\r\n");
        for (var line : headerLines) {
            request.append(line).append("\r\n");
        }
        request.append("\r\n");
        var all = new ByteArrayOutputStream();
        all.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        all.write(bytes);
        return Support.raw(port, all.toByteArray(), false);
    }

    static final String CT_JSON = "Content-Type: application/json";

    /**
     * One request that readJson must reject with 415.
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
            new Unsupported("text/plain", List.of("Content-Type: text/plain")),
            new Unsupported("application/xml", List.of("Content-Type: application/xml")),
            new Unsupported("an invalid Content-Type", List.of("Content-Type: application/")),
            new Unsupported("an invalid Content-Type (*/json)", List.of("Content-Type: */json")),
            new Unsupported("charset=iso-8859-1", List.of("Content-Type: application/json; charset=iso-8859-1")),
            new Unsupported("charset=utf-16", List.of("Content-Type: application/json;charset=UTF-16")),
            new Unsupported("Content-Encoding: gzip", List.of(CT_JSON, "Content-Encoding: gzip")),
            new Unsupported("Content-Encoding: identity, gzip", List.of(CT_JSON, "Content-Encoding: identity, gzip")),
            new Unsupported("Content-Type twice", List.of(CT_JSON, CT_JSON)),
            new Unsupported("application/json-patch (not +json)", List.of("Content-Type: application/json-patch")));

    static Suite tests() {
        var server = new AtomicReference<HttpServer>();
        var log = new Support.CapturedLog();
        return suite("readJson",
                parameterized("415 with Accept: application/json", UNSUPPORTED, c -> {
                    var reply = post(server.get().port(), "/echo", "{\"name\":\"a\",\"quantity\":1}",
                            c.headers().toArray(String[]::new));
                    assertEquals(415, reply.status(), reply.toString());
                    assertEquals("application/json", reply.header("Accept"));
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    assertEquals("Unsupported Media Type", reply.json().string("title"));
                }),
                test("Content-Encoding 415 also says Accept-Encoding: identity; identity itself is fine", () -> {
                    var reply = post(server.get().port(), "/echo", "{}", CT_JSON, "Content-Encoding: gzip");
                    assertEquals("identity", reply.header("Accept-Encoding"));
                    var ok = post(server.get().port(), "/echo", "{\"name\":\"a\",\"quantity\":1}", CT_JSON,
                            "Content-Encoding: Identity");
                    assertEquals(200, ok.status(), ok.toString());
                }),
                test("JSON types that are accepted: charset UTF-8 in any case, quoted, and +json", () -> {
                    for (var type : List.of("application/json; charset=UTF-8", "application/json;charset=\"utf-8\"",
                            "application/vnd.example+json", "APPLICATION/JSON", "application/json;v=2")) {
                        var reply = post(server.get().port(), "/echo", "{\"name\":\"a\",\"quantity\":1}",
                                "Content-Type: " + type);
                        assertEquals(200, reply.status(), type + ": " + reply);
                        assertEquals(new ItemApi.Item(0, "a", 1), ItemApi.ITEM.read(reply.body()));
                    }
                }),
                test("an empty body: 400 Request body is required", () -> {
                    var reply = post(server.get().port(), "/echo", "", CT_JSON);
                    assertEquals(400, reply.status());
                    assertEquals("application/problem+json", reply.header("Content-Type"));
                    assertEquals("Request body is required", reply.json().string("detail"));
                }),
                test("a syntax error: 400 with line and column, without text from the body", () -> {
                    var reply = post(server.get().port(), "/echo", "{\"name\": \"MARKER-7f3a\"\n  \"quantity\": 1}",
                            CT_JSON);
                    assertEquals(400, reply.status());
                    var detail = reply.json().string("detail");
                    assertEquals("Request body is not valid JSON: line 2, column 3: expected ',' or '}' but found '\"'"
                            + " (RFC 8259 section 4)", detail);
                    assertFalse(reply.body().contains("MARKER-7f3a"), reply.body());
                    var duplicate = post(server.get().port(), "/echo", "{\"MARKER-dup-9z\":1,\"MARKER-dup-9z\":2}",
                            CT_JSON);
                    assertEquals(400, duplicate.status());
                    assertTrue(duplicate.json().string("detail").contains("line 1, column"), duplicate.toString());
                    assertFalse(duplicate.body().contains("MARKER-dup"), duplicate.body());
                    var whitespace = post(server.get().port(), "/echo", "  \n ", CT_JSON);
                    assertEquals(400, whitespace.status());
                    assertTrue(whitespace.json().string("detail").startsWith("Request body is not valid JSON: line 2"),
                            whitespace.toString());
                }),
                test("a body deeper than maxDepth: 400", () -> {
                    var reply = post(server.get().port(), "/any-default", "[".repeat(600) + "]".repeat(600), CT_JSON);
                    assertEquals(400, reply.status(), reply.toString());
                    assertTrue(reply.json().string("detail").contains("maxDepth (512)"), reply.toString());
                    var other = post(server.get().port(), "/any", "[\"" + "x".repeat(100) + "\"]", CT_JSON);
                    assertEquals(400, other.status(), "maxStringLength of the router's limits");
                }),
                test("three mapping problems on different levels: 400 with every one in errors, in order", () -> {
                    var body = "{\"customer\":5,\"address\":{\"city\":true},"
                            + "\"lines\":[{\"sku\":\"a\",\"qty\":1},{\"sku\":\"b\",\"qty\":\"x\"}]}";
                    var reply = post(server.get().port(), "/order", body, CT_JSON);
                    assertEquals(400, reply.status(), reply.toString());
                    var json = reply.json();
                    assertEquals("Request body has 3 problems.", json.string("detail"));
                    var errors = json.array("errors");
                    assertEquals(3, errors.values().size());
                    assertEquals(List.of("$.customer", "$.address.city", "$.lines[1].qty"),
                            errors.values().stream().map(e -> ((JsonObject) e).string("path")).toList());
                    for (var error : errors.values()) {
                        var object = (JsonObject) error;
                        assertEquals(List.of("path", "message"), List.copyOf(object.members().keySet()));
                        assertTrue(object.string("message").startsWith("expected "), object.toString());
                    }
                    assertEquals("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                            + "\"detail\":\"Request body has 1 problem.\",\"instance\":\"/order\","
                            + "\"errors\":[{\"path\":\"$\",\"message\":\"expected object but found array\"}]}",
                            post(server.get().port(), "/order", "[]", CT_JSON).body());
                }),
                test("more than 100 problems: at most 100 in errors, and the detail counts them all", () -> {
                    var body = new StringBuilder("[");
                    for (int i = 0; i < 250; i++) {
                        body.append(i == 0 ? "" : ",").append("\"n").append(i).append('"');
                    }
                    var reply = post(server.get().port(), "/ints", body.append(']').toString(), CT_JSON);
                    assertEquals(400, reply.status());
                    assertEquals("Request body has 250 problems. The first 100 are listed.",
                            reply.json().string("detail"));
                    var errors = reply.json().array("errors").values();
                    assertEquals(100, errors.size());
                    assertEquals("$[0]", ((JsonObject) errors.getFirst()).string("path"));
                    assertEquals("$[99]", ((JsonObject) errors.getLast()).string("path"));
                    var exactly = new StringBuilder("[");
                    for (int i = 0; i < 101; i++) {
                        exactly.append(i == 0 ? "" : ",").append("true");
                    }
                    var reply101 = post(server.get().port(), "/ints", exactly.append(']').toString(), CT_JSON);
                    assertEquals("Request body has 101 problems. The first 100 are listed.",
                            reply101.json().string("detail"));
                    assertEquals(100, reply101.json().array("errors").values().size());
                    var reply100 = post(server.get().port(), "/ints",
                            "[" + String.join(",", java.util.Collections.nCopies(100, "true")) + "]", CT_JSON);
                    assertEquals("Request body has 100 problems.", reply100.json().string("detail"));
                    assertEquals(100, reply100.json().array("errors").values().size());
                }),
                test("JsonMappingException.problemCount counts the problems that are not listed", () -> {
                    var e = assertThrows(JsonMappingException.class,
                            () -> list(INT).read("[" + String.join(",", java.util.Collections.nCopies(150, "true")) + "]"));
                    assertEquals(150, e.problemCount());
                    assertEquals(101, e.problems().size());
                    var one = assertThrows(JsonMappingException.class, () -> INT.read("true"));
                    assertEquals(1, one.problemCount());
                }),
                test("a value with %0A or ESC reaches no log line unescaped", () -> {
                    var reply = post(server.get().port(), "/note?tag=%0AFAKE%1B%5B2J",
                            "{\"note\":\"\\u001b[31m\\nFAKE: forged line\"}", CT_JSON);
                    assertEquals(400, reply.status());
                    var message = ((JsonObject) reply.json().array("errors").values().getFirst()).string("message");
                    assertTrue(message.contains("\\u001b[31m\\u000aFAKE"), message);
                    for (char c : reply.body().toCharArray()) {
                        assertFalse(c < 0x20 || c == 0x7F, "control character in the response");
                    }
                    var failed = post(server.get().port(), "/note-fails?tag=%0AFAKE%1B%5B2J",
                            "{\"note\":\"\\u001b[31m\\nFAKE: forged line\"}", CT_JSON);
                    assertEquals(500, failed.status());
                    var records = log.records();
                    assertFalse(records.isEmpty(), "the 500 is logged");
                    for (var record : records) {
                        assertFalse(record.contains("\u001b"), record);
                        for (var line : record.split("\n")) {
                            assertFalse(line.startsWith("FAKE"), record);
                        }
                    }
                    assertTrue(String.join("\n", records).contains("\\x1b[31m\\x0aFAKE"), records.toString());
                }),
                test("a second readJson, or readJson after bytes(), is 500 (IllegalStateException)", () -> {
                    var twice = post(server.get().port(), "/twice", "{\"name\":\"a\",\"quantity\":1}", CT_JSON);
                    assertEquals(500, twice.status());
                    assertFalse(twice.body().contains("detail"), twice.body());
                    assertEquals(500, post(server.get().port(), "/after-bytes", "{}", CT_JSON).status());
                    assertEquals(500, post(server.get().port(), "/body-after", "{\"name\":\"a\",\"quantity\":1}", CT_JSON)
                            .status());
                    assertTrue(log.records().stream().anyMatch(r -> r.contains("IllegalStateException")
                            && r.contains("already read by readJson()")), log.records().toString());
                }),
                test("a mapper for WebException makes its own response for 415 and 400 from readJson", () -> {
                    var router = Router.builder()
                            .post("/echo", r -> Response.text(200, r.readJson(ItemApi.ITEM).toString()))
                            .onException(WebException.class, (e, r) -> Response.text(e.status(),
                                    "custom " + e.status() + " errors=" + e.errors().size()
                                            + " accept=" + e.headers().first("Accept").orElse("-")))
                            .build();
                    try (var own = Support.serve(router)) {
                        var unsupported = post(own.port(), "/echo", "{}", "Content-Type: text/plain");
                        assertEquals(415, unsupported.status());
                        assertEquals("custom 415 errors=0 accept=application/json", unsupported.body());
                        var invalid = post(own.port(), "/echo", "{\"quantity\":\"x\"}", CT_JSON);
                        assertEquals(400, invalid.status());
                        assertEquals("custom 400 errors=2 accept=-", invalid.body());
                        var syntax = post(own.port(), "/echo", "{", CT_JSON);
                        assertEquals("custom 400 errors=0 accept=-", syntax.body());
                    }
                }),
                test("a body over jsonLimits.maxInputSize, streamed chunked and many times larger: 413", () -> {
                    var client = Support.client();
                    var sent = new AtomicLong();
                    long size = 2L << 20; // 2 MiB against a limit of 1 KiB, below maxBodySize (10 MiB)
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.get().port() + "/echo"))
                            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new Endless(size, sent))).build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(413, response.statusCode(), response.body());
                    assertEquals("Request body is larger than 1024 bytes.",
                            ((JsonObject) spola.json.Json.parse(response.body())).string("detail"));
                    client.close();
                }),
                test("readJson stops reading one byte past maxInputSize (the body is never read whole)", () -> {
                    var sent = new AtomicLong();
                    var e = assertThrows(WebException.class, () -> JsonBody.read(
                            Headers.of("Content-Type", "application/json"), new Endless(Long.MAX_VALUE, sent), JSON,
                            JsonLimits.DEFAULT.withMaxInputSize(1_024)));
                    assertEquals(413, e.status());
                    assertEquals(1_025L, sent.get());
                    var padded = "[" + "1,".repeat(510) + "1] ";
                    assertEquals(1_024, padded.length());
                    var value = JsonBody.read(Headers.of("Content-Type", "application/json"),
                            new java.io.ByteArrayInputStream(padded.getBytes(StandardCharsets.US_ASCII)), JSON,
                            JsonLimits.DEFAULT.withMaxInputSize(1_024));
                    assertTrue(value instanceof JsonArray, "exactly at the limit is fine");
                }),
                test("a body over HttpLimits.maxBodySize still gets spola.http's status", () -> {
                    var router = Router.builder()
                            .post("/echo", r -> Response.text(200, r.readJson(JSON).toString()))
                            .onException(Exception.class, (e, r) -> Response.text(500, "swallowed"))
                            .build();
                    var options = Support.options().withLimits(Support.options().limits().withMaxBodySize(100));
                    try (var small = HttpServer.start(options, router)) {
                        var body = "[" + "1,".repeat(500) + "1]";
                        var request = "POST /echo HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                                + "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
                                + Integer.toHexString(body.length()) + "\r\n" + body + "\r\n0\r\n\r\n";
                        var reply = Support.raw(small.port(), request.getBytes(StandardCharsets.ISO_8859_1), false);
                        assertEquals(413, reply.status(), reply.toString());
                        assertFalse(reply.body().contains("swallowed"));
                        assertEquals("text/plain; charset=utf-8", reply.header("Content-Type"));
                        assertEquals("close", reply.header("Connection"));
                    }
                }),
                test("readJson(codec, limits) goes before the router's limits", () -> {
                    var body = "{\"name\":\"" + "x".repeat(2_000) + "\",\"quantity\":1}";
                    assertEquals(413, post(server.get().port(), "/echo", body, CT_JSON).status(), "router: 1 KiB");
                    assertEquals(200, post(server.get().port(), "/own-large", body, CT_JSON).status(), "own: 64 KiB");
                    var small = post(server.get().port(), "/own-small", "{\"name\":\"" + "x".repeat(60)
                            + "\",\"quantity\":1}", CT_JSON);
                    assertEquals(413, small.status(), "own: 50 bytes");
                    assertEquals("Request body is larger than 50 bytes.", small.json().string("detail"));
                }))
                .beforeAll(() -> server.set(Support.serve(Router.builder()
                        .jsonLimits(JsonLimits.DEFAULT.withMaxInputSize(1_024).withMaxStringLength(50))
                        .post("/echo", r -> Responses.json(r, 200, ItemApi.ITEM, r.readJson(ItemApi.ITEM)))
                        .post("/any", r -> Responses.json(r, 200, r.readJson(JSON)))
                        .post("/any-default", r -> Responses.json(r, 200, r.readJson(JSON, JsonLimits.DEFAULT)))
                        .post("/order", r -> Response.text(200, r.readJson(ORDER).toString()))
                        .post("/ints", r -> Response.text(200, r.readJson(list(INT), JsonLimits.DEFAULT).toString()))
                        .post("/note", r -> Response.text(200, r.readJson(JsonCodec.object(Note::new,
                                field("note", NOTE, Note::note))).toString()))
                        .post("/note-fails", r -> {
                            var note = r.readJson(PLAIN_NOTE).note();
                            throw new IllegalStateException("cannot store " + note + " tag " + r.query("tag").orElse(""));
                        })
                        .post("/twice", r -> {
                            r.readJson(ItemApi.ITEM);
                            return Response.text(200, r.readJson(ItemApi.ITEM).toString());
                        })
                        .post("/after-bytes", r -> {
                            r.bytes();
                            return Response.text(200, r.readJson(JSON).toString());
                        })
                        .post("/body-after", r -> {
                            r.readJson(ItemApi.ITEM);
                            return Response.text(200, String.valueOf(r.withAttribute(ScaleTests.CLIENT, "x").body()));
                        })
                        .post("/own-large", r -> Response.text(200, r.readJson(ItemApi.ITEM,
                                JsonLimits.DEFAULT.withMaxInputSize(64 * 1_024)).name().length() + ""))
                        .post("/own-small", r -> Response.text(200, r.readJson(ItemApi.ITEM,
                                JsonLimits.DEFAULT.withMaxInputSize(50)).toString()))
                        .log(log)
                        .build())))
                .afterAll(() -> server.get().close());
    }

    /** {@code [1,1,1,...} up to {@code size} bytes (never a valid end), counting what is read. */
    static final class Endless extends InputStream {
        private final long size;
        private final AtomicLong sent;
        private long position;

        Endless(long size, AtomicLong sent) {
            this.size = size;
            this.sent = sent;
        }

        @Override
        public int read() {
            if (position >= size) {
                return -1;
            }
            int b = position == 0 ? '[' : (position % 2 == 1 ? '1' : ',');
            position++;
            sent.incrementAndGet();
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int n = 0;
            while (n < length) {
                int b = read();
                if (b < 0) {
                    break;
                }
                buffer[offset + n++] = (byte) b;
            }
            return n == 0 ? -1 : n;
        }
    }
}
