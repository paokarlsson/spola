package spola.web;

import static spola.validation.Constraints.email;
import static spola.validation.Constraints.length;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.range;
import static spola.validation.Constraints.size;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.validation.Validator;
import spola.web.FormBinder;
import spola.web.Param;
import spola.web.Router;
import spola.web.WebRequest;

final class SignupApp {

    record Signup(String email, String password, Optional<Integer> age, List<String> interests, boolean terms) {
    }

    // <form method="post" action="/signup"> with email, password, age, interests (checkboxes) and terms
    static final FormBinder<Signup> SIGNUP = f -> new Signup(
            f.required("email", Param.STRING),
            f.required("password", Param.STRING),
            f.optional("age", Param.INT),
            f.all("interests", Param.STRING),
            f.flag("terms"));

    static final Validator<Signup> SIGNUP_RULES = Validator.<Signup>builder()
            .field("email", Signup::email, notBlank(), email())
            .field("password", Signup::password, length(12, 128))
            .optional("age", Signup::age, range(13, 150))
            .field("interests", Signup::interests, size(0, 5))
            .rule("terms", Signup::terms, "accepted", "must be accepted")
            .build();

    private final Map<String, Signup> users = new ConcurrentHashMap<>();

    Response signup(WebRequest request) throws IOException {
        Signup signup = request.readForm(SIGNUP, SIGNUP_RULES); // valid, or 400 with every error
        users.put(signup.email(), signup);
        return Response.text(201, "Welcome, " + signup.email() + ".");
    }

    Router router() {
        return Router.builder()
                .post("/signup", this::signup)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new SignupApp().router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
