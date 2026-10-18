package spola.client;

import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.parameterized;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import spola.test.Suite;

/** Criterion 17 and the README's requirements: the examples are these test sources, and the tables. */
final class ReadmeTests {

    private ReadmeTests() {
    }

    static final Path README = Path.of("modules/client/README.md");

    static String readme() throws Exception {
        return Files.readString(README);
    }

    /** The section from its heading to the next {@code ## } heading. */
    static String section(String heading) throws Exception {
        var text = readme();
        int start = text.indexOf("\n## " + heading + "\n");
        assertTrue(start > 0, "the README has a section " + heading);
        int end = text.indexOf("\n## ", start + 1);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    /** The README block of a compiled test source: the file after its package line, in a java block. */
    static String block(String file) throws Exception {
        var source = Files.readString(Path.of("modules/client/test/spola/client/" + file));
        var header = "package spola.client;\n\n";
        assertTrue(source.startsWith(header), file);
        return "```java\n" + source.substring(header.length()) + "```\n";
    }

    /** A main source of the module. */
    static String mainSource(String name) throws Exception {
        return Files.readString(Path.of("modules/client/src/spola/client/" + name));
    }

    /** {@code text} with every line break, and the {@code *} that starts a Javadoc line, as one space. */
    static String flat(String text) {
        return text.replaceAll("[ \\t]*\\n[ \\t]*(\\*(?!/)[ \\t]*)?", " ");
    }

    static Suite tests() {
        return suite("README",
                test("the Anthropic and OpenAI example is AiClients.java as it is compiled and tested", () -> {
                    var section = section("Anropa Anthropic och OpenAI");
                    assertTrue(section.contains("### Spola\n"), "a Spola part");
                    var spola = section.substring(section.indexOf("### Spola\n"));
                    assertTrue(spola.contains(block("AiClients.java")), "the README's example differs from AiClients.java");
                }),
                test("the Spring code comes first and uses the builder, post().uri(...).contentType(APPLICATION_JSON)"
                        + ".body(...).retrieve().body(...) and RestClientResponseException", () -> {
                    var section = section("Anropa Anthropic och OpenAI");
                    int spring = section.indexOf("### Spring Boot\n");
                    int spola = section.indexOf("### Spola\n");
                    assertTrue(spring > 0 && spring < spola, "Spring before Spola");
                    var code = section.substring(spring, spola);
                    for (var text : List.of("RestClient.builder()", ".baseUrl(\"https://api.anthropic.com\")",
                            ".defaultHeader(\"x-api-key\", apiKey)", ".defaultHeader(\"anthropic-version\", \"2023-06-01\")",
                            ".defaultHeader(\"Authorization\", \"Bearer \" + openAiKey)", "anthropic.post()",
                            ".uri(\"/v1/messages\")", ".contentType(APPLICATION_JSON)", ".body(request)", ".retrieve()",
                            ".body(MessagesResponse.class)", "catch (RestClientResponseException e)",
                            "e.getStatusCode().value()", ".body(JsonNode.class)")) {
                        assertTrue(code.contains(text), "the Spring code lacks " + text);
                    }
                    assertTrue(section.contains("**Avvikelse från behovet:**"), "the deviation from the need");
                }),
                test("the router example is ItemApp.java and the integration test is ItemApiTests.java", () -> {
                    var section = section("Integrationstest mot en egen router");
                    int spring = section.indexOf("### Spring Boot\n");
                    int spola = section.indexOf("### Spola\n");
                    assertTrue(spring > 0 && spring < spola, "Spring before Spola");
                    var springCode = section.substring(spring, spola);
                    assertTrue(springCode.contains("@SpringBootTest(webEnvironment = RANDOM_PORT)"));
                    assertTrue(springCode.contains("@LocalServerPort"));
                    assertTrue(springCode.contains("RestClient.builder().baseUrl("));
                    var spolaPart = section.substring(spola);
                    assertTrue(spolaPart.contains(block("ItemApp.java")), "the README's router differs from ItemApp.java");
                    assertTrue(spolaPart.contains(block("ItemApiTests.java")),
                            "the README's test differs from ItemApiTests.java");
                }),
                test("the comparison table has the rows", () -> {
                    var text = readme();
                    for (var row : List.of(
                            "| `RestClient.builder().baseUrl(\"https://api.anthropic.com\")` | `RestClient.builder().baseUri(URI.create(\"https://api.anthropic.com\"))` |",
                            "| `.retrieve().body(MessagesResponse.class)` | `.retrieve(MessagesResponse.CODEC)` |",
                            "| `.retrieve().body(JsonNode.class)` | `.retrieveJson()` (`JsonValue`) |",
                            "| `.retrieve().toBodilessEntity()` | `.retrieveBodiless()` (statusen som `int`) |",
                            "| `ResourceAccessException` | `RestClientTransportException` (med `timedOut()`) |",
                            "| `e.getStatusCode().value()` | `e.status()` |",
                            "| `e.getResponseBodyAsString()` | `e.bodyExcerpt()`",
                            "| `@SpringBootTest(webEnvironment = RANDOM_PORT)` och `@LocalServerPort` |")) {
                        assertTrue(text.contains(row), "missing " + row);
                    }
                }),
                test("15d K2: the examples log safeExcerpt(), not the raw bodyExcerpt(), and \"aldrig nyckeln\" is gone",
                        () -> {
                            var text = readme();
                            assertFalse(text.contains("aldrig nyckeln"), "README still says \"aldrig nyckeln\"");
                            assertTrue(block("AiClients.java").contains(
                                    "log.warn(\"Anthropic svarade \" + e.status() + \": \" + e.safeExcerpt());"), "the log line");
                            var javadoc = mainSource("RestClientException.java") + mainSource("RestClientResponseException.java")
                                    + mainSource("RestClient.java");
                            for (var source : List.of(text, javadoc)) {
                                for (var line : source.lines().toList()) {
                                    if (line.contains("log.warn(") || line.contains("case RestClientResponseException r ->")) {
                                        assertFalse(line.contains("bodyExcerpt()"), "logs the raw excerpt: " + line);
                                    }
                                }
                            }
                            assertTrue(text.contains("case RestClientResponseException r -> \"status \" + r.status() + \": \""
                                    + " + r.safeExcerpt();"), "the switch in the README");
                            assertTrue(javadoc.contains("case RestClientResponseException r -> \"status \" + r.status() + \": \""
                                    + " + r.safeExcerpt();"), "the switch in RestClientException's Javadoc");
                            assertTrue(flat(text).contains("`bodyExcerpt()` är samma utdrag men **rått**"), "bodyExcerpt() is raw");
                        }),
                test("15d K3: the README and RestClient's Javadoc warn about the JDK's logging by name", () -> {
                    var readme = flat(readme());
                    assertTrue(readme.contains("Slå aldrig på JDK:ns loggning av `HttpClient` i produktion."), readme);
                    var javadoc = flat(mainSource("RestClient.java"));
                    assertTrue(javadoc.contains("Never turn on the JDK's logging of {@link HttpClient} in production."),
                            "RestClient's Javadoc");
                    for (var name : List.of("jdk.httpclient.HttpClient.log", "jdk.internal.httpclient.debug", "headers",
                            "requests", "all")) {
                        assertTrue(readme.contains("`" + name + "`") || readme.contains("-D" + name + "=")
                                || readme.contains("=" + name + "`"), "the README lacks " + name);
                        assertTrue(javadoc.contains("{@code " + name + "}"), "RestClient's Javadoc lacks " + name);
                    }
                }),
                test("15d K5: the cause from spola.json quotes up to 40 characters per value in up to 100 problems", () -> {
                    var readme = flat(readme());
                    assertTrue(readme.contains("upp till 40 tecken per värde i upp till 100 problem (och 200 tecken av ett"
                            + " undantagsmeddelande)"), "the README");
                    assertFalse(readme.contains("upp till 40 tecken av svaret"), "the old text in the README");
                    for (var name : List.of("RestClient.java", "RestClientResponseException.java")) {
                        var javadoc = flat(mainSource(name));
                        assertTrue(javadoc.contains("up to 40 characters per value in up to 100 problems (and 200 characters"
                                + " of an exception's message)"), name);
                        assertFalse(javadoc.contains("up to 40 characters of the response"), "the old text in " + name);
                    }
                }),
                parameterized("the deviation table covers", List.of(
                        List.of("the exception hierarchy", "| Undantagshierarkin |", "`sealed`"),
                        List.of("Content-Type does not decide", "| `Content-Type` i svaret |", "Styr inte tolkningen"),
                        List.of("redirects are not followed", "| Omdirigeringar |", "Följs aldrig"),
                        List.of("HTTP/1.1", "| HTTP-version |", "Bara HTTP/1.1"),
                        List.of("the path without encoding or templates", "| Sökvägen |", "inga mallar och ingen kodning"),
                        List.of("no cookies", "| Cookies |", "Inga cookies"),
                        List.of("no SSLContext or proxy", "| `SSLContext` och proxy |", "Går inte att ange"),
                        List.of("Content-Type cannot be set", "| `Content-Type` i förfrågan |", "Kan inte sättas")),
                        row -> row.getFirst(), row -> {
                            var table = section("Avvikelser från Spring");
                            int at = table.indexOf(row.get(1));
                            assertTrue(at > 0, "missing the row " + row.get(1));
                            var line = table.substring(at, table.indexOf('\n', at));
                            assertTrue(line.contains(row.get(2)), line);
                        }));
    }
}
