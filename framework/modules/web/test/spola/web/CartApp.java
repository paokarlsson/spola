package spola.web;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.web.AttributeKey;
import spola.web.Param;
import spola.web.Responses;
import spola.web.Router;
import spola.web.Session;
import spola.web.SessionOptions;
import spola.web.Sessions;
import spola.web.WebRequest;

final class CartApp {

    record Cart(List<String> items) {
        static final Cart EMPTY = new Cart(List.of());

        Cart add(String item) {
            var more = new ArrayList<>(items);
            more.add(item);
            return new Cart(List.copyOf(more));
        }
    }

    static final AttributeKey<Cart> CART = AttributeKey.of("cart", Cart.class);
    static final AttributeKey<String> CUSTOMER = AttributeKey.of("customer", String.class);

    private final Sessions sessions;

    // secure: true sets Secure on every response, also over http; with false the cookie is
    // Secure when the request's scheme is https (over TLS, or behind a TLS proxy with
    // ForwardedHeaders registered before sessions.filter())
    CartApp(boolean secure) {
        sessions = Sessions.inMemory(SessionOptions.DEFAULT
                .withIdleTimeout(Duration.ofMinutes(30))
                .withCookie(cookie -> cookie.secure(secure))); // Path=/, HttpOnly, SameSite=Lax
    }

    // <form method="post" action="/cart/items"> with the field item
    Response add(WebRequest request) throws IOException {
        var item = request.readForm().required("item", Param.STRING);
        Session session = Sessions.getOrCreate(request); // request.getSession()
        session.put(CART, session.get(CART).orElse(Cart.EMPTY).add(item));
        return Responses.noContent();
    }

    Response show(WebRequest request) {
        var session = Sessions.find(request); // request.getSession(false): never creates a session
        var cart = session.flatMap(s -> s.get(CART)).orElse(Cart.EMPTY);
        var customer = session.flatMap(s -> s.get(CUSTOMER)).orElse("guest");
        return Response.text(200, customer + ": " + cart.items());
    }

    // a real sign-in checks a password first (spola.security); the new id is what matters here
    Response signIn(WebRequest request) throws IOException {
        var name = request.readForm().required("name", Param.STRING);
        Session session = Sessions.renew(request); // request.changeSessionId(): a new id, the cart stays
        session.put(CUSTOMER, name);
        return Responses.noContent();
    }

    Response signOut(WebRequest request) {
        Sessions.invalidate(request); // session.invalidate(): the session is gone, the cookie removed
        return Responses.noContent();
    }

    Router router() {
        return Router.builder()
                .filter(sessions.filter())
                .post("/cart/items", this::add)
                .get("/cart", this::show)
                .post("/sign-in", this::signIn)
                .post("/sign-out", this::signOut)
                .build();
    }

    public static void main(String[] args) throws Exception {
        // behind a TLS proxy that forwards https://shop.example.com to port 8080; the router has
        // no ForwardedHeaders, so the scheme is http and secure = true sets Secure
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new CartApp(true).router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
