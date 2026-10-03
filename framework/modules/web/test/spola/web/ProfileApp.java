package spola.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.web.FormData;
import spola.web.Param;
import spola.web.ResponseCookie;
import spola.web.Responses;
import spola.web.Router;
import spola.web.WebException;
import spola.web.WebRequest;

final class ProfileApp {

    record Profile(String name, int age, List<String> interests) {
    }

    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    // <form method="post" action="/profile"> with the fields name, age and interest (several)
    Response save(WebRequest request) throws IOException {
        FormData form = request.readForm();
        var profile = new Profile(form.required("name", Param.STRING), form.required("age", Param.INT),
                form.all("interest"));
        var id = "p" + ids.incrementAndGet();
        profiles.put(id, profile);
        var cookie = ResponseCookie.of("profile", id).build(); // Path=/, HttpOnly, SameSite=Lax
        return Responses.withCookie(Response.text(200, "Saved " + profile.name() + "."), cookie);
    }

    Response show(WebRequest request) {
        var profile = request.cookie("profile").map(profiles::get)
                .orElseThrow(() -> new WebException(404, "No profile."));
        return Response.text(200, profile.name() + ", " + profile.age() + ", " + profile.interests());
    }

    Response forget(WebRequest request) {
        request.cookie("profile").ifPresent(profiles::remove);
        return Responses.withCookie(Responses.noContent(), ResponseCookie.expire("profile").build());
    }

    Router router() {
        return Router.builder()
                .post("/profile", this::save)
                .get("/profile", this::show)
                .delete("/profile", this::forget)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new ProfileApp().router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
