package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import spola.client.AiClients.Message;
import spola.client.AiClients.MessagesRequest;
import spola.client.AiClients.MessagesResponse;
import spola.client.AiClients.TextBlock;
import spola.http.Request;
import spola.http.Response;
import spola.json.Json;
import spola.json.JsonArray;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;
import spola.test.Suite;

/** Criteria 3 and 4: the need's code (AiClients) against servers that play Anthropic and OpenAI. */
final class AnthropicTests {

    private AnthropicTests() {
    }

    static final String KEY = "sk-ant-KEYMARKER-0123456789";
    static final String OPENAI_KEY = "sk-proj-OAIMARKER-9876543210";

    static final MessagesRequest REQUEST = new MessagesRequest("claude-sonnet-4-5", 1024,
            List.of(new Message("user", "Hej, hur mår du?")));

    static final String OK_BODY = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"Jag mår bra, tack."}],
             "stop_reason":"end_turn","usage":{"input_tokens":12,"output_tokens":8}}""";

    static final String ERROR_BODY = """
            {"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: Field required"}}""";

    /**
     * A server that plays Anthropic's Messages API: it checks the method, the path, the headers
     * and the body, and answers with {@code status} and {@code body}. Every check that fails is
     * recorded in {@code problems} and answered with 418.
     */
    record Anthropic(spola.http.HttpServer server, List<String> problems, AtomicInteger calls) implements AutoCloseable {

        static Anthropic start(int status, String body) throws Exception {
            var problems = new CopyOnWriteArrayList<String>();
            var calls = new AtomicInteger();
            var server = Support.serve(request -> {
                calls.incrementAndGet();
                var found = check(request);
                problems.addAll(found);
                if (!found.isEmpty()) {
                    return Response.text(418, String.join("; ", found));
                }
                return Response.bytes(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
            });
            return new Anthropic(server, problems, calls);
        }

        static List<String> check(Request request) throws Exception {
            var problems = new ArrayList<String>();
            expect(problems, "method", "POST", request.method());
            expect(problems, "path", "/v1/messages", request.target());
            expect(problems, "x-api-key", Optional.of(KEY), request.headers().first("x-api-key"));
            expect(problems, "anthropic-version", Optional.of("2023-06-01"), request.headers().first("anthropic-version"));
            expect(problems, "Content-Type", List.of("application/json"), request.headers().all("Content-Type"));
            expect(problems, "Accept", List.of("application/json"), request.headers().all("Accept"));
            var body = Json.parse(request.bytes());
            expect(problems, "body", MessagesRequest.CODEC.encode(REQUEST), body);
            return problems;
        }

        static void expect(List<String> problems, String what, Object expected, Object actual) {
            if (!expected.equals(actual)) {
                problems.add(what + ": expected " + expected + " but was " + actual);
            }
        }

        AiClients clients(List<String> log) {
            return new AiClients(Support.base(server.port()), KEY, AiClients.OPENAI, OPENAI_KEY, log::add);
        }

        @Override
        public void close() {
            server.close();
        }
    }

    static Suite tests() {
        return suite("the need's code against a server that plays Anthropic and OpenAI",
                test("200 with JSON gives the decoded MessagesResponse; the server saw every header and the body",
                        () -> {
                            try (var anthropic = Anthropic.start(200, OK_BODY);
                                    var clients = anthropic.clients(new ArrayList<>())) {
                                var response = clients.ask(REQUEST);
                                assertEquals(List.of(), anthropic.problems());
                                assertEquals(new MessagesResponse("msg_01",
                                        List.of(new TextBlock("text", "Jag mår bra, tack.")), "end_turn"), response);
                                assertEquals(1, anthropic.calls().get());
                            }
                        }),
                test("400 with Anthropic's error format gives RestClientResponseException 400 with the type and text",
                        () -> {
                            try (var anthropic = Anthropic.start(400, ERROR_BODY);
                                    var clients = anthropic.clients(new ArrayList<>())) {
                                var e = assertThrows(RestClientResponseException.class, () -> clients.ask(REQUEST));
                                assertEquals(List.of(), anthropic.problems());
                                assertEquals(400, e.status());
                                assertTrue(e.bodyExcerpt().contains("invalid_request_error"), e.bodyExcerpt());
                                assertTrue(e.bodyExcerpt().contains("max_tokens: Field required"), e.bodyExcerpt());
                                assertEquals(ERROR_BODY, e.bodyExcerpt());
                                assertEquals("POST", e.method());
                                assertEquals(Optional.of("application/json"), e.headers().firstValue("Content-Type"));
                            }
                        }),
                test("the need's log line has the status and the excerpt but not the key", () -> {
                    var log = new ArrayList<String>();
                    try (var anthropic = Anthropic.start(400, ERROR_BODY); var clients = anthropic.clients(log)) {
                        assertEquals(Optional.empty(), clients.askOrLog(REQUEST));
                        assertEquals(List.of("Anthropic svarade 400: " + ERROR_BODY), log);
                        assertFalse(log.getFirst().contains("KEYMARKER"));
                        assertFalse(log.getFirst().contains(KEY));
                    }
                }),
                test("askOrLog returns the response for 200 and logs nothing", () -> {
                    var log = new ArrayList<String>();
                    try (var anthropic = Anthropic.start(200, OK_BODY); var clients = anthropic.clients(log)) {
                        assertEquals("msg_01", clients.askOrLog(REQUEST).orElseThrow().id());
                        assertEquals(List.of(), log);
                    }
                }),
                test("500 and 529 give RestClientResponseException with that status", () -> {
                    for (int status : new int[] {500, 529}) {
                        var body = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";
                        try (var anthropic = Anthropic.start(status, body);
                                var clients = anthropic.clients(new ArrayList<>())) {
                            var e = assertThrows(RestClientResponseException.class, () -> clients.ask(REQUEST));
                            assertEquals(List.of(), anthropic.problems());
                            assertEquals(status, e.status());
                            assertTrue(e.getMessage().contains("status " + status), e.getMessage());
                            assertFalse(e.getMessage().contains("KEYMARKER"), e.getMessage());
                        }
                    }
                }),
                test("timeout(Duration.ofSeconds(60)) from the need is taken", () -> {
                    try (var clients = new AiClients(AiClients.ANTHROPIC, KEY, AiClients.OPENAI, OPENAI_KEY, m -> { })) {
                        assertTrue(clients.toString().contains("AiClients"));
                    }
                    try (var client = RestClient.builder().baseUri(AiClients.ANTHROPIC)
                            .timeout(java.time.Duration.ofSeconds(60)).build()) {
                        assertTrue(client.toString().contains("timeout 60000 ms"), client.toString());
                    }
                }),
                test("OpenAI: defaultHeader Authorization Bearer, json(JsonValue) and retrieveJson give the server's tree",
                        () -> {
                            var chatRequest = JsonObject.builder()
                                    .put("model", new JsonString("gpt-4o-mini"))
                                    .put("messages", JsonArray.of(JsonObject.of("role", new JsonString("user"),
                                            "content", new JsonString("Hej"))))
                                    .build();
                            var answer = Json.parse("""
                                    {"id":"chatcmpl-1","object":"chat.completion","choices":[{"index":0,
                                     "message":{"role":"assistant","content":"Hej! Vad kan jag hjälpa till med?"},
                                     "finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":9}}""");
                            var problems = new CopyOnWriteArrayList<String>();
                            try (var server = Support.serve(request -> {
                                Anthropic.expect(problems, "method", "POST", request.method());
                                Anthropic.expect(problems, "path", "/v1/chat/completions", request.target());
                                Anthropic.expect(problems, "Authorization", List.of("Bearer " + OPENAI_KEY),
                                        request.headers().all("Authorization"));
                                Anthropic.expect(problems, "Content-Type", List.of("application/json"),
                                        request.headers().all("Content-Type"));
                                Anthropic.expect(problems, "body", chatRequest, Json.parse(request.bytes()));
                                return Response.bytes(200, "application/json",
                                        Json.write(answer).getBytes(StandardCharsets.UTF_8));
                            }); var clients = new AiClients(AiClients.ANTHROPIC, KEY, Support.base(server.port()),
                                    OPENAI_KEY, m -> { })) {
                                JsonValue body = clients.chat(chatRequest);
                                assertEquals(List.of(), problems);
                                assertEquals(answer, body);
                            }
                        }));
    }
}
