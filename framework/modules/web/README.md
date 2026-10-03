# spola.web

Webblagret i Spola ovanpå `spola.http`: routes som skrivs som kod, sökvägsvariabler och
queryparametrar med strikt procentavkodning och typade värden, filter, typade
request-attribut och central felhantering med felsvar i RFC 9457-format
(`application/problem+json`). Modulen ersätter kärnan i Spring MVC (`DispatcherServlet`,
`@RestController`, `@GetMapping`, `@PathVariable`, `@RequestParam`, `OncePerRequestFilter`,
`@ControllerAdvice`/`@ExceptionHandler`, `ProblemDetail`). Inga annoteringar, ingen
reflektion, ingen classpath-scanning: en `Router` är en vanlig `spola.http.Handler` som byggs
med metodanrop och startas med `HttpServer.start(options, router)`.

JSON-kroppar in och ut med en uttrycklig `JsonCodec` (`request.readJson(codec)`,
`Responses.json(...)`), strikt tolkning av `Content-Type` och `Accept` (`MediaType`,
`AcceptHeader`) och innehållsförhandling (`request.negotiate(...)`) kom i 08b. Cookies och
formulärkroppar kommer i 08c.

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
| `@RequestBody Item item` (Jackson) | `request.readJson(ITEM)` med en `JsonCodec<Item>` |
| `@ResponseBody` / `ResponseEntity.ok(item)` | `Responses.json(request, 200, ITEM, item)` |
| `ResponseEntity.created(uri).body(item)` | `Responses.created(request, "/items/7", ITEM, item)` |
| `ResponseEntity.noContent().build()` | `Responses.noContent()` |
| `MediaType`, `MediaType.parseMediaTypes(accept)` | `MediaType.parse`, `AcceptHeader.parse` |
| `produces = {...}` med flera typer | `request.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)` |
| `spring.codec.max-in-memory-size`, Jacksons `StreamReadConstraints` | `RouterBuilder.jsonLimits(JsonLimits)`, `readJson(codec, limits)` |

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

## JSON in och ut: ett CRUD-exempel

### Spring Boot

```java
record Item(Long id, String name, int quantity) {
}

@RestController
@RequestMapping("/items")
class ItemController {
    private final Map<Long, Item> items = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Item> create(@RequestBody Item input) {
        var item = new Item(ids.incrementAndGet(), input.name(), input.quantity());
        items.put(item.id(), item);
        return ResponseEntity.created(URI.create("/items/" + item.id())).body(item);
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    Item show(@PathVariable long id) {
        return find(id);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    Item update(@PathVariable long id, @RequestBody Item input) {
        find(id);
        var item = new Item(id, input.name(), input.quantity());
        items.put(id, item);
        return item;
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> remove(@PathVariable long id) {
        if (items.remove(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No item " + id + ".");
        }
        return ResponseEntity.noContent().build();
    }

    private Item find(long id) {
        var item = items.get(id);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No item " + id + ".");
        }
        return item;
    }
}
```

### Spola

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.LONG;
import static spola.json.codec.JsonCodecs.STRING;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.json.codec.JsonCodec;
import spola.web.Param;
import spola.web.Responses;
import spola.web.Router;
import spola.web.WebException;
import spola.web.WebRequest;

final class ItemApi {

    record Item(long id, String name, int quantity) {
    }

    // the id is the server's: a client may leave it out
    static final JsonCodec<Item> ITEM = JsonCodec.object(Item::new,
            field("id", LONG, Item::id).orElse(0L),
            field("name", STRING, Item::name),
            field("quantity", INT, Item::quantity));

    private final Map<Long, Item> items = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    Response create(WebRequest request) throws IOException {
        var input = request.readJson(ITEM);
        var item = new Item(ids.incrementAndGet(), input.name(), input.quantity());
        items.put(item.id(), item);
        return Responses.created(request, "/items/" + item.id(), ITEM, item);
    }

    Response show(WebRequest request) {
        return Responses.json(request, 200, ITEM, find(request.pathParam("id", Param.LONG)));
    }

    Response update(WebRequest request) throws IOException {
        long id = request.pathParam("id", Param.LONG);
        find(id);
        var input = request.readJson(ITEM);
        var item = new Item(id, input.name(), input.quantity());
        items.put(id, item);
        return Responses.json(request, 200, ITEM, item);
    }

    Response remove(WebRequest request) {
        long id = request.pathParam("id", Param.LONG);
        if (items.remove(id) == null) {
            throw new WebException(404, "No item " + id + ".");
        }
        return Responses.noContent();
    }

    private Item find(long id) {
        var item = items.get(id);
        if (item == null) {
            throw new WebException(404, "No item " + id + ".");
        }
        return item;
    }

    Router router() {
        return Router.builder()
                .post("/items", this::create)
                .get("/items/{id}", this::show)
                .put("/items/{id}", this::update)
                .delete("/items/{id}", this::remove)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new ItemApi().router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
```

Exemplet ovan är `modules/web/test/spola/web/ItemApi.java` (utan paketraden), och testerna
kör det genom en riktig server. Kodeken anges uttryckligen; inget hittas med reflektion.

- `POST /items` med `{"name":"Te","quantity":5}` ger 201, `Location: /items/1` och
  `{"id":1,"name":"Te","quantity":5}` med `Content-Type: application/json` och
  `Content-Length`. `GET` ger 200, `PUT` 200, `DELETE` 204, `HEAD` samma huvuden som `GET`
  utan kropp.
- `readJson` kontrollerar i den här ordningen: `Content-Encoding` som inte är `identity` ger
  415; ett saknat, felaktigt eller annat `Content-Type` än `application/json` eller
  `application/<namn>+json`, eller en `charset` som inte är UTF-8, ger 415 med
  `Accept: application/json`; en tom kropp ger 400 (`Request body is required`); en kropp
  över `JsonLimits.maxInputSize()` (standard 4 MiB) ger 413; ogiltig JSON eller en annan
  gräns (djup, antal poster, stränglängd) ger 400 med rad och kolumn men ingen text ur
  kroppen; problem i mappningen ger 400 med alla problem i `errors`:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"Request body has 2 problems.",
 "instance":"/items","errors":[{"path":"$.name","message":"missing member"},
 {"path":"$.quantity","message":"expected int but found string"}]}
```

- Kroppen parsas medan den strömmar in och läses aldrig in hel först. Gränsen för alla routes
  sätts med `Router.builder().jsonLimits(JsonLimits.DEFAULT.withMaxInputSize(64 * 1024))`,
  och en handler kan ge egna gränser med `request.readJson(ITEM, limits)`.
  `HttpLimits.maxBodySize` i `spola.http` gäller alltid (kroppens status, 413, och
  anslutningen stängs).
- Kroppen kan bara läsas en gång: ett andra `readJson`, eller `readJson` efter `body()`/
  `bytes()`, är ett programmeringsfel (`IllegalStateException`, 500).
- `Responses.json` förhandlar mot `Accept` först (406 om JSON inte godtas), kodar värdet till
  en byte-array och svarar med `Content-Type: application/json` (utan `charset`) och
  `Content-Length`. Kan kodeken inte koda värdet (ett `null` där kodeken inte tillåter det) är
  det serverns fel: 500 utan detaljer, aldrig 400.
- Alla fel ovan är `WebException` och går genom `onException` som andra fel;
  `e.errors()` ger mappningsproblemen till en egen mapper.

## Egen innehållsförhandling

### Spring Boot

```java
@GetMapping(path = "/items/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
Item showJson(@PathVariable long id) {
    return find(id);
}

@GetMapping(path = "/items/{id}", produces = MediaType.TEXT_PLAIN_VALUE)
String showText(@PathVariable long id) {
    var item = find(id);
    return item.name() + " x " + item.quantity();
}
```

### Spola

```java
Response show(WebRequest request) {
    var item = find(request.pathParam("id", Param.LONG));
    var type = request.negotiate(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN);
    if (type.equals(MediaType.TEXT_PLAIN)) {
        return Response.text(200, item.name() + " x " + item.quantity());
    }
    return Responses.json(request, 200, ITEM, item);
}
```

`negotiate` väljer den erbjudna typen med högst kvalitet över 0. Kvaliteten för en typ
bestäms av det mest specifika intervallet i `Accept` som innehåller den (med parametrar före
utan, `text/plain` före `text/*` före `*/*`), oavsett ordningen i huvudet, så
`Accept: */*, application/json;q=0` utesluter JSON. Vid lika kvalitet vinner ordningen i
anropet, och utan `Accept` väljs den första. Inget godtagbart ger 406, ett felaktigt `Accept`
400. `request.accept()` ger det tolkade huvudet (`AcceptHeader`) och
`request.contentType()` förfrågans `MediaType`.

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
| Felaktigt `Accept` | Ger 406 (`HttpMediaTypeNotAcceptableException`) | Ger 400 med detalj: huvudet är klientens fel, inte en fråga om vad servern kan leverera. Ett `q` utanför `0`–`1`, med fler än tre decimaler eller citerat, och fler än 100 intervall, är fel. |
| `charset` i `Content-Type` | Jackson läser UTF-16/UTF-32 och flera andra kodningar | Bara UTF-8 (RFC 8259 §8.1): en annan `charset` ger 415. `charset` jämförs utan hänsyn till skiftläge, citerat `"utf-8"` godtas. Svar har `application/json` utan `charset`. |
| Felformat för kroppsfel | `MethodArgumentNotValidException`/`HttpMessageNotReadableException` med Boots egna fält | Problem-svar med `errors`: `[{"path": "$.items[3].qty", "message": "..."}]` i dokumentordning, högst 100, och en detalj med antalet. Samma format används för validering (uppdrag 09). |
| Förhandling av felsvar | `ProblemDetail` skrivs som `application/problem+json` eller `application/json` (eller XML med Jackson XML) efter `Accept`; Boots `/error` ger HTML till webbläsare | Problem-svar förhandlas inte: alltid `application/problem+json`, även med `Accept: text/html`. |
| `produces`/`consumes` | En del av routingen: fel `Content-Type` eller `Accept` ger 415/406 innan handlern väljs, och två metoder kan dela sökväg med olika `produces` | Inte en del av routingen. Handlern förhandlar själv (`readJson` ger 415, `Responses.json` och `negotiate` ger 406), och en sökväg har en handler per metod. |
| Komprimerade kroppar | Beror på servern | `Content-Encoding` annat än `identity` ger 415 från `readJson`. |
