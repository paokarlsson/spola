package spola.client;

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

    // the app's logger
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

    // the API adds members over time: read the ones the app uses
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

    // Felhantering som appen behöver: status och en del av kroppen, aldrig nyckeln.
    Optional<MessagesResponse> askOrLog(MessagesRequest request) {
        try {
            return Optional.of(anthropic.post("/v1/messages").json(request, MessagesRequest.CODEC)
                    .retrieve(MessagesResponse.CODEC));
        } catch (RestClientResponseException e) { // a timeout or a refused connection goes on up
            log.warn("Anthropic svarade " + e.status() + ": " + e.bodyExcerpt());
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
