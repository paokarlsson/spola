package spola.security.web;

import static spola.security.web.Support.ADA;
import static spola.security.web.Support.ADA_IDENTITY;
import static spola.security.web.Support.ADA_PASSWORD;
import static spola.security.web.Support.COLON;
import static spola.security.web.Support.COLON_IDENTITY;
import static spola.security.web.Support.COLON_PASSWORD;
import static spola.security.web.Support.UNICODE;
import static spola.security.web.Support.UNICODE_IDENTITY;
import static spola.security.web.Support.UNICODE_PASSWORD;
import static spola.security.web.Support.basic;
import static spola.security.web.Support.token;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import spola.http.Response;
import spola.security.Identity;
import spola.test.Suite;
import spola.web.Router;

/** Criterion 4: malformed Authorization values, the RFC 7617 examples and the accepted forms. */
final class MalformedTests {

    private MalformedTests() {
    }

    /** One malformed {@code Authorization} value and why it is malformed. */
    record Case(String label, String value) {
    }

    /** An application where every path is permitAll, so only the credentials decide. */
    static final class Open {
        final Support.Counting counting = new Support.Counting();
        final List<Optional<Identity>> seen = new ArrayList<>();
        final Router router;

        Open() {
            var security = WebSecurity.builder()
                    .authentication(HttpBasic.of(counting.authenticator, "app"))
                    .rule(RequestMatcher.anyRequest(), AccessRule.permitAll())
                    .build();
            router = Support.router()
                    .filter(security.filter())
                    .get("/p", request -> {
                        synchronized (seen) {
                            seen.add(WebSecurity.identity(request));
                        }
                        return Response.text(200, "handled");
                    })
                    .build();
        }

        Optional<Identity> last() {
            synchronized (seen) {
                return seen.getLast();
            }
        }

        int handled() {
            synchronized (seen) {
                return seen.size();
            }
        }
    }

    static byte[] bytes(Object... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            if (part instanceof String s) {
                out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
            } else {
                for (int b : (int[]) part) {
                    out.write(b);
                }
            }
        }
        return out.toByteArray();
    }

    /** A token for "zUsrAda:zPwd??>>>~" whose standard encoding holds '+' or '/'. */
    static String urlAlphabet() {
        var standard = token("zUsrAda:zPwd??>>>~~");
        assertTrue(standard.contains("+") || standard.contains("/"), standard);
        return standard.replace('+', '-').replace('/', '_');
    }

    /** A token whose padding is followed by unused bits that are not zero: the JDK decodes it, but it is not canonical. */
    static String nonCanonical() {
        var canonical = token("zUsrAda:zPwdx"); // 13 bytes: two '=' and four unused bits
        assertTrue(canonical.endsWith("=="), canonical);
        var alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int last = canonical.length() - 3;
        char changed = alphabet.charAt(alphabet.indexOf(canonical.charAt(last)) + 1);
        var token = canonical.substring(0, last) + changed + "==";
        assertTrue(Arrays.equals(Base64.getDecoder().decode(token), Base64.getDecoder().decode(canonical)),
                "the JDK decoder accepts the non-canonical form");
        return token;
    }

    static List<Case> malformed() {
        var good = token(ADA + ":" + ADA_PASSWORD);
        var padded = token("zUsrAda:zPwdx");
        return List.of(
                new Case("no token", "Basic"),
                new Case("a tab instead of a space", "Basic\t" + good),
                new Case("a '*' in the token", "Basic " + good.substring(0, 4) + "*" + good.substring(5)),
                new Case("a space in the token", "Basic " + good.substring(0, 8) + " " + good.substring(8)),
                new Case("a '.' in the token", "Basic " + good.substring(0, 4) + "." + good.substring(5)),
                new Case("the -_ alphabet", "Basic " + urlAlphabet()),
                new Case("no padding", "Basic " + padded.replace("=", "")),
                new Case("half the padding", "Basic " + padded.substring(0, padded.length() - 1)),
                new Case("too much padding", "Basic " + padded + "===="),
                new Case("padding in the middle", "Basic QQ==" + good),
                new Case("only padding", "Basic ===="),
                new Case("non-canonical Base64", "Basic " + nonCanonical()),
                new Case("a token of 8 196 characters", "Basic " + "QUFB".repeat(2_049)),
                new Case("two credentials in one field", "Basic " + good + ", Basic " + good),
                new Case("obs-text in the token", "Basic " + good + "éééé"),
                new Case("invalid UTF-8: 0xFF", "Basic " + token(bytes("zUsrAda:zPwd", new int[] {0xFF}))),
                new Case("invalid UTF-8: overlong '/'", "Basic " + token(bytes("zUsrAda:zPwd", new int[] {0xC0, 0xAF}))),
                new Case("invalid UTF-8: an encoded surrogate", "Basic " + token(bytes("zUsr", new int[] {0xED, 0xA0, 0x80},
                        ":zPwd"))),
                new Case("invalid UTF-8: truncated sequence", "Basic " + token(bytes("zUsrAda:zPwd", new int[] {0xE6, 0x97}))),
                new Case("no colon", "Basic " + token(ADA + ADA_PASSWORD)),
                new Case("NUL in the username", "Basic " + token("zUsr\u0000Ada:" + ADA_PASSWORD)),
                new Case("LF in the username", "Basic " + token("zUsr\nAda:" + ADA_PASSWORD)),
                new Case("DEL in the username", "Basic " + token("zUsrAda\u007f:" + ADA_PASSWORD)),
                new Case("NUL in the password", "Basic " + token(ADA + ":" + ADA_PASSWORD + "\u0000")),
                new Case("LF in the password", "Basic " + token(ADA + ":zPwd\nAda")),
                new Case("CR in the password", "Basic " + token(ADA + ":zPwd\rAda")),
                new Case("TAB in the password", "Basic " + token(ADA + ":zPwd\tAda")),
                new Case("DEL in the password", "Basic " + token(ADA + ":zPwd\u007fAda")),
                new Case("ESC in the password", "Basic " + token(ADA + ":zPwd\u001b[31m")),
                new Case("an empty username with a password", "Basic " + token(":" + ADA_PASSWORD)),
                new Case("only a colon", "Basic " + token(":")),
                new Case("an empty token after many spaces", "Basic          =")
        );
    }

    static Suite tests() {
        return suite("Authorization values",
                test("at least 20 malformed values: each the 401 of a wrong password, and no Authenticator call", () -> {
                    var cases = malformed();
                    assertTrue(cases.size() >= 20);
                    var app = new Open();
                    try (var server = Support.serve(app.router)) {
                        var wrong = Support.send(server.port(), "GET", "/p", basic(ADA, "zPwd-wrong"));
                        BasicTests.assert401(wrong);
                        assertEquals(1, app.counting.calls());
                        var failures = new ArrayList<String>();
                        for (var c : cases) {
                            var reply = Support.send(server.port(), "GET", "/p", c.value());
                            if (!reply.withoutDate().equals(wrong.withoutDate())) {
                                failures.add(c.label() + ": " + reply);
                            }
                        }
                        assertEquals(List.of(), failures);
                        assertEquals(1, app.counting.calls(), "the Authenticator was called for a malformed value");
                        assertEquals(1, app.counting.finds.get());
                        assertEquals(0, app.handled());
                    }
                }),
                test("a token of exactly 8 192 characters is decoded and checked once", () -> {
                    var app = new Open();
                    var password = "zPwd" + "x".repeat(6_144 - ADA.length() - 1 - 4);
                    var token = token(ADA + ":" + password);
                    assertEquals(8_192, token.length());
                    try (var server = Support.serve(app.router)) {
                        BasicTests.assert401(Support.send(server.port(), "GET", "/p", "Basic " + token));
                        assertEquals(1, app.counting.calls());
                        assertEquals(ADA, app.counting.lastUsername());
                        assertEquals(password, app.counting.lastPassword());
                    }
                }),
                test("RFC 7617's examples decode to the right username and password", () -> {
                    assertEquals("QWxhZGRpbjpvcGVuIHNlc2FtZQ==", token("Aladdin:open sesame"));
                    assertEquals("dGVzdDoxMjPCow==", token("test:123£"));
                    var app = new Open();
                    try (var server = Support.serve(app.router)) {
                        BasicTests.assert401(Support.send(server.port(), "GET", "/p", "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="));
                        assertEquals("Aladdin", app.counting.lastUsername());
                        assertEquals("open sesame", app.counting.lastPassword());
                        BasicTests.assert401(Support.send(server.port(), "GET", "/p", "Basic dGVzdDoxMjPCow=="));
                        assertEquals("test", app.counting.lastUsername());
                        assertEquals("123£", app.counting.lastPassword());
                        assertEquals(2, app.counting.calls());
                    }
                }),
                test("basic and BASIC, several spaces, ':' and non-ASCII (with a surrogate pair) in the password", () -> {
                    var app = new Open();
                    var good = token(ADA + ":" + ADA_PASSWORD);
                    try (var server = Support.serve(app.router)) {
                        for (var value : List.of("basic " + good, "BASIC " + good, "bAsIc " + good, "Basic    " + good)) {
                            var reply = Support.send(server.port(), "GET", "/p", value);
                            assertEquals(200, reply.status(), value + ": " + reply);
                            assertEquals(Optional.of(ADA_IDENTITY), app.last());
                        }
                        assertEquals(200, Support.send(server.port(), "GET", "/p", basic(COLON, COLON_PASSWORD)).status());
                        assertEquals(Optional.of(COLON_IDENTITY), app.last());
                        assertEquals(COLON_PASSWORD, app.counting.lastPassword());
                        assertEquals(200, Support.send(server.port(), "GET", "/p", basic(UNICODE, UNICODE_PASSWORD)).status());
                        assertEquals(Optional.of(UNICODE_IDENTITY), app.last());
                        assertEquals(UNICODE, app.counting.lastUsername());
                        assertEquals(UNICODE_PASSWORD, app.counting.lastPassword());
                        assertEquals(6, app.counting.calls());
                    }
                }),
                test("a Bearer value (or any other scheme) is None: anonymous, no Authenticator call", () -> {
                    var app = new Open();
                    try (var server = Support.serve(app.router)) {
                        for (var value : List.of("Bearer eyJhbGciOiJIUzI1NiJ9.e30.zPwdSignature", "Basicx " + token("a:b"),
                                "Digest username=\"zUsrAda\"", "Negotiate YIIB")) {
                            var reply = Support.send(server.port(), "GET", "/p", value);
                            assertEquals(200, reply.status(), value + ": " + reply);
                            assertEquals(Optional.empty(), app.last());
                        }
                        assertEquals(0, app.counting.calls());
                    }
                }),
                test("two Authorization fields: 400 before any authentication", () -> {
                    var app = new Open();
                    var good = basic(ADA, ADA_PASSWORD);
                    try (var server = Support.serve(app.router)) {
                        for (var pair : List.of(List.of(good, good), List.of(good, "Bearer x"), List.of("Bearer x", good),
                                List.of("Bearer x", "Bearer y"))) {
                            var reply = Support.sendWith(server.port(), "GET", "/p", pair);
                            assertEquals(400, reply.status(), String.valueOf(reply));
                            assertEquals("application/problem+json", reply.header("Content-Type"));
                            assertFalse(reply.body().contains("zUsr") || reply.body().contains("Bearer"), reply.body());
                        }
                        assertEquals(0, app.counting.calls());
                        assertEquals(0, app.handled());
                    }
                }),
                test("HttpBasic alone also refuses two fields (when another mechanism's filter did not check)", () -> {
                    var counting = new Support.Counting();
                    var basic = HttpBasic.of(counting.authenticator, "app");
                    var seen = java.util.Collections.synchronizedList(new ArrayList<String>());
                    // A filter that calls the mechanism directly, as a custom filter could.
                    var router = Support.router()
                            .filter((request, next) -> {
                                seen.add(basic.authenticate(request).getClass().getSimpleName());
                                return next.proceed(request);
                            })
                            .get("/p", request -> Response.text(200, "handled"))
                            .build();
                    try (var server = Support.serve(router)) {
                        var reply = Support.sendWith(server.port(), "GET", "/p",
                                List.of(basic(ADA, ADA_PASSWORD), basic(ADA, ADA_PASSWORD)));
                        assertEquals(400, reply.status());
                        assertEquals(List.of(), seen);
                        assertEquals(200, Support.send(server.port(), "GET", "/p", basic(ADA, ADA_PASSWORD)).status());
                        assertEquals(200, Support.send(server.port(), "GET", "/p", "Basic").status());
                        assertEquals(200, Support.send(server.port(), "GET", "/p").status());
                        assertEquals(List.of("Success", "Rejected", "None"), seen);
                        assertEquals(1, counting.calls());
                    }
                }));
    }
}
