package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import spola.http.Response;
import spola.test.Suite;
import spola.web.ResponseCookie.SameSite;

/** Criteria 5 and 6: ResponseCookie's Set-Cookie text, its validation, and separate fields on the wire. */
final class ResponseCookieTests {

    private ResponseCookieTests() {
    }

    /**
     * A cookie that builds.
     *
     * @param name     what the case shows
     * @param cookie   builds the cookie
     * @param expected the exact {@code Set-Cookie} value
     */
    record Valid(String name, Supplier<ResponseCookie> cookie, String expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * A cookie that must not build.
     *
     * @param name    what the case shows
     * @param builder the builder whose build() must fail
     */
    record Invalid(String name, Supplier<ResponseCookie.Builder> builder) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final String DEFAULTS = "; Path=/; HttpOnly; SameSite=Lax";

    static final List<Valid> VALID = List.of(
            new Valid("defaults: Path=/, HttpOnly, SameSite=Lax", () -> ResponseCookie.of("a", "1").build(), "a=1" + DEFAULTS),
            new Valid("an empty value", () -> ResponseCookie.of("a", "").build(), "a=" + DEFAULTS),
            new Valid("a quoted value", () -> ResponseCookie.of("a", "\"x1\"").build(), "a=\"x1\"" + DEFAULTS),
            new Valid("an empty quoted value", () -> ResponseCookie.of("a", "\"\"").build(), "a=\"\"" + DEFAULTS),
            new Valid("every cookie-octet", () -> ResponseCookie.of("a", "!#$%&'()*+-./:<=>?@[]^_`{|}~").build(),
                    "a=!#$%&'()*+-./:<=>?@[]^_`{|}~" + DEFAULTS),
            new Valid("path", () -> ResponseCookie.of("a", "1").path("/app/x y").build(),
                    "a=1; Path=/app/x y; HttpOnly; SameSite=Lax"),
            new Valid("domain", () -> ResponseCookie.of("a", "1").domain("example.com").build(),
                    "a=1; Path=/; Domain=example.com; HttpOnly; SameSite=Lax"),
            new Valid("a leading dot in domain is removed", () -> ResponseCookie.of("a", "1").domain(".Sub-1.example.com")
                    .build(), "a=1; Path=/; Domain=Sub-1.example.com; HttpOnly; SameSite=Lax"),
            new Valid("maxAge 0", () -> ResponseCookie.of("a", "1").maxAge(Duration.ZERO).build(),
                    "a=1; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"),
            new Valid("maxAge 30 days", () -> ResponseCookie.of("a", "1").maxAge(Duration.ofDays(30)).build(),
                    "a=1; Path=/; Max-Age=2592000; HttpOnly; SameSite=Lax"),
            new Valid("secure", () -> ResponseCookie.of("a", "1").secure(true).build(),
                    "a=1; Path=/; Secure; HttpOnly; SameSite=Lax"),
            new Valid("httpOnly off", () -> ResponseCookie.of("a", "1").httpOnly(false).build(), "a=1; Path=/; SameSite=Lax"),
            new Valid("SameSite=Strict", () -> ResponseCookie.of("a", "1").sameSite(SameSite.STRICT).build(),
                    "a=1; Path=/; HttpOnly; SameSite=Strict"),
            new Valid("SameSite=None with Secure", () -> ResponseCookie.of("a", "1").sameSite(SameSite.NONE).secure(true)
                    .build(), "a=1; Path=/; Secure; HttpOnly; SameSite=None"),
            new Valid("every attribute, in the fixed order", () -> ResponseCookie.of("id", "v").sameSite(SameSite.STRICT)
                    .secure(true).maxAge(Duration.ofHours(1)).domain("example.com").path("/p").build(),
                    "id=v; Path=/p; Domain=example.com; Max-Age=3600; Secure; HttpOnly; SameSite=Strict"),
            new Valid("expire", () -> ResponseCookie.expire("a").build(), "a=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"),
            new Valid("expire with path and domain", () -> ResponseCookie.expire("a").path("/app").domain("example.com")
                    .build(), "a=; Path=/app; Domain=example.com; Max-Age=0; HttpOnly; SameSite=Lax"),
            new Valid("__Host- with Secure, Path=/ and no domain", () -> ResponseCookie.of("__Host-id", "1").secure(true)
                    .build(), "__Host-id=1; Path=/; Secure; HttpOnly; SameSite=Lax"),
            new Valid("expire a __Host- cookie", () -> ResponseCookie.expire("__Host-id").secure(true).build(),
                    "__Host-id=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax"),
            new Valid("__Secure- with Secure, a path and a domain", () -> ResponseCookie.of("__Secure-id", "1").secure(true)
                    .path("/a").domain("example.com").build(),
                    "__Secure-id=1; Path=/a; Domain=example.com; Secure; HttpOnly; SameSite=Lax"),
            new Valid("prefixes are case-sensitive: __host- is a plain name", () -> ResponseCookie.of("__host-id", "1")
                    .domain("example.com").build(), "__host-id=1; Path=/; Domain=example.com; HttpOnly; SameSite=Lax"),
            new Valid("a later setter replaces an earlier one", () -> ResponseCookie.of("a", "1").path("/x").path("/")
                    .secure(true).secure(false).build(), "a=1" + DEFAULTS));

    static final List<Invalid> INVALID = List.of(
            new Invalid("an empty name", () -> ResponseCookie.of("", "1")),
            new Invalid("a name with =", () -> ResponseCookie.of("a=b", "1")),
            new Invalid("a name with a space", () -> ResponseCookie.of("a b", "1")),
            new Invalid("a name with a control character", () -> ResponseCookie.of("a\u0001\n", "1")),
            new Invalid("a name with ;", () -> ResponseCookie.of("a;", "1")),
            new Invalid("a value with ;", () -> ResponseCookie.of("a", "1;Domain=evil.com")),
            new Invalid("a value with ,", () -> ResponseCookie.of("a", "1,2")),
            new Invalid("a value with a space", () -> ResponseCookie.of("a", "1 2")),
            new Invalid("a value with \" in the middle", () -> ResponseCookie.of("a", "1\"2")),
            new Invalid("a value with only an opening quote", () -> ResponseCookie.of("a", "\"12")),
            new Invalid("a value with non-ASCII", () -> ResponseCookie.of("a", "å")),
            new Invalid("a value with a backslash", () -> ResponseCookie.of("a", "1\\2")),
            new Invalid("a value with CR LF", () -> ResponseCookie.of("a", "1\r\nSet-Cookie: x=1")),
            new Invalid("a path with ;", () -> ResponseCookie.of("a", "1").path("/a;Domain=x")),
            new Invalid("a path not starting with /", () -> ResponseCookie.of("a", "1").path("app")),
            new Invalid("an empty path", () -> ResponseCookie.of("a", "1").path("")),
            new Invalid("a path with a control character", () -> ResponseCookie.of("a", "1").path("/a\u001b")),
            new Invalid("a path with non-ASCII", () -> ResponseCookie.of("a", "1").path("/å")),
            new Invalid("a domain with /", () -> ResponseCookie.of("a", "1").domain("example.com/x")),
            new Invalid("a domain with an empty label", () -> ResponseCookie.of("a", "1").domain("a..com")),
            new Invalid("a domain label starting with -", () -> ResponseCookie.of("a", "1").domain("-a.com")),
            new Invalid("an empty domain", () -> ResponseCookie.of("a", "1").domain(".")),
            new Invalid("a negative maxAge", () -> ResponseCookie.of("a", "1").maxAge(Duration.ofSeconds(-1))),
            new Invalid("a maxAge with a fraction of a second", () -> ResponseCookie.of("a", "1").maxAge(Duration.ofMillis(1500))),
            new Invalid("SameSite=None without Secure", () -> ResponseCookie.of("a", "1").sameSite(SameSite.NONE)),
            new Invalid("__Secure- without Secure", () -> ResponseCookie.of("__Secure-id", "1")),
            new Invalid("__Host- without Secure", () -> ResponseCookie.of("__Host-id", "1")),
            new Invalid("__Host- with a domain", () -> ResponseCookie.of("__Host-id", "1").secure(true).domain("example.com")),
            new Invalid("__Host- with a path other than /", () -> ResponseCookie.of("__Host-id", "1").secure(true)
                    .path("/app")));

    /** Fails if {@code text} holds anything but printable ASCII. */
    static void assertPrintable(String text) {
        for (char c : text.toCharArray()) {
            assertTrue(c >= 0x20 && c <= 0x7E, "U+" + Integer.toHexString(c) + " in "
                    + text.replace("\n", "<LF>").replace("\r", "<CR>").replace("\u001b", "<ESC>"));
        }
    }

    static Suite tests() {
        return suite("ResponseCookie and Set-Cookie",
                parameterized("valid cookies (" + VALID.size() + " cases): the exact Set-Cookie text", VALID, c -> {
                    var cookie = c.cookie().get();
                    assertEquals(c.expected(), cookie.headerValue());
                    assertEquals(c.expected(), cookie.toString());
                    assertFalse(cookie.headerValue().contains("Expires"));
                    var response = Responses.withCookie(Response.text(200, "x"), cookie);
                    assertEquals(List.of(c.expected()), response.headers().all("Set-Cookie"));
                }),
                parameterized("invalid cookies (" + INVALID.size() + " cases): IllegalArgumentException, printable message",
                        INVALID, c -> {
                            var builder = c.builder().get();
                            var e = assertThrows(IllegalArgumentException.class, builder::build);
                            assertPrintable(e.getMessage());
                            assertTrue(e.getMessage().length() < 300, e.getMessage());
                        }),
                test("accessors and the defaults", () -> {
                    var cookie = ResponseCookie.of("a", "1").build();
                    assertEquals("a", cookie.name());
                    assertEquals("1", cookie.value());
                    assertEquals("/", cookie.path());
                    assertTrue(cookie.domain().isEmpty());
                    assertTrue(cookie.maxAge().isEmpty());
                    assertFalse(cookie.secure());
                    assertTrue(cookie.httpOnly());
                    assertEquals(SameSite.LAX, cookie.sameSite());
                    var full = ResponseCookie.of("a", "1").domain(".example.com").maxAge(Duration.ofMinutes(2)).build();
                    assertEquals("example.com", full.domain().orElseThrow());
                    assertEquals(Duration.ofSeconds(120), full.maxAge().orElseThrow());
                    assertEquals(full, ResponseCookie.of("a", "1").domain("example.com").maxAge(Duration.ofSeconds(120))
                            .build());
                    assertEquals(Duration.ZERO, ResponseCookie.expire("a").build().maxAge().orElseThrow());
                }),
                test("the builder is immutable: a shared base gives independent cookies", () -> {
                    var base = ResponseCookie.of("a", "1");
                    var secure = base.secure(true).build();
                    var plain = base.build();
                    assertEquals("a=1; Path=/; Secure; HttpOnly; SameSite=Lax", secure.headerValue());
                    assertEquals("a=1" + DEFAULTS, plain.headerValue());
                }),
                test("expire(name) cannot be given another maxAge", () -> {
                    assertThrows(IllegalStateException.class, () -> ResponseCookie.expire("a").maxAge(Duration.ofDays(1)));
                    assertThrows(NullPointerException.class, () -> ResponseCookie.of("a", null));
                    assertThrows(NullPointerException.class, () -> ResponseCookie.of(null, "1"));
                }),
                test("messages escape what they quote and say what is wrong", () -> {
                    var e = assertThrows(IllegalArgumentException.class,
                            () -> ResponseCookie.of("a\n\u001b[31m", "1").build());
                    assertEquals("cookie name \"a\\x0a\\x1b[31m\" is not a token (RFC 9110 5.6.2)", e.getMessage());
                    var value = assertThrows(IllegalArgumentException.class,
                            () -> ResponseCookie.of("a", "x".repeat(100) + ";").build());
                    assertEquals("cookie \"a\": the value \"" + "x".repeat(40) + "...\" is not cookie-octets (RFC 6265"
                            + " 4.1.1: visible US-ASCII without '\"', ',', ';' and '\\')", value.getMessage());
                    var host = assertThrows(IllegalArgumentException.class,
                            () -> ResponseCookie.of("__Host-id", "1").secure(true).path("/a").build());
                    assertEquals("cookie \"__Host-id\": the __Host- prefix needs Path=/", host.getMessage());
                }),
                test("three cookies are three separate Set-Cookie lines on the wire", () -> {
                    var router = Router.builder()
                            .get("/three", r -> Responses.withCookie(Responses.withCookie(Responses.withCookie(
                                    Response.text(200, "ok"),
                                    ResponseCookie.of("a", "1").build()),
                                    ResponseCookie.of("b", "2").maxAge(Duration.ofDays(1)).build()),
                                    ResponseCookie.expire("c").path("/x").build()))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.send(server.port(), "GET", "/three");
                        assertEquals(200, reply.status());
                        var lines = new ArrayList<String>();
                        for (var header : reply.headers()) {
                            if (header[0].equalsIgnoreCase("Set-Cookie")) {
                                lines.add(header[1]);
                            }
                        }
                        assertEquals(List.of("a=1" + DEFAULTS, "b=2; Path=/; Max-Age=86400; HttpOnly; SameSite=Lax",
                                "c=; Path=/x; Max-Age=0; HttpOnly; SameSite=Lax"), lines);
                    }
                }),
                test("known limit: java.net.CookieManager reads Max-Age without Expires as RFC 2965", () -> {
                    // java.net.HttpCookie guesses version 1 (RFC 2965) from Max-Age when Expires is missing and
                    // sends $Version="1"; s="1";$Path="/";... back: the value arrives quoted, and the quotes are
                    // kept (RFC 6265). Browsers follow RFC 6265; see ARCHITECTURE.md ("Web del 3", no Expires).
                    var router = Router.builder()
                            .get("/set", r -> Responses.withCookie(Response.text(200, "ok"), ResponseCookie.of("s", "1")
                                    .maxAge(Duration.ofDays(1)).build()))
                            .get("/echo", r -> Response.text(200, r.headers().all("Cookie") + " parsed=" + r.cookies()))
                            .build();
                    try (var server = Support.serve(router);
                         var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                 .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                                 .connectTimeout(Duration.ofSeconds(5)).build()) {
                        var base = "http://127.0.0.1:" + server.port();
                        client.send(HttpRequest.newBuilder(URI.create(base + "/set")).build(),
                                HttpResponse.BodyHandlers.ofString());
                        var echo = client.send(HttpRequest.newBuilder(URI.create(base + "/echo")).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals("[$Version=\"1\"; s=\"1\";$Path=\"/\";$Domain=\"127.0.0.1\"] parsed={$Version=[\"1\"],"
                                + " s=[\"1\"], $Path=[\"/\"], $Domain=[\"127.0.0.1\"]}", echo.body());
                    }
                }),
                test("HttpClient with a CookieManager sends the cookie back, and request.cookie reads it", () -> {
                    var manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                    try (var server = Support.serve(new ProfileApp().router());
                         var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                                 .cookieHandler(manager)
                                 .connectTimeout(Duration.ofSeconds(5)).build()) {
                        var base = "http://127.0.0.1:" + server.port() + "/profile";
                        var none = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(404, none.statusCode());
                        var saved = client.send(HttpRequest.newBuilder(URI.create(base))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString("name=Ada&age=36&interest=math")).build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, saved.statusCode(), saved.body());
                        assertEquals(List.of("profile=p1; Path=/; HttpOnly; SameSite=Lax"),
                                saved.headers().allValues("Set-Cookie"));
                        var shown = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(200, shown.statusCode(), shown.body());
                        assertEquals("Ada, 36, [math]", shown.body());
                        assertEquals(1, manager.getCookieStore().getCookies().size());
                        var forgotten = client.send(HttpRequest.newBuilder(URI.create(base)).DELETE().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(204, forgotten.statusCode());
                        var gone = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                        assertEquals(404, gone.statusCode());
                        assertEquals(List.of(), manager.getCookieStore().getCookies(),
                                "Max-Age=0 removed the cookie from the CookieManager");
                    }
                }));
    }
}
