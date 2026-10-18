/**
 * Spola's REST client for JSON APIs, the counterpart of Spring's {@code RestClient}, on top of
 * {@link java.net.http.HttpClient} and {@code spola.json}.
 *
 * <pre>{@code
 * var anthropic = RestClient.builder()
 *         .baseUri(URI.create("https://api.anthropic.com"))
 *         .defaultHeader("x-api-key", apiKey)
 *         .defaultHeader("anthropic-version", "2023-06-01")
 *         .timeout(Duration.ofSeconds(60))
 *         .build();
 *
 * MessagesResponse response = anthropic.post("/v1/messages")
 *         .json(request, MessagesRequest.CODEC)
 *         .retrieve(MessagesResponse.CODEC);
 * }</pre>
 *
 * <ul>
 *   <li>{@link spola.client.RestClient}: a shareable, thread-safe client with a base URI,
 *       default headers, a total timeout per call, a connect timeout and a limit on the response
 *       body; {@code get}, {@code post}, {@code put}, {@code patch} and {@code delete} give a
 *       {@link spola.client.RestRequest}.</li>
 *   <li>{@link spola.client.RestRequest}: an immutable request with headers and a JSON body,
 *       sent by {@code retrieve(codec)}, {@code retrieveJson()} or
 *       {@code retrieveBodiless()}.</li>
 *   <li>{@link spola.client.RestClientException}: every failure of a call, sealed with two
 *       cases: {@link spola.client.RestClientResponseException} (a response came but cannot be
 *       used: a status outside 2xx, a body over the limit, a body that is not JSON or does not
 *       fit the codec) and {@link spola.client.RestClientTransportException} (no usable response
 *       came: connection, TLS, protocol, timeout, interrupt).</li>
 * </ul>
 *
 * <p>The client speaks HTTP/1.1, never follows redirects, uses no proxy and no cookies, and
 * reads every response body as JSON in UTF-8 whatever its {@code Content-Type}. The module never
 * writes a header value, the query or the request body into a message, {@code toString()} or
 * cause, and a failure's message hides them where the server sends them back (see
 * {@link spola.client.RestClientException}).
 *
 * <p>At run time the module needs {@code java.net.http} and {@code spola.json} only; it uses no
 * reflection and logs nothing. {@code requires static spola.web} is for the module's tests only
 * (their servers and routers); no main code uses {@code spola.web}, {@code spola.http} or
 * {@code spola.validation}, so the client does not depend on the server.
 */
module spola.client {
    requires transitive java.net.http;
    requires transitive spola.json;
    requires static spola.web;

    exports spola.client;
}
