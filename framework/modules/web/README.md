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
`AcceptHeader`) och innehållsförhandling (`request.negotiate(...)`) kom i 08b. Cookies in
(`request.cookie(...)`), cookies ut (`ResponseCookie`, `Responses.withCookie`) och
formulärkroppar (`request.readForm()`, `FormData`) kom i 08c. Validering med
`spola.validation` (`request.readJson(codec, validator)`) och formulärbindning till records
(`FormBinder`, `request.readForm(binder, validator)`, `request.bindQuery(binder, validator)`)
med fel per fält i `errors` kom i 09b. Sessioner i minnet med en sessionscookie (`Sessions`,
`Session`, `SessionOptions`) kom i 11a. Klientens schema och adress bakom en omvänd proxy, ur
`X-Forwarded-Proto` och `X-Forwarded-For` (`ForwardedHeaders`, `TrustedProxies`,
`request.scheme()`, `request.clientAddress()`), kom i 13a. `Secure` på sessionscookien när
förfrågans schema är `https`, också bakom en proxy, kom i 13b.

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
| `@CookieValue String session` | `request.cookie("session")` (`Optional<String>`) |
| `@CookieValue int count` | `request.cookie("count", Param.INT)` |
| `ResponseCookie.from(name, value)...build()` + `HttpHeaders.SET_COOKIE` | `Responses.withCookie(response, ResponseCookie.of(name, value)...build())` |
| `@RequestParam` för ett formulärfält, `@ModelAttribute` | `request.readForm()` och `form.required("age", Param.INT)` |
| `server.tomcat.max-http-form-post-size` (2 MB) | `RouterBuilder.maxFormSize(int)` (2 MiB), `readForm(maxBytes)` |
| `@Valid @RequestBody CreateOrder order` (Jakarta-annoteringar) | `request.readJson(CREATE_ORDER, CREATE_ORDER_RULES)` med en `Validator<CreateOrder>` |
| `@Valid @ModelAttribute SignupForm form` + `BindingResult` | `request.readForm(SIGNUP, SIGNUP_RULES)` med en `FormBinder<Signup>` |
| `@ModelAttribute` på en `GET` (query till objekt) | `request.bindQuery(SEARCH)` eller `bindQuery(SEARCH, SEARCH_RULES)` |
| `MethodArgumentNotValidException` → 400 | `WebException.invalid(...)`: 400 med `errors` (`path`, `message`, `code`) |
| `FieldError.getCode()` | `Problem.FieldError.code()` (`Optional<String>`) |
| `@ExceptionHandler(ConstraintViolationException.class)` | `onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))` |
| `HttpSession` som parameter, `request.getSession()` | `Sessions.getOrCreate(request)` (`Session`) |
| `request.getSession(false)` | `Sessions.find(request)` (`Optional<Session>`) |
| `session.getAttribute("cart")` med cast | `session.get(CART)` med `AttributeKey<Cart>` (`Optional<Cart>`) |
| `session.setAttribute("cart", cart)`, `removeAttribute` | `session.put(CART, cart)`, `session.remove(CART)` |
| `request.changeSessionId()` | `Sessions.renew(request)` |
| `session.invalidate()` | `Sessions.invalidate(request)` |
| `server.servlet.session.timeout=30m` | `SessionOptions.DEFAULT.withIdleTimeout(Duration.ofMinutes(30))` |
| `server.servlet.session.cookie.secure=true` | `withCookie(cookie -> cookie.secure(true))` |
| `server.servlet.session.cookie.name` | `withCookieName("__Host-SESSION")` |
| Tomcats `maxActiveSessions` | `withMaxSessions(10_000)` (standard 10 000) |
| `sessionManagement(s -> s.maximumSessions(10))` (Spring Security) | `Sessions.renewFor(request, name, 10)` vid inloggningen; `WebSecurity.login` gör det med `maximumSessions` |
| `server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`) | `.filter(ForwardedHeaders.trusting(TrustedProxies.privateNetworks()))`, registrerat först |
| `request.getScheme()`, `request.isSecure()` | `request.scheme()`, `request.scheme().equals("https")` |
| `request.getRemoteAddr()` | `request.clientAddress()` (`InetAddress`); anslutningens adress är `request.http().remoteAddress()` |
| `server.tomcat.remoteip.internal-proxies` | `TrustedProxies.of("172.18.0.5")` eller `TrustedProxies.privateNetworks()` |

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
400. Eftersom JSON alltid är UTF-8 (RFC 8259 §8.1) godtar `charset=utf-8` i ett intervall en
JSON-typ utan `charset`: `Accept: application/json; charset=utf-8` ger 200 från
`Responses.json`, medan `charset=iso-8859-1` ger 406 (om inte till exempel `*/*` också finns).
`request.accept()` ger det tolkade huvudet (`AcceptHeader`) och
`request.contentType()` förfrågans `MediaType`.

## Formulär och cookies

### Spring Boot

```java
record Profile(String name, int age, List<String> interests) {
}

@RestController
class ProfileController {
    private final Map<String, Profile> profiles = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @PostMapping(path = "/profile", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<String> save(@RequestParam String name, @RequestParam int age,
            @RequestParam(name = "interest", required = false) List<String> interests) {
        var profile = new Profile(name, age, interests == null ? List.of() : interests);
        var id = "p" + ids.incrementAndGet();
        profiles.put(id, profile);
        var cookie = ResponseCookie.from("profile", id).path("/").httpOnly(true).sameSite("Lax").build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body("Saved " + name + ".");
    }

    @GetMapping("/profile")
    String show(@CookieValue(name = "profile", required = false) String id) {
        var profile = id == null ? null : profiles.get(id);
        if (profile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No profile.");
        }
        return profile.name() + ", " + profile.age() + ", " + profile.interests();
    }

    @DeleteMapping("/profile")
    ResponseEntity<Void> forget(@CookieValue(name = "profile", required = false) String id) {
        if (id != null) {
            profiles.remove(id);
        }
        var cookie = ResponseCookie.from("profile", "").path("/").maxAge(0).httpOnly(true).sameSite("Lax").build();
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }
}
```

### Spola

```java
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
```

Exemplet ovan är `modules/web/test/spola/web/ProfileApp.java` (utan paketraden), och testerna
kör det genom en riktig server, också med `HttpClient` och en `CookieManager`.

- `POST /profile` med `name=Ada+Lovelace&age=36&interest=math&interest=po%C3%A9sie` och
  `Content-Type: application/x-www-form-urlencoded` ger 200 och
  `Set-Cookie: profile=p1; Path=/; HttpOnly; SameSite=Lax` (en sessionscookie). `GET /profile`
  med `Cookie: profile=p1` ger `Ada Lovelace, 36, [math, poésie]`.
- `readForm` kontrollerar i samma ordning som `readJson`: `Content-Encoding` som inte är
  `identity` ger 415; ett saknat, felaktigt eller annat `Content-Type` än
  `application/x-www-form-urlencoded`, eller en `charset` som inte är UTF-8, ger 415 med
  `Accept: application/x-www-form-urlencoded`; en kropp över `maxFormSize` (standard 2 MiB)
  ger 413, och läsningen avbryts vid gränsen; en byte över 0x7F, ogiltig procentkodning,
  ogiltig UTF-8 eller fler än 1 000 par ger 400 utan text ur kroppen. Kroppen tolkas med samma
  regler som queryn (`+` är mellanslag, strikt UTF-8).
- `form.required("age", Param.INT)` ger 400 `Missing form field "age".` eller
  `Invalid form field "age": expected int, got "x".`; `form.get(...)` ger `Optional`,
  `form.all(...)` alla värden i ordning och `form.asMap()` hela formuläret.
- `request.cookie("profile")` ger det första värdet, `cookieAll` alla (webbläsare skickar
  samma namn flera gånger för olika `Path`), `cookie(name, Param.INT)` ett typat värde (fel
  ger 400 med namnet) och `cookies()` alla cookies. Cookies tolkas högst en gång per
  förfrågan.
- `ResponseCookie.of(name, value)` har standardvärdena `Path=/`, `HttpOnly` och
  `SameSite=Lax`; `Secure`, `domain` och `maxAge` anges uttryckligen. `build()` kontrollerar
  namn, värde, sökväg, domän, `SameSite=None` (kräver `Secure`) och prefixen `__Secure-`
  och `__Host-`, och ger `IllegalArgumentException` vid fel. `ResponseCookie.expire(name)`
  tar bort en cookie (`Max-Age=0`). `Responses.withCookie` lägger till ett eget
  `Set-Cookie`-fält per cookie.

## Sessioner

Sessioner i minnet, motsvarigheten till servlet-API:ts `HttpSession` med Spring Boots
`server.servlet.session.*`: en session per webbläsare, identifierad av ett slumpat id i en
cookie, med typade attribut, utgång efter inaktivitet, en övre gräns för antalet sessioner, nytt
id (`request.changeSessionId()`), en gräns per användare vid inloggning och ogiltigförklaring
(`session.invalidate()`). Koden är
säkerhetskritisk, eftersom sessions-id:t kan bära en inloggning, och bör granskas av en människa
innan den används på riktigt.

### Spring Boot

```properties
# application.properties
server.servlet.session.timeout=30m
server.servlet.session.cookie.secure=true
```

```java
record Cart(List<String> items) implements Serializable {
    static final Cart EMPTY = new Cart(List.of());

    Cart add(String item) {
        var more = new ArrayList<>(items);
        more.add(item);
        return new Cart(List.copyOf(more));
    }
}

@RestController
class CartController {

    @PostMapping(path = "/cart/items", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> add(@RequestParam String item, HttpSession session) { // creates the session
        var cart = (Cart) session.getAttribute("cart");
        session.setAttribute("cart", (cart == null ? Cart.EMPTY : cart).add(item));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cart")
    String show(HttpServletRequest request) {
        var session = request.getSession(false);
        var cart = session == null ? null : (Cart) session.getAttribute("cart");
        var customer = session == null ? null : (String) session.getAttribute("customer");
        return (customer == null ? "guest" : customer) + ": " + (cart == null ? List.of() : cart.items());
    }

    @PostMapping(path = "/sign-in", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> signIn(@RequestParam String name, HttpServletRequest request) {
        request.getSession(); // changeSessionId needs a session
        request.changeSessionId();
        request.getSession().setAttribute("customer", name);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sign-out")
    ResponseEntity<Void> signOut(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}
```

### Spola

```java
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
```

Exemplet ovan är `modules/web/test/spola/web/CartApp.java` (utan paketraden), och testerna
kör det genom en riktig server med `HttpClient` och en `CookieManager`.

- **Filtret.** `sessions.filter()` registreras på routern, före filter som använder
  sessionen. Det läser cookien, slår upp sessionen och lägger förfrågans sessionstillstånd som
  ett attribut; på svaret lägger det till `Set-Cookie` när sessionen skapats, fått nytt id
  eller ogiltigförklarats under förfrågan, också på svar från mappade undantag. Filtret skapar
  aldrig en session själv: en väg som inte sparar något (`/actuator/health`) får ingen.
  Utan filtret ger `find`, `getOrCreate`, `renew` och `invalidate` `IllegalStateException`
  (500) med ett meddelande om att registrera `filter()`, och ett filter som når samma
  förfrågan två gånger (registrerat två gånger, eller två `Sessions` på samma router) ger 500.
- **Id:t.** 32 byte från en `SecureRandom` (en `new SecureRandom()` per `Sessions`), kodade som
  Base64url utan utfyllnad: 43 tecken ur `[A-Za-z0-9_-]`, 256 bitar. Bara servern skapar id; ett
  id som klienten hittat på blir aldrig en session. Lagringen slår upp på SHA-256 av id:t och
  sparar aldrig själva id:t, så uppslagningen läcker ingen tid om hur mycket av ett id som är
  rätt. `Session` har ingen `getId()`, och id:t syns aldrig i `toString()`, undantag, loggrader
  eller problem-svar.
- **Cookien in.** Alla värden med cookienamnet provas i ordning, och det första som pekar på en
  levande session används. Ett värde som inte är exakt 43 tecken ur `[A-Za-z0-9_-]` slås aldrig
  upp; ett felaktigt, okänt eller utgånget värde ger ingen session, inget 400 och inget
  `Set-Cookie`.
- **Cookien ut.** `SESSION=<id>; Path=/; HttpOnly; SameSite=Lax` (en sessionscookie) med de
  attribut som `withCookie` ger. Funktionen körs en gång, i `Sessions.inMemory`, så fel i
  inställningarna (`SameSite=None` utan `Secure`, `__Host-` med `Domain`) ger
  `IllegalArgumentException` vid uppstart. Borttagningen är
  `SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax` med samma `Path` och `Domain`.
  Ett svar får högst ett `Set-Cookie` för sessionen, med det slutliga utfallet.
- **`Cache-Control`.** Ett svar som sätter eller tar bort sessionscookien får aldrig kunna sparas av
  en delad cache (en proxy eller ett CDN), eftersom nästa besökare då skulle få id:t. Saknar svaret
  `Cache-Control` får det `Cache-Control: no-store`. Handlerns värde behålls bara när det redan
  håller delade cachar borta: `no-store` eller `private` (utan fältlista), och varken `public`
  eller `s-maxage`. Allt annat, till exempel `public, max-age=60`, `s-maxage=60`, `max-age=60`,
  `no-cache`, `private="Set-Cookie"` eller ett värde som inte går att tolka, ersätts med ett enda
  `Cache-Control: no-store`. Direktivens namn jämförs utan hänsyn till skiftläge, och alla
  `Cache-Control`-fält räknas. Andra fält (`Expires`, `CDN-Cache-Control`, `Surrogate-Control`)
  ändras inte; sätt inga sådana på svar som kan bära sessionscookien.
- **`Secure`**, som Tomcat sätter det på `JSESSIONID`: cookien och borttagningen får `Secure` när
  förfrågans schema (`request.scheme()`) är `https`, eller när `withCookie` säger det
  (`cookie.secure(true)` ger `Secure` på varje svar, också över `http`). Inget annat i raden
  ändras: `SESSION=<id>; Path=/; Secure; HttpOnly; SameSite=Lax`. Schemat är det som förfrågan
  har när den når `sessions.filter()`, så `renew` och `invalidate` i handlern ändrar det inte.
  Över TLS är det `https`. Bakom en TLS-proxy är det `https` bara när `ForwardedHeaders` är
  registrerat före `sessions.filter()` (se [Bakom en omvänd proxy](#bakom-en-omvänd-proxy));
  registrerat efter får cookien inget `Secure`. Över `http://localhost` får cookien inget
  `Secure`, så utvecklingen fungerar. Regeln går inte att stänga av. En borttagning över `http`
  av en cookie som sattes över `https` får inget `Secure`, och webbläsaren kan då ignorera den.
- **Livscykeln.** `find` skapar aldrig en session. `getOrCreate` ger den levande sessionen eller
  skapar en. `renew` ger en levande session ett nytt id atomärt (det gamla slutar gälla direkt,
  attributen finns kvar) eller skapar en ny; använd den när sessionen får högre behörighet, till
  exempel vid inloggning (skydd mot session fixation). `renewFor` är `renew` vid en inloggning
  (se "Sessioner per användare" nedan). `invalidate` tar bort sessionen och cookien. Ett
  `Session`-objekt för en session som ogiltigförklarats eller gått ut ger tomt från `get`, och
  `put` ger `IllegalStateException`.
- **Utgång och städning.** En session går ut när `idleTimeout` (standard 30 minuter) har gått
  sedan den senast användes, och varje förfrågan med cookien räknas som användning. En utgången
  session tas bort när den slås upp, och filtret städar bort alla utgångna högst en gång per
  minut. Ingen bakgrundstråd, ingen `close()`.
- **Gränsen.** Högst `maxSessions` (standard 10 000). När gränsen är nådd städas först, av en
  förfrågan åt gången: en förfrågan som kommer medan en annan städar får 503 direkt, utan att gå
  igenom lagringen själv. Är gränsen nådd även efter städningen ger `getOrCreate` 503
  (`application/problem+json`, utan `Set-Cookie`). Befintliga sessioner tas aldrig bort för att
  ge plats åt anonyma; bara en användares egna inloggningar avslutar användarens sessioner (se
  nästa punkt).
- **Sessioner per användare.** `Sessions.renewFor(request, name, n)` är `renew` vid en
  inloggning, motsvarigheten till Spring Securitys `sessionManagement(s -> s.maximumSessions(n))`
  med Spring Sessions index på användarnamn. `WebSecurity.login` i `spola.security.web` anropar
  den med identitetens namn och `maximumSessions` (standard 10), så en app med `WebSecurity`
  skriver den inte själv.
  - Hör förfrågans session till **en annan användare** (en delad dator utan utloggning)
    ogiltigförklaras den, och inloggningen får en ny, tom session: den nya användaren ser aldrig
    den förras attribut, och det gamla id:t slutar gälla. En session utan användare eller med
    samma användare förnyas och behåller sina attribut.
  - Skulle användaren få fler än `n` sessioner avslutas användarens **minst nyligen använda**,
    aldrig den nya inloggningen (en användning är en förfrågan som hittar sessionen, att den
    skapas och att den förnyas). Ett konto som loggar in om och om igen utan cookie kan därför
    inte fylla `maxSessions` och stänga ute alla andra. Det sker innan den nya sessionen skapas,
    så en användare vid sin gräns kan logga in också när `maxSessions` är nådd, och igen när
    svaret byggs, så att gränsen håller också när samma användare loggar in på flera ställen
    samtidigt; sessionen räknas till användaren först då.
  - Namn jämförs exakt. Indexet från namn till sessioner håller bara levande sessioner: en
    session lämnar det när den ogiltigförklaras, går ut, städas bort eller avslutas av gränsen,
    och en användare utan sessioner finns inte i det.
- **I produktion** rekommenderas cookienamnet `__Host-SESSION` med `secure(true)`: webbläsaren
  behåller då cookien bara för den här värden, över HTTPS och med `Path=/`, så att en underdomän
  inte kan sätta eller skugga den.

```java
Sessions sessions = Sessions.inMemory(SessionOptions.DEFAULT
        .withCookieName("__Host-SESSION")
        .withCookie(cookie -> cookie.secure(true)));
```

## Bakom en omvänd proxy

En app bakom en omvänd proxy som avslutar TLS (Caddy, nginx) får förfrågningarna över vanlig HTTP
från proxyn. Då är `request.http().scheme()` alltid `http` och `request.http().remoteAddress()`
proxyns adress. Filtret `ForwardedHeaders` läser proxyns `X-Forwarded-Proto` och
`X-Forwarded-For`, men bara när den direkta avsändaren är en betrodd proxy, och ger alla filter och
handlers efter sig klientens schema och adress i `request.scheme()` och `request.clientAddress()`.
Det motsvarar Spring Boots `server.forward-headers-strategy=native` (Tomcats `RemoteIpValve`), där
`request.getScheme()`, `isSecure()` och `getRemoteAddr()` sedan ger klientens värden. Huvudena är
data utifrån, så koden är säkerhetskritisk och bör granskas av en människa innan den används på
riktigt.

### Spring Boot

```properties
# application.properties
server.forward-headers-strategy=native
```

```java
@RestController
class WhoamiController {

    @GetMapping("/api/whoami")
    String whoami(HttpServletRequest request) {
        return request.getScheme() + " " + request.getRemoteAddr(); // "https 203.0.113.7" behind Caddy
    }
}
```

### Spola

```java
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.web.ForwardedHeaders;
import spola.web.Router;
import spola.web.TrustedProxies;

final class WhoamiApp {

    static Router router(TrustedProxies proxies) {
        var router = Router.builder()
                .filter(ForwardedHeaders.trusting(proxies))  // first: every filter after it sees the client
                .get("/api/whoami", request -> Response.text(200,
                        request.scheme() + " " + request.clientAddress().getHostAddress()))
                .build();
        // Behind Caddy:          "https 203.0.113.7"
        // Directly, no proxy:    "http 127.0.0.1"
        // From outside with forged headers to a server that does not trust the sender: ignored
        return router;
    }

    public static void main(String[] args) throws Exception {
        // Caddy and the app on Docker's network; only Caddy's port is published
        var router = router(TrustedProxies.privateNetworks());  // 10/8, 172.16/12, 192.168/16, loopback, fc00::/7
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), router);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
```

- **Registrera `ForwardedHeaders` först**, ytterst på routern. Då ser alla filter efter det (till
  exempel `sessions.filter()` och `security.filter()`), gruppernas filter och handlers klientens
  schema och adress, också på förfrågningar som ett filter skickar vidare med `withAttribute` och
  på routerns egna 400, 404 och 405. Ett filter som registreras före det ser anslutningens värden.
- **`Secure` på sessions- och CSRF-cookien.** Med `ForwardedHeaders` före `sessions.filter()`
  (eller `security.filter()`) är schemat `https` när proxyn skickar `X-Forwarded-Proto: https`,
  och cookierna får `Secure` utan `secure(true)`. Registreras `ForwardedHeaders` efter ser
  handlern `https`, men cookierna får inget `Secure`. Egna cookies med `Responses.withCookie` får
  inget automatiskt `Secure`; appen väljer själv efter `request.scheme()`.
- **Bara från en betrodd avsändare.** Filtret frågar `TrustedProxies` om den direkta avsändaren,
  `request.http().remoteAddress()`. Är den inte betrodd går förfrågan vidare oförändrad och
  huvudena läses inte alls: den som når appen direkt kan varken ändra värdena eller få 400 med
  dem.
- **`X-Forwarded-For` läses från höger.** Alla fält läses i ordning som en lista. Från höger hoppas
  adresser som `TrustedProxies` litar på över, och den första adress den inte litar på är klienten.
  Det som står till vänster om klienten läses aldrig, så det som klienten själv skickat före sin
  adress spelar ingen roll. Litar `TrustedProxies` på alla adresser är klienten den vänstraste, och
  utan element är klienten avsändaren.
- **`X-Forwarded-Proto`** ska vara exakt ett fält med `http` eller `https`, oavsett skiftläge.
  Utan fältet behålls anslutningens schema.
- **400 för ogiltiga värden från en betrodd proxy**: två `X-Forwarded-Proto`-fält, ett annat
  värde än `http` eller `https` (till exempel `https, http` eller `wss`), och ett element i
  `X-Forwarded-For` som genomgången når och som inte är en IP-adress (till exempel `unknown`,
  `203.0.113.7:8080` eller `[2001:db8::1]`). Svaret är ett problem-svar vars `detail` namnger
  huvudet men aldrig värdet, och handlern anropas inte. Ett ogiltigt element nås bara när allt till
  höger om det kommer från betrodda proxyer. Att då falla tillbaka på avsändaren skulle göra
  klienten till proxyn, en intern adress som en regel för interna adresser släpper in.
- **Strikta IP-adresser.** IPv4 är fyra decimala delar utan inledande nollor, och IPv6 skrivs
  enligt RFC 4291 utan hakparenteser, zon eller port. Inga namn slås upp.
- **`request.http()` beskriver anslutningen.** `http().scheme()` och `http().remoteAddress()` är
  proxyns och ändras aldrig. Bara `ForwardedHeaders` kan ändra `scheme()` och `clientAddress()`,
  så ett värde som skiljer sig från anslutningens kommer alltid från en betrodd proxy.
- **Bara proxyns port ska publiceras.** Den som når appens egen port direkt från ett betrott nät
  kan sätta huvudena till vad som helst.
- **`privateNetworks()` litar på hela nätet**, inte bara på proxyn: `10.0.0.0/8`,
  `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `::1` och `fc00::/7`. Med Dockers
  userland-proxy, och i Docker Desktop, kan en direkt anslutning till en publicerad port komma från
  bryggans gateway (till exempel `172.17.0.1`). Den adressen är betrodd och kan förfalska huvudena.
  En klient på ett betrott nät som går genom proxyn hoppas dessutom över som om den vore en
  proxy, så det den själv skriver till vänster om sin adress blir klientadressen.
  Lita då bara på proxyns adress med `TrustedProxies.of(...)`, och ge proxyn en fast adress på
  nätet (i Docker Compose med `ipv4_address`):

```java
Filter forwarded = ForwardedHeaders.trusting(TrustedProxies.of("172.18.0.5")); // only Caddy
```

## Validering av JSON och formulär

`spola.web` kräver `spola.validation` transitivt, så en handler kan validera det som kommer in
och få ett giltigt värde tillbaka, eller låta ramverket svara 400 med ett fel per fält. Det
finns inget `BindingResult`: en handler får aldrig ett ogiltigt värde.

### JSON: `@Valid @RequestBody`

#### Spring Boot

```java
public record OrderLine(
        @NotBlank @Pattern(regexp = "[A-Z]{3}-[0-9]{4}") String sku,
        @Min(1) @Max(99) int quantity) {
}

public record CreateOrder(
        @NotBlank @Size(max = 100) String customer,
        @NotBlank @Email String email,
        @Size(min = 1, max = 50) List<@Valid OrderLine> lines) {
}

@RestController
class OrderController {
    private final Map<Long, CreateOrder> orders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @PostMapping("/orders") // MethodArgumentNotValidException (400) om ordern är ogiltig
    ResponseEntity<CreateOrder> create(@Valid @RequestBody CreateOrder order) {
        long id = ids.incrementAndGet();
        orders.put(id, order);
        return ResponseEntity.created(URI.create("/orders/" + id)).body(order);
    }
}
```

#### Spola

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.list;
import static spola.validation.Constraints.email;
import static spola.validation.Constraints.matches;
import static spola.validation.Constraints.maxLength;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.range;
import static spola.validation.Constraints.size;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.json.codec.JsonCodec;
import spola.validation.Validator;
import spola.web.Responses;
import spola.web.Router;
import spola.web.WebRequest;

final class OrderApi {

    record OrderLine(String sku, int quantity) {
    }

    record CreateOrder(String customer, String email, List<OrderLine> lines) {
    }

    static final JsonCodec<OrderLine> ORDER_LINE = JsonCodec.object(OrderLine::new,
            field("sku", STRING, OrderLine::sku),
            field("quantity", INT, OrderLine::quantity));

    static final JsonCodec<CreateOrder> CREATE_ORDER = JsonCodec.object(CreateOrder::new,
            field("customer", STRING, CreateOrder::customer),
            field("email", STRING, CreateOrder::email),
            field("lines", list(ORDER_LINE), CreateOrder::lines));

    static final Validator<OrderLine> ORDER_LINE_RULES = Validator.<OrderLine>builder()
            .field("sku", OrderLine::sku, notBlank(), matches(Pattern.compile("[A-Z]{3}-[0-9]{4}")))
            .field("quantity", OrderLine::quantity, range(1, 99))
            .build();

    static final Validator<CreateOrder> CREATE_ORDER_RULES = Validator.<CreateOrder>builder()
            .field("customer", CreateOrder::customer, notBlank(), maxLength(100))
            .field("email", CreateOrder::email, notBlank(), email())
            .field("lines", CreateOrder::lines, size(1, 50))
            .eachNested("lines", CreateOrder::lines, ORDER_LINE_RULES)
            .build();

    private final Map<Long, CreateOrder> orders = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    Response create(WebRequest request) throws IOException {
        CreateOrder order = request.readJson(CREATE_ORDER, CREATE_ORDER_RULES); // valid, or 400
        long id = ids.incrementAndGet();
        orders.put(id, order);
        return Responses.created(request, "/orders/" + id, CREATE_ORDER, order);
    }

    Router router() {
        return Router.builder()
                .post("/orders", this::create)
                .build();
    }

    public static void main(String[] args) throws Exception {
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), new OrderApi().router());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
```

Exemplet ovan är `modules/web/test/spola/web/OrderApi.java` (utan paketraden), och testerna
kör det genom en riktig server med `HttpClient`.

- Kontrollerna i `readJson(codec)` görs först och oförändrade: 415, 400 för en tom kropp, 413,
  400 för ogiltig JSON och 400 med `errors` för ett mappningsfel (`Request body has N
  problems.`). Validatorn anropas bara för ett värde som gick att läsa, tolka och mappa.
- En ogiltig order ger 400 med detaljen `The request has 5 validation errors.` och en
  `errors`-lista i valideringens ordning, med `code` för varje fel:

  ```json
  {"type":"about:blank","title":"Bad Request","status":400,"detail":"The request has 2 validation errors.",
   "instance":"/orders","errors":[{"path":"$.customer","message":"must not be blank","code":"notBlank"},
   {"path":"$.lines[0].quantity","message":"must be between 1 and 99","code":"range"}]}
  ```

  Högst 100 fel listas; med fler läggs ` The first 100 are listed.` till detaljen. Ett
  mappningsfel har ingen `code`, så svaren från 08b är oförändrade.
- `readJson(codec, limits, validator)` använder egna `JsonLimits`. Ett undantag från
  validatorn släpps igenom oförändrat till routerns mappers (eller 500).

### Formulär: `@Valid @ModelAttribute` och `BindingResult`

#### Spring Boot

```java
public class SignupForm {
    @NotBlank @Email private String email;
    @Size(min = 12, max = 128) private String password;
    @Min(13) @Max(150) private Integer age;
    @Size(max = 5) private List<String> interests = new ArrayList<>();
    @AssertTrue(message = "must be accepted") private boolean terms;
    // getters och setters för alla fält (Spring binder via JavaBeans-egenskaper)
}

@Controller
class SignupController {
    private final Map<String, SignupForm> users = new ConcurrentHashMap<>();

    @PostMapping(path = "/signup", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<String> signup(@Valid @ModelAttribute SignupForm form, BindingResult result) {
        if (result.hasErrors()) { // fel vid typomvandling och validering i samma lista
            return ResponseEntity.badRequest().body(result.getFieldErrors().toString());
        }
        users.put(form.getEmail(), form);
        return ResponseEntity.status(HttpStatus.CREATED).body("Welcome, " + form.getEmail() + ".");
    }
}
```

#### Spola

```java
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
```

Exemplet ovan är `modules/web/test/spola/web/SignupApp.java` (utan paketraden), och testerna
kör det genom en riktig server med `HttpClient`.

- `FormBinder<T>` är ett funktionellt gränssnitt (`bind(FormFields)`). `FormFields` läser
  fälten och **samlar alla fel** i stället för att kasta vid det första:
  `required(name, param)` (saknas: felet `required`, `is required`), `optional(name, param)`,
  `all(name, param)` (fel per element på `$.namn[i]`) och `flag(name)` för kryssrutor (`on`,
  `true`, `1` ger `true`; `false`, `0`, ett saknat eller tomt fält ger `false`).
- Ett värde som inte går att omvandla ger felet `type` med meddelandet `expected int` (typens
  namn), aldrig värdet. Fler än ett värde för `required`, `optional` eller `flag` ger felet
  `single` (`must have a single value`). Ett tomt värde (`age=`) räknas som saknat för alla
  `Param` utom `Param.STRING`, där `""` är ett värde som valideringen får bedöma. Okända fält
  (CSRF-token, knappar) ignoreras.
- Bindningsfel ger 400 med detaljen `Request body has N problems.` och `errors` i den ordning
  bindern läser fälten; validatorn anropas då inte. Valideringsfel ger samma 400 som för JSON
  (`The request has N validation errors.`). `readForm(binder)` läser kroppen exakt som
  `readForm()`: samma 415, 413, 400 och läs-en-gång-regel.
- Om bindern kastar efter att fel har samlats (oftast en record-konstruktor som får `null`)
  blir svaret 400 med de insamlade felen. Utan insamlade fel släpps undantaget igenom
  oförändrat.
- `request.bindQuery(binder, validator)` binder queryn på samma sätt (detaljen `Query has N
  problems.`) och rör aldrig kroppen. `binder.bind(formData)` binder utan en förfrågan, till
  exempel i ett test.

### `ValidationException` från egen kod

`Validator.require` i en tjänst kastar `ValidationException`. Routern gör inte om den till 400
av sig själv: ett ogiltigt värde som tjänsten själv har byggt är en bugg och ger 500, som
Springs `ConstraintViolationException`. Där undantaget betyder att klienten skickat fel
registreras mappern uttryckligen, på en rad:

```java
Router.builder()
        .onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))
```

`WebException.invalid(e)` ger exakt samma 400 som `readJson(codec, validator)` för samma
överträdelser.

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
| Gammalt `HttpURLConnection`-standardhuvud | `text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2` godtas (Spring tolkar `*` som `*/*`) | Ger 400: ett ensamt `*` är ingen mediatyp i RFC 9110, och `Accept` tolkas strikt. Huvudet skickas bara av `HttpURLConnection` före JDK 23 när anroparen inte sätter `Accept`; sätt det uttryckligen. |
| `charset` i `Accept` | `application/json;charset=UTF-8` godtar `application/json` | Samma för JSON: `charset=utf-8` (oavsett skiftläge, även citerat) godtar en JSON-typ utan `charset`. En annan `charset` utesluter. För andra typer krävs samma `charset` i den erbjudna typen. |
| `charset` i `Content-Type` | Jackson läser UTF-16/UTF-32 och flera andra kodningar | Bara UTF-8 (RFC 8259 §8.1): en annan `charset` ger 415. `charset` jämförs utan hänsyn till skiftläge, citerat `"utf-8"` godtas. Svar har `application/json` utan `charset`. |
| Felformat för kroppsfel | `MethodArgumentNotValidException`/`HttpMessageNotReadableException` med Boots egna fält | Problem-svar med `errors`: `[{"path": "$.items[3].qty", "message": "..."}]` i dokumentordning, högst 100, och en detalj med antalet. Valideringsfel och bindningsfel har samma format med `code` sist (`"code": "notBlank"`). |
| Förhandling av felsvar | `ProblemDetail` skrivs som `application/problem+json` eller `application/json` (eller XML med Jackson XML) efter `Accept`; Boots `/error` ger HTML till webbläsare | Problem-svar förhandlas inte: alltid `application/problem+json`, även med `Accept: text/html`. |
| `produces`/`consumes` | En del av routingen: fel `Content-Type` eller `Accept` ger 415/406 innan handlern väljs, och två metoder kan dela sökväg med olika `produces` | Inte en del av routingen. Handlern förhandlar själv (`readJson` ger 415, `Responses.json` och `negotiate` ger 406), och en sökväg har en handler per metod. |
| Komprimerade kroppar | Beror på servern | `Content-Encoding` annat än `identity` ger 415 från `readJson`. |
| Standardvärden för `ResponseCookie` | Springs `ResponseCookie` har inga: utan `path` skrivs ingen `Path`, och `HttpOnly`/`SameSite` saknas tills de anges | `Path=/`, `HttpOnly` och `SameSite=Lax` om inget annat anges. `Secure` anges uttryckligen (så att `http://localhost` fungerar). Prefixen `__Secure-`/`__Host-` och `SameSite=None` utan `Secure` ger `IllegalArgumentException` redan i `build()`. |
| `Expires` | Spring skriver både `Max-Age` och `Expires` när `maxAge` är satt | Bara `Max-Age` (alla webbläsare i bruk förstår det, och det beror inte på serverns klocka). `maxAge` måste vara hela sekunder. |
| Felaktiga cookies | Tomcats `Rfc6265CookieProcessor` hoppar över dem | Samma: ett par utan `=`, ett namn som inte är en token, ett värde med mellanslag, komma, `\`, `"` mitt i, styrtecken eller icke-ASCII hoppas över tyst, utan 400. Fler än 200 giltiga cookies ger 400. |
| Citattecken i cookievärden | Beror på servlet-containern och dess inställningar (äldre cookieprocessorer tog bort dem) | Citattecknen behålls som en del av värdet (`"abc"`), och inget avkodas: värdet är opakt. |
| Formulär och query | `@RequestParam` läser både queryn och formuläret som "request parameters" | Slås inte ihop: handlern väljer uttryckligen `request.query(...)` eller `request.readForm()`. |
| Tom formulärkropp | Inga parametrar | Samma: ett tomt `FormData` (ett HTML-formulär utan fält är giltigt). Det skiljer sig från `readJson`, där en tom kropp ger 400. |
| Fel `Content-Type` på formulär | `@RequestParam` hittar inga formulärfält och ger 400 för ett obligatoriskt fält; `consumes` ger 415 | `readForm` ger 415 med `Accept: application/x-www-form-urlencoded` för ett saknat eller annat `Content-Type`, en annan `charset` än UTF-8 och `Content-Encoding`. |
| Inget `BindingResult` | `@Valid` med ett `BindingResult` ger handlern ett ogiltigt objekt och felen; utan det kastas `MethodArgumentNotValidException` | Inget `BindingResult`: handlern får aldrig ett ogiltigt värde. `readJson`/`readForm`/`bindQuery` med en validator ger värdet eller 400 med `errors`. |
| Mappningsfel före valideringsfel | `HttpMessageNotReadableException` kommer före valideringen; för `@ModelAttribute` hamnar typfel och valideringsfel i samma `BindingResult` | Mappnings- och bindningsfel besvaras först och validatorn anropas inte. Valideringsfel kommer bara för ett värde som gick att mappa eller binda, så de två listorna blandas aldrig. |
| Värden i felsvar | `FieldError.getRejectedValue()` bär det avvisade värdet, och Boots standardsvar kan visa det | Värden ekas aldrig: inte i `errors`, inte i detaljen och inte i loggen. Ett typfel säger `expected int`, inte vad som skickades (fältet kan vara ett lösenord). |
| Tomma formulärfält | `age=` blir `null` för en `Integer` (tom text omvandlas till `null`, med `StringTrimmerEditor` även blanktecken), men ett bindningsfel för en `int` | Ett tomt värde räknas som saknat för alla `Param` utom `STRING`: `optional` ger tomt, `required` ger felet `required`. För `STRING` är `""` ett värde (`notBlank()` fångar det). |
| Flera värden för ett fält | `a=1&a=2` till en `String` blir `"1,2"`, till en `Integer` det första värdet | Fler än ett värde för `required`, `optional` och `flag` ger felet `single`. Bara `all` tar emot flera värden. |
| `ValidationException` från egen kod | `ConstraintViolationException` från en `@Validated`-tjänst ger 500 utan en egen `@ExceptionHandler` | Samma: 500 utan mapper. 400 kräver en uttrycklig rad, `onException(ValidationException.class, (e, r) -> WebException.invalid(e).toResponse(r))`. |
| Sessionens id | `HttpSession.getId()` ger id:t | Ingen `getId()`: id:t är en hemlighet som bara cookien bär, och applikationen behöver det inte. `Session.toString()` visar bara antalet attribut. |
| Gräns för antalet sessioner | Ingen gräns som standard (Tomcats `maxActiveSessions` är -1). Med en gräns kastar Tomcat `TooManyActiveSessionsException` när en ny session ska skapas | Högst `maxSessions` (standard 10 000). Vid gränsen ger `getOrCreate` 503 (problem-svar utan `Set-Cookie`) i stället för att ta bort sessioner för att ge plats: befintliga sessioner tas aldrig bort, så den som kan skapa sessioner kan inte logga ut andra. Bara en användares egna inloggningar avslutar användarens äldsta sessioner (`renewFor`). |
| Sessioner per användare | Spring Securitys `maximumSessions(n)` med en `SessionRegistry` markerar den minst nyligen använda sessionen som utgången, och `ConcurrentSessionFilter` avslutar den vid nästa förfrågan; utan `maximumSessions` finns ingen gräns. `changeSessionId()` behåller attributen också när en annan användare loggar in | `Sessions.renewFor(request, name, n)` avslutar den minst nyligen använda sessionen direkt; nästa förfrågan med dess cookie är anonym. `WebSecurity.login` har gränsen 10 som standard. En session som hör till en annan användare ger en ny, tom session i stället för att förnyas. Ingen `maxSessionsPreventsLogin(true)`, inget `SessionRegistry`-API för att lista eller avsluta en användares sessioner. |
| Cookienamn | `JSESSIONID` (Tomcat) | `SESSION`, som Spring Session, i stället för `JSESSIONID`. I produktion rekommenderas `__Host-SESSION` med `secure(true)`. |
| `Secure` på sessionscookien | Tomcat sätter `Secure` på `JSESSIONID` när `request.isSecure()`, och `server.servlet.session.cookie.secure=true` tvingar det | Samma regel: `Secure` när `request.scheme()` är `https`, och `withCookie(cookie -> cookie.secure(true))` tvingar det, också över `http`. Bakom en proxy krävs att `ForwardedHeaders` registreras före `sessions.filter()` (se raden om `Secure` bakom en proxy nedan). Som i Tomcat går regeln inte att stänga av. |
| Persistens och flera servrar | Tomcat kan spara sessioner mellan omstarter (`server.servlet.session.persistent`), och Spring Session delar dem mellan servrar med JDBC eller Redis | Ingen persistens och ingen delning: sessionerna finns bara i minnet, försvinner vid omstart och delas inte mellan servrar. Attributen sparas som referenser (ingen serialisering, inget krav på `Serializable`). |
| Städning av utgångna sessioner | Tomcat städar i en bakgrundstråd | Ingen bakgrundstråd: en utgången session tas bort när den slås upp, filtret städar alla utgångna högst en gång per minut, och alltid innan en ny session nekas vid gränsen. En städning åt gången: en förfrågan som nekas medan en annan städar får 503 utan att gå igenom lagringen. `Sessions` behöver ingen `close()`. |
| `Cache-Control` för sessionscookien | Inget särskilt utan Spring Security (som sätter `no-cache, no-store, max-age=0, must-revalidate` på alla svar som saknar `Cache-Control`, och annars behåller handlerns värde) | Ett svar som sätter eller tar bort sessionscookien får `Cache-Control: no-store`, så att en delad cache aldrig sparar ett svar med ett sessions-id. Handlerns värde behålls bara om det redan har `no-store` eller `private` och varken `public` eller `s-maxage`; `public, max-age=60`, `s-maxage=60` och `max-age=60` ersätts. |
| Gamla id:t efter `changeSessionId` | En förfrågan som redan har `HttpSession`-objektet fortsätter att använda det | `renew` gäller direkt också för förfrågningar som redan hanteras: en förfrågan som kom med det gamla id:t ser ingen session längre (`get` ger tomt, `put` kastar). |
| Proxyhuvuden som läses | Med `native` läser Tomcat `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host` och `X-Forwarded-Port`; med `framework` läser `ForwardedHeaderFilter` också `Forwarded` (RFC 7239) och `X-Forwarded-Prefix` | Bara `X-Forwarded-For` och `X-Forwarded-Proto`. `Forwarded` (RFC 7239), `X-Forwarded-Host`, `X-Forwarded-Port` och `X-Forwarded-Prefix` stöds inte: `request.http().host()` är det `Host` som proxyn skickar, och porten och absoluta URL:er ändras inte. |
| Ogiltiga värden från en betrodd proxy | `RemoteIpValve` avvisar aldrig förfrågan: elementen i `X-Forwarded-For` jämförs som text med reguljära uttryck, och ett `X-Forwarded-Proto` som inte är `https` ger `http` | 400 (problem-svar som namnger huvudet men inte värdet), aldrig 500: ett element i `X-Forwarded-For` som genomgången når och som inte är en IP-adress, fler än ett `X-Forwarded-Proto` och ett annat värde än `http` eller `https`. Element till vänster om klienten läses inte, och en obetrodd avsändare kan aldrig få 400 med huvudena. |
| IP-adresser i `X-Forwarded-For` | Text som matchas mot `internal-proxies` (reguljära uttryck) | Strikta IP-literaler: IPv4 som fyra decimala delar utan inledande nollor (`127.1`, `2130706433`, `1.2.3` och `010.0.0.1` avvisas), IPv6 enligt RFC 4291 utan hakparenteser, zon eller port. Adressen skapas med `InetAddress.ofLiteral` först efter kontrollen, och inga namn slås upp. `::ffff:203.0.113.7` blir IPv4-adressen. |
| Betrodda proxyer som standard | Tomcats standard för `internal-proxies` omfattar fler nät, bland annat de länklokala `169.254.0.0/16` och `100.64.0.0/10` | `TrustedProxies.privateNetworks()`: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `127.0.0.0/8`, `::1` och `fc00::/7`, inga länklokala adresser och inte `100.64.0.0/10`. Inget standardval: `ForwardedHeaders.trusting(...)` tar alltid `TrustedProxies` uttryckligen. |
| `Secure` på cookies efter schemat bakom en proxy | Tomcats `RemoteIpValve` körs före alla filter, så `request.isSecure()` är klientens schema för sessionscookien och för Spring Securitys cookies | `ForwardedHeaders` är ett filter och måste registreras före `sessions.filter()` och `security.filter()`. Då får sessions- och CSRF-cookien `Secure` efter klientens schema. Registreras det efter ser handlern `https`, men cookierna får inget `Secure`. Cookies som appen sätter själv med `Responses.withCookie` får inget automatiskt `Secure`. |
