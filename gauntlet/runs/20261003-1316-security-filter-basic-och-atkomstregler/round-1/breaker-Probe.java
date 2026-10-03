import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import spola.http.*;
import spola.security.*;
import spola.security.web.*;
import spola.web.*;

public class Probe {
    static int port;
    public static void main(String[] a) throws Exception {
        var hasher = Pbkdf2PasswordHasher.of(10_000, 16, 32);
        var users = new InMemoryUserStore();
        users.add(new User("ada", hasher.hash("pw"), Set.of("USER","ADMIN"), true));
        users.add(new User("bob", hasher.hash("pw"), Set.of("USER"), true));
        var auth = spola.security.Authenticator.of(users, hasher);
        var sec = WebSecurity.builder().authentication(HttpBasic.of(auth, "app"))
            .rule(RequestMatcher.under("/admin"), AccessRule.hasRole("ADMIN"))
            .rule(RequestMatcher.method("GET", RequestMatcher.under("/files")), AccessRule.hasRole("ADMIN"))
            .rule(RequestMatcher.anyRequest(), AccessRule.permitAll()).build();
        WebHandler h = r -> Response.text(200, "HANDLER " + r.path() + " id=" + WebSecurity.identity(r));
        var router = Router.builder().filter(sec.filter())
            .get("/admin", h).get("/admin/{rest...}", h).get("/files/{name}", h)
            .route("GET", "/x", h)
            .build();
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withAddress(InetAddress.getLoopbackAddress()).withPort(0), router);
        port = server.port();
        for (var line : a) {
            String[] p = line.split(" ", 3);
            String auths = p.length > 2 ? p[2] : null;
            System.out.println(line + " -> " + send(p[0], p[1], auths));
        }
        server.close();
    }
    static String send(String m, String t, String auth) throws Exception {
        try (var s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            var req = m + " " + t + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + (auth == null ? "" : "Authorization: " + auth + "\r\n") + "\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.ISO_8859_1));
            var out = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return out.replace("\r\n", " | ");
        }
    }
}
