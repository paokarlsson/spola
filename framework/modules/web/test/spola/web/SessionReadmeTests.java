package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.web.SessionSupport.issued;
import static spola.web.SessionSupport.setCookies;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import spola.test.Suite;

/** Criterion 14: the README's section "Sessioner", its example CartApp.java and the table rows. */
final class SessionReadmeTests {

    private SessionReadmeTests() {
    }

    static String readme() throws Exception {
        return Files.readString(Path.of("modules/web/README.md"));
    }

    /** The section from its heading to the next {@code ## } heading. */
    static String section() throws Exception {
        var text = readme();
        int start = text.indexOf("\n## Sessioner\n");
        assertTrue(start > 0, "the README has a section Sessioner");
        int end = text.indexOf("\n## ", start + 1);
        return text.substring(start, end);
    }

    /** The production setting of the section "Sessioner", as a code block. */
    static final String PRODUCTION = """
            ```java
            Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT
                    .withCookieName("__Host-SESSION")
                    .withCookie(cookie -> cookie.secure(true)));
            // Set-Cookie: __Host-SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax   (also over http)
            ```
            """;

    static HttpResponse<String> send(HttpClient client, String base, String method, String path, String form)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        if (form == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .method(method, HttpRequest.BodyPublishers.ofString(form));
        }
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        response.headers().allValues("Set-Cookie").forEach(SessionSupport::recordSetCookie);
        SessionSupport.TEXTS.add(response.body());
        return response;
    }

    static Suite tests() {
        return suite("README sessions",
                test("the README shows CartApp.java as it is compiled and tested here", () -> {
                    var source = Files.readString(Path.of("modules/web/test/spola/web/CartApp.java"));
                    var header = "package spola.web;\n\n";
                    assertTrue(source.startsWith(header));
                    var block = "### Spola\n\n```java\n" + source.substring(header.length()) + "```\n";
                    assertTrue(section().contains(block), "the README's Spola session example differs from CartApp.java");
                }),
                test("the Spring Boot code comes first and shows HttpSession, changeSessionId, invalidate and the"
                        + " properties", () -> {
                    var section = section();
                    assertTrue(section.indexOf("### Spring Boot") < section.indexOf("### Spola"));
                    var spring = section.substring(section.indexOf("### Spring Boot"), section.indexOf("### Spola"));
                    for (var text : List.of("@RestController", "HttpSession session", "session.getAttribute(\"cart\")",
                            "session.setAttribute(\"cart\"", "request.changeSessionId()", "session.invalidate()",
                            "request.getSession(false)", "server.servlet.session.timeout=30m",
                            "server.servlet.session.cookie.secure=true")) {
                        assertTrue(spring.contains(text), "the Spring Boot code lacks " + text);
                    }
                    assertTrue(section.contains("`__Host-SESSION` med `secure(true)`"), "recommends __Host-SESSION");
                    assertTrue(section.contains(".withCookieName(\"__Host-SESSION\")"));
                }),
                test("the comparison table and the deviation table have the new rows", () -> {
                    var text = readme();
                    for (var row : List.of(
                            "| `HttpSession` som parameter, `request.getSession()` | `Sessions.getOrCreate(request)` (`Session`) |",
                            "| `request.getSession(false)` | `Sessions.find(request)` (`Optional<Session>`) |",
                            "| `session.getAttribute(\"cart\")` med cast |",
                            "| `session.setAttribute(\"cart\", cart)`, `removeAttribute` |",
                            "| `request.changeSessionId()` | `Sessions.renew(request)` |",
                            "| `session.invalidate()` | `Sessions.invalidate(request)` |",
                            "| `server.servlet.session.timeout=30m` |",
                            "| `server.servlet.session.cookie.secure=true` |",
                            "| Sessionens id | `HttpSession.getId()` ger id:t | Ingen `getId()`",
                            "| Gräns för antalet sessioner |",
                            "i stället för att ta bort sessioner",
                            "| Cookienamn | `JSESSIONID` (Tomcat) | `SESSION`, som Spring Session, i stället för `JSESSIONID`.",
                            "| `Secure` på sessionscookien |", "Samma regel: `Secure` när `request.scheme()` är `https`",
                            "| Persistens och flera servrar |", "Ingen persistens och ingen delning",
                            "| Städning av utgångna sessioner |", "Ingen bakgrundstråd",
                            "| `Cache-Control` för sessionscookien |", "`Cache-Control: no-store`")) {
                        assertTrue(text.contains(row), "missing " + row);
                    }
                }),
                test("the example through a server with HttpClient and a CookieManager", () -> {
                    var manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
                    try (var server = Support.serve(new CartApp(false).router());
                            var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(manager)
                                    .connectTimeout(Duration.ofSeconds(5)).build()) {
                        var base = "http://127.0.0.1:" + server.port();
                        assertEquals("guest: []", send(client, base, "GET", "/cart", null).body());
                        assertEquals(List.of(), manager.getCookieStore().getCookies(), "showing the cart created nothing");
                        var added = send(client, base, "POST", "/cart/items", "item=tea");
                        assertEquals(204, added.statusCode(), added.body());
                        var first = added.headers().allValues("Set-Cookie");
                        assertEquals(1, first.size());
                        assertEquals(List.of("no-store"), added.headers().allValues("Cache-Control"));
                        assertEquals(204, send(client, base, "POST", "/cart/items", "item=milk").statusCode());
                        assertEquals("guest: [tea, milk]", send(client, base, "GET", "/cart", null).body());
                        var signedIn = send(client, base, "POST", "/sign-in", "name=Ada");
                        assertEquals(204, signedIn.statusCode());
                        var second = signedIn.headers().allValues("Set-Cookie");
                        assertEquals(1, second.size());
                        assertTrue(!first.equals(second), "a new id at sign-in");
                        assertEquals("Ada: [tea, milk]", send(client, base, "GET", "/cart", null).body());
                        var signedOut = send(client, base, "POST", "/sign-out", null);
                        assertEquals(List.of("SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"),
                                signedOut.headers().allValues("Set-Cookie"));
                        assertEquals(List.of(), manager.getCookieStore().getCookies());
                        assertEquals("guest: []", send(client, base, "GET", "/cart", null).body());
                    }
                }),
                test("15b: the production setting as the README shows it builds and gives __Host-SESSION with"
                        + " Secure over plain http", () -> {
                    var section = section();
                    assertTrue(section.contains(PRODUCTION), "the README shows the production setting");
                    // the code of PRODUCTION
                    Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT
                            .withCookieName("__Host-SESSION")
                            .withCookie(cookie -> cookie.secure(true)));
                    try (var server = Support.serve(SessionSupport.app(sessions))) {
                        var created = SessionSupport.send(server, "POST", "/count");
                        var id = issued(created);
                        assertEquals(List.of("__Host-SESSION=" + id + "; Path=/; Secure; HttpOnly; SameSite=Lax"),
                                setCookies(created));
                        assertEquals("found:1", SessionSupport.send(server, "GET", "/find", "Cookie: __Host-SESSION=" + id)
                                .body());
                        var removed = SessionSupport.send(server, "POST", "/invalidate", "Cookie: __Host-SESSION=" + id);
                        assertEquals(List.of("__Host-SESSION=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax"),
                                setCookies(removed));
                    }
                    for (var text : List.of("för en app som bara nås över https", "**`secure(true)`** ger `Secure` på varje svar",
                            "**`__Host-`** gör att webbläsaren", "cookie tossing", "`Domain=<förälder>`",
                            "`Csrf.cookie().withCookie(c -> c.secure(true))`", "[Bakom en omvänd proxy](#bakom-en-omvänd-proxy)")) {
                        assertTrue(section.contains(text), "the section lacks " + text);
                    }
                }),
                test("the example with secure = true sets Secure", () -> {
                    try (var server = Support.serve(new CartApp(true).router())) {
                        var request = "POST /cart/items HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
                                + "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: 8\r\n\r\nitem=tea";
                        var reply = Support.raw(server.port(), request.getBytes(StandardCharsets.US_ASCII), false);
                        SessionSupport.record(reply);
                        assertEquals(204, reply.status(), reply.toString());
                        assertEquals(List.of("SESSION=" + issued(reply) + "; Path=/; Secure; HttpOnly; SameSite=Lax"),
                                setCookies(reply));
                    }
                }));
    }
}
