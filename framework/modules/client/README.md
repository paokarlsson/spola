# spola.client

En REST-klient för JSON-API:er i Spola, motsvarigheten till Springs `RestClient`. Den bygger på
`java.net.http.HttpClient` och `spola.json`: en JSON-kropp in via en `JsonCodec`, ett typat svar
ut, och ett typat fel med status när API:t svarar med något annat än 2xx. Svaret är data utifrån,
så klienten har en tidsgräns för hela anropet och en storleksgräns för kroppen. Klienten skriver
aldrig värdena i huvudena (API-nycklar) i fel eller `toString`, och felmeddelandet och
`safeExcerpt()` döljer hela värden, och nyckeln efter schemat i `Authorization`, när servern
skickar tillbaka dem. Samma klient och en `HttpServer` räcker
för integrationstester mot appens egen router, som `@SpringBootTest(webEnvironment = RANDOM_PORT)`
med en `RestClient`.

Modulen har `requires transitive java.net.http` och `requires transitive spola.json`. Vid körning
behövs inget annat, så klienten kan användas fristående och beror inte på servern.
`requires static spola.web` finns bara för modulens tester (servrarna och routern i
integrationstesterna). Ingen reflektion, inga annoteringar och ingen loggning.

| Spring (`RestClient`) | Spola |
|---|---|
| `RestClient.builder().baseUrl("https://api.anthropic.com")` | `RestClient.builder().baseUri(URI.create("https://api.anthropic.com"))` |
| `.defaultHeader("x-api-key", apiKey)` | `.defaultHeader("x-api-key", apiKey)` |
| `requestFactory(...)` med `setConnectTimeout` och `setReadTimeout` | `.connectTimeout(Duration)` (10 s) och `.timeout(Duration)` för hela anropet (30 s) |
| Ingen gräns för svarets storlek | `.maxResponseSize(int)` (4 MiB) |
| `client.post().uri("/v1/messages")` | `client.post("/v1/messages")`, också `get`, `put`, `patch` och `delete` |
| `.header("Idempotency-Key", key)` | `.header("Idempotency-Key", key)` |
| `.contentType(APPLICATION_JSON).body(request)` (Jackson) | `.json(request, MessagesRequest.CODEC)` med en `JsonCodec` |
| `.body(jsonNode)` | `.json(jsonValue)` |
| `.retrieve().body(MessagesResponse.class)` | `.retrieve(MessagesResponse.CODEC)` |
| `.retrieve().body(JsonNode.class)` | `.retrieveJson()` (`JsonValue`) |
| `.retrieve().toBodilessEntity()` | `.retrieveBodiless()` (statusen som `int`) |
| `RestClientException` | `RestClientException` (`sealed`, två fall) |
| `RestClientResponseException`, `HttpClientErrorException`, `HttpServerErrorException`, `UnknownContentTypeException` | `RestClientResponseException` |
| `ResourceAccessException` | `RestClientTransportException` (med `timedOut()`) |
| `e.getStatusCode().value()` | `e.status()` |
| `e.getResponseHeaders()` | `e.headers()` (`java.net.http.HttpHeaders`) |
| `e.getResponseBodyAsString()` | `e.bodyExcerpt()` (högst 1 000 tecken, utan radbrytningar och styrtecken, rått); `e.safeExcerpt()` är samma utdrag med förfrågans värden dolda, för loggen |
| `@SpringBootTest(webEnvironment = RANDOM_PORT)` och `@LocalServerPort` | `HttpServer.start(HttpServerOptions.DEFAULT.withPort(0), router)` och `server.port()` |
| Ingen livscykel | `client.close()` (`AutoCloseable`) |

## Anropa Anthropic och OpenAI

Behovet från appen psykologen: Anthropics Messages API och OpenAIs Chat Completions med var sin
klient. Klienterna för de två API:erna stannar i appen; Spola ger den allmänna klienten.

### Spring Boot

```java
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Service
class AiClients {

    private static final Logger log = LoggerFactory.getLogger(AiClients.class);

    record Message(String role, String content) {
    }

    record MessagesRequest(String model, @JsonProperty("max_tokens") int maxTokens, List<Message> messages) {
    }

    record TextBlock(String type, String text) {
    }

    // Spring Boots ObjectMapper ignorerar okända medlemmar
    record MessagesResponse(String id, List<TextBlock> content, @JsonProperty("stop_reason") String stopReason) {
    }

    private final RestClient anthropic;
    private final RestClient openai;

    AiClients(@Value("${anthropic.api-key}") String apiKey, @Value("${openai.api-key}") String openAiKey) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.anthropic = RestClient.builder()
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .requestFactory(factory)
                .build();
        this.openai = RestClient.builder()
                .baseUrl("https://api.openai.com")
                .defaultHeader("Authorization", "Bearer " + openAiKey)
                .build();
    }

    MessagesResponse ask(MessagesRequest request) {
        return anthropic.post()
                .uri("/v1/messages")
                .contentType(APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MessagesResponse.class);
    }

    Optional<MessagesResponse> askOrLog(MessagesRequest request) {
        try {
            return Optional.ofNullable(ask(request));
        } catch (RestClientResponseException e) {
            log.warn("Anthropic svarade " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString());
            return Optional.empty();
        }
    }

    JsonNode chat(JsonNode chatRequest) {
        return openai.post()
                .uri("/v1/chat/completions")
                .contentType(APPLICATION_JSON)
                .body(chatRequest)
                .retrieve()
                .body(JsonNode.class);
    }
}
```

### Spola

`AiClients.java` är en kompilerad testkälla. Ett test kör den mot en `HttpServer` som spelar
Anthropic (den kontrollerar metoden, sökvägen, `x-api-key`, `anthropic-version`, `Content-Type`,
`Accept` och kroppen och svarar med JSON, 400, 500 och 529) och mot en som spelar OpenAI. I appen
skapas den med `new AiClients(AiClients.ANTHROPIC, apiKey, AiClients.OPENAI, openAiKey, log::warn)`.

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.json.codec.JsonCodecs.list;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import spola.json.JsonValue;
import spola.json.codec.JsonCodec;

final class AiClients implements AutoCloseable {

    static final URI ANTHROPIC = URI.create("https://api.anthropic.com");
    static final URI OPENAI = URI.create("https://api.openai.com");

    // appens logger
    interface Log {
        void warn(String message);
    }

    record Message(String role, String content) {
        static final JsonCodec<Message> CODEC = JsonCodec.object(Message::new,
                field("role", STRING, Message::role),
                field("content", STRING, Message::content));
    }

    record MessagesRequest(String model, int maxTokens, List<Message> messages) {
        static final JsonCodec<MessagesRequest> CODEC = JsonCodec.object(MessagesRequest::new,
                field("model", STRING, MessagesRequest::model),
                field("max_tokens", INT, MessagesRequest::maxTokens),
                field("messages", list(Message.CODEC), MessagesRequest::messages));
    }

    record TextBlock(String type, String text) {
        static final JsonCodec<TextBlock> CODEC = JsonCodec.object(TextBlock::new,
                field("type", STRING, TextBlock::type),
                field("text", STRING, TextBlock::text));
    }

    // API:t lägger till medlemmar med tiden: läs de som appen använder
    record MessagesResponse(String id, List<TextBlock> content, String stopReason) {
        static final JsonCodec<MessagesResponse> CODEC = JsonCodec.object(MessagesResponse::new,
                field("id", STRING, MessagesResponse::id),
                field("content", list(TextBlock.CODEC), MessagesResponse::content),
                field("stop_reason", STRING, MessagesResponse::stopReason)).ignoringUnknown();
    }

    private final RestClient anthropic;
    private final RestClient openai;
    private final Log log;

    AiClients(URI anthropicUri, String apiKey, URI openAiUri, String openAiKey, Log log) {
        this.anthropic = RestClient.builder()
                .baseUri(anthropicUri)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(60))
                .build();
        // Samma klient för OpenAI, med ett Bearer-huvud och ett svar som bara läses som träd.
        this.openai = RestClient.builder()
                .baseUri(openAiUri)
                .defaultHeader("Authorization", "Bearer " + openAiKey)
                .build();
        this.log = log;
    }

    MessagesResponse ask(MessagesRequest request) {
        return anthropic.post("/v1/messages")
                .json(request, MessagesRequest.CODEC)          // Content-Type: application/json
                .retrieve(MessagesResponse.CODEC);              // 2xx: avkodat svar, annars RestClientException
    }

    // Felhantering som appen behöver: status och en del av kroppen. safeExcerpt() döljer värden
    // från förfrågan som servern skickar tillbaka hela (nyckeln, kroppen); bodyExcerpt() är rått.
    Optional<MessagesResponse> askOrLog(MessagesRequest request) {
        try {
            return Optional.of(anthropic.post("/v1/messages").json(request, MessagesRequest.CODEC)
                    .retrieve(MessagesResponse.CODEC));
        } catch (RestClientResponseException e) { // en tidsgräns eller nekad anslutning går vidare
            log.warn("Anthropic svarade " + e.status() + ": " + e.safeExcerpt());
            return Optional.empty();
        }
    }

    JsonValue chat(JsonValue chatRequest) {
        return openai.post("/v1/chat/completions").json(chatRequest).retrieveJson();
    }

    @Override
    public void close() {
        anthropic.close();
        openai.close();
    }
}
```

- **Avvikelse från behovet:** behovet fångar `RestClientException` och läser `status()` och
  `bodyExcerpt()`. En tidsgräns eller en nekad anslutning har ingen status, så de metoderna finns
  på `RestClientResponseException`, som `getStatusCode()` i Spring. `catch (RestClientException e)`
  fångar fortfarande alla fel, och en `switch` över den är uttömmande:

  ```java
  } catch (RestClientException e) {
      String what = switch (e) {
          case RestClientResponseException r -> "status " + r.status() + ": " + r.safeExcerpt();
          case RestClientTransportException t -> t.timedOut() ? "timed out" : "no response";
      };
  }
  ```

  Behovets `bodyExcerpt()` finns kvar men är rått, så exemplen läser `safeExcerpt()` (nästa
  punkt).
- **Loggraden** innehåller statusen och `safeExcerpt()`: utdraget där varje helt värde från
  förfrågan är `<hidden>`, som i `getMessage()`. Det är högst 1 000 tecken och har inga
  radbrytningar, styrtecken eller bidi-tecken (de skrivs `\uXXXX`), så en kropp från servern kan
  inte förfalska loggrader. Springs `getResponseBodyAsString()` ger hela kroppen som den är.
  `bodyExcerpt()` är samma utdrag men **rått**: det är serverns text och döljer ingenting, så en
  server som skickar tillbaka förfrågan (ett valideringsfel med indata, en ekotjänst, ett 401 som
  nämner nyckeln) får med den där. Logga `safeExcerpt()` eller `getMessage()`, inte
  `bodyExcerpt()`.
- **Hela undantaget i loggen** (`log.warn("...", e)`): klienten skriver aldrig ett huvudvärde i
  ett undantag, och när servern skickar tillbaka det döljer `getMessage()` varje hel förekomst av
  ett huvudvärde, queryn och förfrågans kropp (de blir `<hidden>`). För `Authorization` och
  `Proxy-Authorization` (namnet i vilket skiftläge som helst) döljs också nyckeln för sig: texten
  efter det första mellanslaget (efter schemat, `Bearer` eller `bearer`), och för schemat `Basic`
  det Base64-avkodade `user:lösenord` och lösenordet efter det första `:`. Andra huvuden döljs bara
  hela. Ett transportfel visar bara JDK:ns text när den inte kan komma från servern. En del av ett
  värde som servern skickar tillbaka för sig (en medlem i kroppen) eller ändrar först känns inte
  igen. Orsaken från `spola.json` (kroppen är inte JSON eller passar inte kodeken) döljer ingenting
  och kan citera svaret: upp till 40 tecken per värde i upp till 100 problem (och 200 tecken av
  ett undantagsmeddelande).
- **Slå aldrig på JDK:ns loggning av `HttpClient` i produktion.** Med
  `-Djdk.httpclient.HttpClient.log=headers`, `requests` eller `all` (ensam eller i en lista) eller
  med `-Djdk.internal.httpclient.debug=true` skriver `java.net.http` själv ut huvudvärden
  (API-nycklarna, med `headers` och `all`) och varje URI med sin query (med alla fyra) i loggen
  eller på standard error. Modulen kan inte stänga av det och kontrollerar det inte.
- `MessagesResponse.CODEC` har `ignoringUnknown()`, eftersom API:t lägger till medlemmar. Spolas
  kodekar avvisar annars okända medlemmar.

## Integrationstest mot en egen router

### Spring Boot

```java
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest(webEnvironment = RANDOM_PORT)
class ItemApiTests {

    @LocalServerPort
    int port;

    @Test
    void createShowDeleteThen404And400() {
        var client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();

        Item created = client.post().uri("/items").contentType(APPLICATION_JSON)
                .body(new Item(0, "tea", 2)).retrieve().body(Item.class);
        assertEquals(new Item(1, "tea", 2), created);
        assertEquals(created, client.get().uri("/items/1").retrieve().body(Item.class));

        assertEquals(204, client.delete().uri("/items/1").retrieve().toBodilessEntity().getStatusCode().value());

        var missing = assertThrows(HttpClientErrorException.NotFound.class,
                () -> client.get().uri("/items/1").retrieve().body(Item.class));
        assertTrue(missing.getResponseBodyAsString().contains("\"detail\":\"No item 1.\""));

        client.post().uri("/items").contentType(APPLICATION_JSON)
                .body(new Item(0, "milk", 1)).retrieve().body(Item.class);
        var invalid = assertThrows(HttpClientErrorException.BadRequest.class,
                () -> client.put().uri("/items/2").contentType(APPLICATION_JSON)
                        .body(new Item(0, "milk", 0)).retrieve().body(Item.class));
        assertTrue(invalid.getResponseBodyAsString().contains("quantity"));
    }
}
```

`ItemController` har `@GetMapping`, `@PostMapping`, `@PutMapping` med `@Valid @RequestBody` och
`@DeleteMapping`, som i [`spola.web`](../web/README.md).

### Spola

Appens router med fyra vägar (`ItemApp.java`): en `GET` som svarar med JSON, en `POST` som läser
JSON med `readJson` och svarar 201, en `PUT` som validerar och ger 400 med `errors`, och en
`DELETE` som svarar 204.

```java
import static spola.json.codec.JsonCodec.field;
import static spola.json.codec.JsonCodecs.INT;
import static spola.json.codec.JsonCodecs.LONG;
import static spola.json.codec.JsonCodecs.STRING;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.range;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import spola.http.Response;
import spola.json.codec.JsonCodec;
import spola.validation.Validator;
import spola.web.Param;
import spola.web.Responses;
import spola.web.Router;
import spola.web.WebException;
import spola.web.WebRequest;

// the app's router, as in its main code
final class ItemApp {

    record Item(long id, String name, int quantity) {
    }

    // the id is the server's: a client may leave it out
    static final JsonCodec<Item> ITEM = JsonCodec.object(Item::new,
            field("id", LONG, Item::id).orElse(0L),
            field("name", STRING, Item::name),
            field("quantity", INT, Item::quantity));

    static final Validator<Item> ITEM_RULES = Validator.<Item>builder()
            .field("name", Item::name, notBlank())
            .field("quantity", Item::quantity, range(1, 99))
            .build();

    private final Map<Long, Item> items = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    Response show(WebRequest request) {
        return Responses.json(request, 200, ITEM, find(request.pathParam("id", Param.LONG)));
    }

    Response create(WebRequest request) throws IOException {
        var input = request.readJson(ITEM);
        var item = new Item(ids.incrementAndGet(), input.name(), input.quantity());
        items.put(item.id(), item);
        return Responses.created(request, "/items/" + item.id(), ITEM, item);
    }

    Response update(WebRequest request) throws IOException {
        long id = request.pathParam("id", Param.LONG);
        find(id);
        var input = request.readJson(ITEM, ITEM_RULES); // valid, or 400 with errors
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
                .get("/items/{id}", this::show)
                .post("/items", this::create)
                .put("/items/{id}", this::update)
                .delete("/items/{id}", this::remove)
                .build();
    }
}
```

Testet (`ItemApiTests.java`) startar routern på en `HttpServer` med port 0 och anropar den med en
`RestClient`. Det körs i modulens tester precis som det står här.

```java
import static spola.client.ItemApp.ITEM;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.net.URI;
import spola.client.ItemApp.Item;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.test.Suite;

final class ItemApiTests {

    private ItemApiTests() {
    }

    static Suite tests() {
        return suite("item API through a real server",
                test("create, show, delete, then 404 and 400", () -> {
                    try (var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(0), new ItemApp().router());
                            var client = RestClient.builder()
                                    .baseUri(URI.create("http://127.0.0.1:" + server.port()))
                                    .build()) {
                        Item created = client.post("/items").json(new Item(0, "tea", 2), ITEM).retrieve(ITEM);
                        assertEquals(new Item(1, "tea", 2), created);
                        assertEquals(created, client.get("/items/1").retrieve(ITEM));

                        assertEquals(204, client.delete("/items/1").retrieveBodiless());

                        var missing = assertThrows(RestClientResponseException.class,
                                () -> client.get("/items/1").retrieve(ITEM));
                        assertEquals(404, missing.status());
                        assertTrue(missing.bodyExcerpt().contains("\"detail\":\"No item 1.\""), missing.bodyExcerpt());

                        client.post("/items").json(new Item(0, "milk", 1), ITEM).retrieve(ITEM);
                        var invalid = assertThrows(RestClientResponseException.class,
                                () -> client.put("/items/2").json(new Item(0, "milk", 0), ITEM).retrieve(ITEM));
                        assertEquals(400, invalid.status());
                        assertTrue(invalid.bodyExcerpt().contains("\"errors\":[{\"path\":\"$.quantity\""),
                                invalid.bodyExcerpt());
                    }
                }));
    }
}
```

- `HttpServer.start(...)` och `RestClient` i try-with-resources ersätter `@SpringBootTest` och
  `@LocalServerPort`: ingen kontext startas, servern lyssnar på en ledig port och stängs när
  testet är klart.
- `retrieveBodiless()` ger statusen (204). Ett 404 och ett 400 är `RestClientResponseException`
  med problem-svaret i `bodyExcerpt()`; Spring har en underklass per status
  (`HttpClientErrorException.NotFound`).

## Gränser och standardvärden

| Inställning | Standard | Vad den gäller |
|---|---|---|
| `timeout(Duration)` | 30 s | Hela anropet: anslutning, TLS, förfrågan, statusrad, huvuden och hela kroppen. Går den ut avbryts förfrågan, anslutningen stängs och `RestClientTransportException` med `timedOut()` kastas. |
| `connectTimeout(Duration)` | 10 s | Anslutningen, inom `timeout`. |
| `maxResponseSize(int)` | 4 MiB (`JsonLimits.DEFAULT.maxInputSize()`) | Kroppen i alla avslutande anrop. Ett `Content-Length` över gränsen ger fel utan att kroppen läses; en längre kropp slutar läsas vid gränsen, också chunked och utan slut. JSON tolkas med `JsonLimits.DEFAULT.withMaxInputSize(maxResponseSize)`. |
| Felsvar | 8 KiB | Bara de första 8 KiB av en kropp utanför 2xx läses till utdraget; sedan stängs svaret. |
| `bodyExcerpt()` | 1 000 tecken | De första tecknen av kroppen (UTF-8, ogiltiga byte blir U+FFFD), aldrig ett delat surrogatpar, med `...` efter en kortning, också när läsningen stannade vid en gräns (ett tecken som gränsen delade tas bort). Styrtecken, U+2028, U+2029, bidi-tecknen (U+061C, U+200E, U+200F, U+202A–U+202E, U+2066–U+2069) och ensamma surrogat skrivs `\uXXXX`. |
| `safeExcerpt()` | 1 000 tecken | Samma utdrag som `bodyExcerpt()`, men varje helt värde från förfrågan (huvudvärden, nyckeln efter schemat i `Authorization` och `Proxy-Authorization` och för `Basic` det avkodade `user:lösenord`, queryn, kroppen) är `<hidden>`. Utdraget att logga. |
| `getMessage()` | 1 000 tecken | En rad utskrivbar ASCII: metoden, URI:n utan query, vad som gick fel och för svarsfel statusen och början av `safeExcerpt()`, med samma värden dolda. |

`build()` samlar alla problem i ett `IllegalArgumentException` med en rad per problem: saknad
eller felaktig `baseUri` (också en vars sökväg har ett segment som är `.` eller `..`), felaktigt
eller förbjudet huvud, samma huvud två gånger, en tidsgräns som inte är positiv och
`maxResponseSize` under 1. `header(...)` och en felaktig sökväg ger `IllegalArgumentException`
direkt. En sökväg med ett segment som är `.` eller `..`, också procentkodat (`%2e`, `%2E`) eller
före ett `;` (`/..;/x`), är felaktig och ger en fast text som inte citerar sökvägen. Inget meddelande innehåller ett
huvudvärde eller sökvägens query.

## Avvikelser från Spring

| Område | Spring (`RestClient`) | Spola |
|---|---|---|
| Undantagshierarkin | `RestClientException` med bland annat `RestClientResponseException` (och en underklass per status, `HttpClientErrorException.NotFound`), `UnknownContentTypeException` och `ResourceAccessException`. | `RestClientException` är `sealed` med exakt två slutliga fall: `RestClientResponseException` (ett svar kom: `status()`, `headers()`, `safeExcerpt()`, `bodyExcerpt()`) och `RestClientTransportException` (inget användbart svar: `timedOut()`). Ingen underklass per status. En `switch` utan `default` är uttömmande. Konstruktorerna är inte publika. |
| `Content-Type` i svaret | Styr vilken konverterare som läser kroppen; `text/html` på ett 200 ger `UnknownContentTypeException`. | Styr inte tolkningen. Varje 2xx-kropp tolkas som JSON (strikt, UTF-8) oavsett `Content-Type`. HTML ger `RestClientResponseException` med `JsonParseException` som orsak; `text/plain` med giltig JSON ger värdet. |
| Omdirigeringar | Beror på fabriken: `HttpURLConnection` följer omdirigeringar för `GET`, och JDK:ns `HttpClient` gör det om den är inställd så. | Följs aldrig. En 3xx ger `RestClientResponseException` med statusen och `Location` i `headers()`, så huvudena med nyckeln skickas aldrig vidare till en annan värd. |
| HTTP-version | Beror på fabriken; JDK:ns `HttpClient` försöker med HTTP/2. | Bara HTTP/1.1, den version som `spola.http` talar. Anslutningar återanvänds (keep-alive). |
| Sökvägen | `uri("/items/{id}", id)` fyller i mallar och kodar värdena; `uri(...)` mot en bas-URL följer URI-reglerna. | Sökvägen läggs som text efter basens sökväg (`https://h/api` + `/v1/x` blir `https://h/api/v1/x`) och skickas som den står: inga mallar och ingen kodning. Värden utifrån måste kodas av appen. En sökväg som inte börjar med `/`, har ett fragment eller har tecken som inte får finnas i en URI ger `IllegalArgumentException`. Det gör också ett segment som är `.` eller `..` (också `%2e` eller `%2E`), i sökvägen och i bas-URI:n: servern löser upp det till en annan resurs, och kodning ändrar det inte (`URLEncoder.encode("..")` är `..`). Ett segment räknas fram till sitt första `;`, så `/..;/x` avvisas också (Tomcat och Jetty tar bort sökvägsparametrar innan de löser upp punktsegment). `/a..b`, `/...` och `/a.b` går bra. `//annan.example/x` går till basens värd. |
| Cookies | Kan slås på i fabriken (`CookieManager`). | Inga cookies och ingen session mellan anrop. |
| `SSLContext` och proxy | Ställs in i fabriken (`HttpClient.newBuilder().sslContext(...)`, `.proxy(...)`); JDK:ns `HttpClient` använder annars JVM:ens proxyinställningar. | Går inte att ange. TLS använder JDK:ns standard-`SSLContext` (truststore enligt `javax.net.ssl.*`, kontroll av värdnamnet). Ingen proxy används, inte heller den som `https.proxyHost` anger. |
| `Content-Type` i förfrågan | `.contentType(...)` med vilken typ som helst. | Kan inte sättas. `json(...)` sätter `Content-Type: application/json`, och `header("Content-Type", ...)` ger `IllegalArgumentException`. Samma gäller `Connection`, `Content-Length`, `Expect`, `Host`, `Keep-Alive`, `Proxy-Connection`, `TE`, `Trailer`, `Transfer-Encoding` och `Upgrade`. Bara JSON-kroppar. |
| Felkroppen | `getResponseBodyAsString()` ger hela kroppen som den är. | Högst de första 8 KiB läses, sedan stängs svaret. `bodyExcerpt()` är högst 1 000 tecken, utan styrtecken, och rått; `safeExcerpt()` är samma utdrag med förfrågans värden dolda. |
| Felmeddelandet | Meddelandet från `DefaultResponseErrorHandler` har statusen och kroppen som de är. | `getMessage()` har början av utdraget, men varje helt värde från förfrågan som servern skickar tillbaka är `<hidden>`, också nyckeln efter schemat i `Authorization`. Ett transportfel visar JDK:ns text bara för fel i anslutning, TLS och tidsgräns; annars bara klassnamnen, och orsaken är en kopia utan text. |
| Tidsgräns | `setReadTimeout` gäller varje läsning, så en server som skickar en byte i taget håller anropet hur länge som helst. | `timeout` är en deadline för hela anropet, också kroppen. Samma tidsgräns gäller alla anrop från en klient; ingen tidsgräns per förfrågan. |
| Storleksgräns | Ingen; hela kroppen läses in. | `maxResponseSize`, standard 4 MiB. |
| Tom kropp | `body(Type.class)` ger `null`. | `retrieve` och `retrieveJson` ger `RestClientResponseException` med `JsonParseException` som orsak. Använd `retrieveBodiless()` för 204. |
| Svarshuvuden | `toEntity(...)` och `exchange(...)` ger huvudena på lyckade anrop. | Svarshuvuden finns bara på `RestClientResponseException`. |
| Felhantering per status | `onStatus(...)` och `defaultStatusHandler(...)`. | Varje status utanför 2xx ger `RestClientResponseException`; fånga och titta på `status()`. |
| `Accept` | Byggs av konverterarna (`application/json, application/*+json`). | `Accept: application/json` när varken standardhuvudena eller förfrågan har ett `Accept`. |
| Kropp på `GET` och `DELETE` | `get()` och `delete()` har ingen `body(...)`. | `json(...)` går på alla metoder; en kropp på `GET` har ingen definierad betydelse och många servrar avvisar den. |
| Okända medlemmar i svaret | Spring Boots `ObjectMapper` ignorerar dem. | Kodeken avvisar dem om den inte har `ignoringUnknown()`. |
| Loggning, interceptorer, omförsök | `requestInterceptor(...)`, loggning i fabriken, Spring Retry. | Inget av det. Modulen loggar ingenting. JDK:ns egen loggning (`jdk.httpclient.HttpClient.log`, `jdk.internal.httpclient.debug`) skriver ut nycklar och query och ska aldrig slås på i produktion. |
