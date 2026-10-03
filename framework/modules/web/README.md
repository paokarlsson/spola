# spola.web

Webblagret i Spola ovanpå `spola.http`: routes som skrivs som kod, sökvägsvariabler och
queryparametrar med strikt procentavkodning och typade värden, filter, typade
request-attribut och central felhantering med felsvar i RFC 9457-format
(`application/problem+json`). Modulen ersätter kärnan i Spring MVC (`DispatcherServlet`,
`@RestController`, `@GetMapping`, `@PathVariable`, `@RequestParam`, `OncePerRequestFilter`,
`@ControllerAdvice`/`@ExceptionHandler`, `ProblemDetail`). Inga annoteringar, ingen
reflektion, ingen classpath-scanning: en `Router` är en vanlig `spola.http.Handler` som byggs
med metodanrop och startas med `HttpServer.start(options, router)`.

JSON-kroppar in och ut, `Content-Type`/`Accept`, cookies och formulärkroppar kommer i 08b.

| Spring MVC | Spola |
|---|---|
| `@RestController` + `@GetMapping("/orders/{id}")` | `Router.builder().get("/orders/{id}", handler)` |
| `@PathVariable long id` | `request.pathParam("id", Param.LONG)` |
| `@RequestParam(defaultValue = "20") int limit` | `request.query("limit", Param.INT).orElse(20)` |
| `@RequestParam Status status` (obligatorisk) | `request.requiredQuery("status", Param.enumOf(Status.class))` |
| `@RequestParam List<String> tag` | `request.queryAll("tag")` |
| `@RequestMapping("/admin")` på klassen | `group("/admin", admin -> ...)` |
| `OncePerRequestFilter`, `HandlerInterceptor` | `Filter`: `(request, next) -> next.proceed(request)` |
| `request.setAttribute("user", user)` | `request.withAttribute(USER, user)` med `AttributeKey<User>` |
| `@ControllerAdvice` + `@ExceptionHandler` | `onException(Klass.class, (e, request) -> response)` |
| `ResponseStatusException`, `ErrorResponseException` | `WebException` (kan ärvas) |
| `ProblemDetail` | `Problem` (`toResponse()`) |

## Ett komplett exempel

### Spring Boot

```java
@SpringBootApplication
public class ShopApp {
    public static void main(String[] args) {
        SpringApplication.run(ShopApp.class, args); // server.port=8080
    }
}

record Order(long id, String customer, int quantity) {
}

class OrderNotFoundException extends ErrorResponseException {
    OrderNotFoundException(long id) {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No order " + id + "."), null);
    }
}

@RestController
class OrderController {
    private final Map<Long, Order> orders = new ConcurrentHashMap<>();

    @GetMapping("/orders/{id}")
    String show(@PathVariable long id) {
        var order = orders.get(id);
        if (order == null) {
            throw new OrderNotFoundException(id);
        }
        return order.toString();
    }

    @GetMapping("/orders")
    String search(@RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String customer) {
        return orders.values().stream()
                .filter(o -> customer == null || o.customer().equals(customer))
                .limit(limit).map(Order::toString).collect(Collectors.joining("\n"));
    }
}

@RestController
@RequestMapping("/admin")
class AdminController {
    @DeleteMapping("/orders/{id}")
    String remove(@PathVariable long id, @RequestAttribute("user") String user) {
        return "order " + id + " removed by " + user;
    }
}

@Component
class AdminFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var user = request.getHeader("X-User");
        if (user == null) {
            response.sendError(401);
            return;
        }
        request.setAttribute("user", user);
        chain.doFilter(request, response);
    }
}

@ControllerAdvice
class Errors {
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The order is locked.");
    }
}
```

### Spola

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.web.AttributeKey;
import spola.web.Param;
import spola.web.Problem;
import spola.web.Router;
import spola.web.WebException;
import spola.web.WebRequest;

record Order(long id, String customer, int quantity) {
}

final class OrderNotFound extends WebException {
    OrderNotFound(long id) {
        super(404, "Order not found", "No order " + id + ".");
    }
}

final class ShopApp {
    static final AttributeKey<String> USER = AttributeKey.of("user", String.class);

    private final Map<Long, Order> orders = new ConcurrentHashMap<>();

    Response show(WebRequest request) {
        long id = request.pathParam("id", Param.LONG);
        var order = orders.get(id);
        if (order == null) {
            throw new OrderNotFound(id);
        }
        return Response.text(200, order.toString());
    }

    Response search(WebRequest request) {
        int limit = request.query("limit", Param.INT).orElse(20);
        var customer = request.query("customer");
        return Response.text(200, orders.values().stream()
                .filter(o -> customer.isEmpty() || o.customer().equals(customer.get()))
                .limit(limit).map(Order::toString).collect(Collectors.joining("\n")));
    }

    Response remove(WebRequest request) {
        long id = request.pathParam("id", Param.LONG);
        return Response.text(200, "order " + id + " removed by " + request.attribute(USER).orElseThrow());
    }

    Router router() {
        return Router.builder()
                .get("/orders/{id}", this::show)
                .get("/orders", this::search)
                .group("/admin", admin -> admin
                        .filter((request, next) -> {
                            var user = request.headers().first("X-User");
                            if (user.isEmpty()) {
                                return Problem.of(401).withInstance(request.path()).toResponse();
                            }
                            return next.proceed(request.withAttribute(USER, user.get()));
                        })
                        .delete("/orders/{id}", this::remove))
                .onException(IllegalStateException.class, (e, request) ->
                        Problem.of(409, "The order is locked.").withInstance(request.path()).toResponse())
                .build();
    }

    public static void main(String[] args) throws Exception {
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new ShopApp().router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
```

`OrderNotFound` behöver ingen mapper: en `WebException` utan mapper blir sitt problem-svar
(`{"type":"about:blank","title":"Order not found","status":404,"detail":"No order 7.","instance":"/orders/7"}`).
Ett `IllegalStateException` från en handler eller ett filter går till sin mapper; ett okänt
undantag blir 500 utan meddelande (det loggas escapat på `ERROR`, logger `spola.web`).

## Mönster och matchning

- Ett mönster börjar med `/`. Segment: literal (`orders`), variabel (`{id}`, ett helt
  segment, namnet en Java-identifierare och unikt i mönstret) eller, bara sist, catch-all
  (`{path...}`, minst ett segment). `request.pathParam("path")` ger de avkodade segmenten
  med `/` emellan, `request.pathParamSegments("path")` ger dem som lista.
- Inga reguljära uttryck, jokertecken (`*`, `**`, `?`), delsegment (`{id}.json`) eller `%`
  i mönster. Fel hittas av `build()`, alla på en gång (`RouterDefinitionException`).
- Matchningen beror inte på registreringsordningen: segment för segment från vänster går
  literal före variabel före catch-all, med backtracking. Metoden väljer bland routes med
  samma sökväg; finns sökvägen men inte metoden blir det 405 med `Allow`.
- `HEAD` utan egen route går till `GET`-routen; `OPTIONS` utan egen route ger 204 med `Allow`.

## Avvikelser från Spring MVC

| Område | Spring MVC (Spring Boot 3.x) | Spola |
|---|---|---|
| Avslutande snedstreck | `/orders/` matchar inte `/orders` (Spring 6; tidigare matchade det) | Samma, strikt åt båda hållen: `/orders/` matchar bara ett mönster som slutar med `/`. |
| Matchningsordning | Mest specifika mönstret enligt `PathPattern.SPECIFICITY_COMPARATOR` (antal variabler, jokertecken, längd) | Segment för segment från vänster: literal före variabel före catch-all, med backtracking. `/a/b/{y}` vinner över `/a/{x}/c` för `/a/b/c`. |
| Två mönster med samma form | `/a/{x}` och `/a/{y}` ger fel vid första anropet ("Ambiguous handler methods") | Fel vid `build()`, med båda mönstren i meddelandet. |
| Strikt sökväg | `StrictHttpFirewall` (Spring Security) avvisar `%2F`, `%5C`, `;`, `//`, `.`/`..` | Alltid på, utan Spring Security: felaktig UTF-8, överlång kodning, kodade surrogat, `%2F`, `%5C`, kodade styrtecken, `.`/`..` (även kodade) och `//` ger 400 före routing. `;` är tillåtet (vanlig data). |
| Avkodning | Sökvägen avkodas före matchning (`UrlPathHelper`), `%2F` kan bli ett snedstreck beroende på inställningar | Sökvägen delas först, sedan avkodas varje segment en gång; `%2F` ger 400, `%252F` är texten `%2F`. |
| Parametergräns | Tomcats `maxParameterCount` 1 000 (Spring Boot 3.x); fler parametrar ignoreras tyst eller ger 400 beroende på version | Högst 1 000 par; fler ger 400. |
| Typomvandling | `ConversionService`: `" 5"` kan bli 5 med trimning, `"1"` kan bli `true` | Strikt: inga blanktecken, inget `+`, `true`/`false` exakt, enum-namn exakt; fel ger 400 med parameterns namn. |
| Felsvar | `ProblemDetail` när `spring.mvc.problemdetails.enabled=true`, annars Boots `/error`-JSON | Alltid `application/problem+json`; `detail` och strängar i extra medlemmar kortas till 2 000 tecken. |
| `HEAD`/`OPTIONS` | Automatiska | Automatiska, men en egen `HEAD`- eller `OPTIONS`-route går före. |
| Filter och 404 | Servlet-filter körs för allt, interceptors bara för en hittad handler | Routerns filter körs för allt (också 400, 404, 405, automatiskt `OPTIONS`); en grupps filter bara för gruppens routes. |
