package spola.web;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import spola.http.Response;
import spola.test.Suite;

/** Criterion 3: GET, POST, PUT, PATCH and DELETE through a real server and HttpClient. */
final class ClientTests {

    private ClientTests() {
    }

    /** method, pattern, decoded path variables, query values and body. */
    static Response echo(WebRequest request) throws Exception {
        var text = request.method() + " " + request.pattern().orElse("-")
                + " user=" + request.pathParam("user")
                + " item=" + request.pathParam("item", Param.LONG)
                + " q=" + request.queryAll("q")
                + " page=" + request.query("page", Param.INT).map(String::valueOf).orElse("-")
                + " body=" + new String(request.bytes(), StandardCharsets.UTF_8);
        return Response.text(200, text);
    }

    static Suite tests() {
        return suite("HttpClient against a router",
                test("every method reaches its route with decoded path variables and query parameters", () -> {
                    var router = Router.builder()
                            .get("/users/{user}/items/{item}", ClientTests::echo)
                            .post("/users/{user}/items/{item}", ClientTests::echo)
                            .put("/users/{user}/items/{item}", ClientTests::echo)
                            .patch("/users/{user}/items/{item}", ClientTests::echo)
                            .delete("/users/{user}/items/{item}", ClientTests::echo)
                            .build();
                    try (var server = Support.serve(router); var client = Support.client()) {
                        for (var method : new String[] {"GET", "POST", "PUT", "PATCH", "DELETE"}) {
                            var body = method.equals("GET") || method.equals("DELETE") ? null : "{\"n\":1} åäö";
                            var response = Support.call(client, server, method,
                                    "/users/%C3%A5sa%20b/items/42?q=a+b&q=%2B%26&page=3", body);
                            assertEquals(200, response.statusCode(), method);
                            assertEquals(method + " /users/{user}/items/{item} user=åsa b item=42 q=[a b, +&] page=3 body="
                                    + (body == null ? "" : body), response.body());
                            assertEquals(Optional.of("text/plain; charset=utf-8"),
                                    response.headers().firstValue("Content-Type"));
                        }
                    }
                }));
    }
}
