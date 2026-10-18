# HTTP-klient för JSON-API:er (motsvarighet till RestClient)

Projekt: psykologen (paokarlsson/psykologen), backend i Spring Boot som ska kunna gå över till Spola
Spola-version: 0.1.0

## Behov
Psykologen anropar två externa JSON-API:er: Anthropics Messages API och OpenAIs Chat
Completions. I dag används Anthropics Java-SDK (som drar in OkHttp, Jackson och Kotlin) och
för OpenAI handskriven kod på `java.net.http` med Gson. Med Spola ska båda bli korta anrop med
samma klient: skicka en JSON-kropp med några huvuden, få tillbaka ett typat svar via en
`JsonCodec`, och få ett tydligt fel med status när API:t svarar med något annat än 2xx. Själva
klienterna för Anthropic och OpenAI stannar i appen; det som behövs i Spola är den allmänna
HTTP-klienten.

## Motsvarighet i Spring eller Lombok
Spring Framework: `RestClient` (`RestClient.builder().baseUrl(...).defaultHeader(...)`,
`post().uri(...).body(...).retrieve().body(Typ.class)`, `onStatus(...)`,
`RestClientResponseException` med status, huvuden och kropp).

## Användningsfall
```java
// Namnen är förslag, planeraren bestämmer.
var anthropic = RestClient.builder()
        .baseUri(URI.create("https://api.anthropic.com"))
        .defaultHeader("x-api-key", apiKey)
        .defaultHeader("anthropic-version", "2023-06-01")
        .timeout(Duration.ofSeconds(60))
        .build();

MessagesResponse response = anthropic.post("/v1/messages")
        .json(request, MessagesRequest.CODEC)          // Content-Type: application/json
        .retrieve(MessagesResponse.CODEC);              // 2xx: avkodat svar, annars RestClientException

// Felhantering som appen behöver: status och en del av kroppen, aldrig nyckeln.
try {
    anthropic.post("/v1/messages").json(request, MessagesRequest.CODEC).retrieve(MessagesResponse.CODEC);
} catch (RestClientException e) {
    log.warn("Anthropic svarade " + e.status() + ": " + e.bodyExcerpt());
}

// Samma klient för OpenAI, med ett Bearer-huvud och ett svar som bara läses som träd.
var openai = RestClient.builder()
        .baseUri(URI.create("https://api.openai.com"))
        .defaultHeader("Authorization", "Bearer " + openAiKey)
        .build();
JsonValue body = openai.post("/v1/chat/completions").json(chatRequest).retrieveJson();
```

Acceptans för appen: anropen ovan fungerar mot en lokal `spola.http`-server i ett test som
spelar Anthropic (kontrollerar huvuden och kropp och svarar med JSON, 4xx och 5xx), och
`HttpServer` plus klienten räcker för att skriva integrationstester mot appens egen router
(motsvarigheten till `@SpringBootTest(webEnvironment = RANDOM_PORT)` med en `RestClient`).

## Krav som inte syns i koden (valfritt)
- Bygger på `java.net.http.HttpClient` och `spola.json`, inga andra beroenden.
- Tidsgräns för anslutning och för hela svaret, med säkra standardvärden.
- Svarskroppen har en storleksgräns (svaret är data utifrån). En kropp som inte är JSON, eller
  JSON som inte passar codecen, ger ett tydligt fel med status, inte ett `NullPointerException`.
- Felmeddelanden och `toString` visar aldrig värden för huvuden som `Authorization` och
  `x-api-key`.
- Virtuella trådar och blockerande anrop, ingen reaktiv stil. Klienten är trådsäker och kan
  delas.

## Lokal lösning i appen (valfritt)
`backend/src/main/java/se/olaslab/psykologen/service/ai/OpenAiClient.java` (java.net.http och
Gson) och `AnthropicChatClient.java` (Anthropics SDK).
