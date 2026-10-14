package spola.client;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import spola.json.JsonLimits;
import spola.test.Suite;

/** Criterion 2: the module's boundaries, read from its sources (tests run from framework/). */
final class ModuleTests {

    private ModuleTests() {
    }

    static final Path SOURCES = Path.of("modules/client/src");

    static List<Path> mainSources() throws Exception {
        try (var files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java") && !f.endsWith("module-info.java")).sorted().toList();
        }
    }

    /** The code without comments: what is said about a package in Javadoc is not a use of it. */
    static String code(Path file) throws Exception {
        return Files.readString(file).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    static Suite tests() {
        return suite("module boundaries",
                test("module-info has exactly requires transitive java.net.http, requires transitive spola.json,"
                        + " requires static spola.web and exports spola.client", () -> {
                    var text = code(SOURCES.resolve("module-info.java"));
                    var requires = Pattern.compile("\\brequires\\s+([^;]+);").matcher(text).results()
                            .map(m -> m.group(1).strip().replaceAll("\\s+", " ")).toList();
                    assertEquals(List.of("transitive java.net.http", "transitive spola.json", "static spola.web"), requires);
                    var exports = Pattern.compile("\\bexports\\s+([^;]+);").matcher(text).results()
                            .map(m -> m.group(1).strip()).toList();
                    assertEquals(List.of("spola.client"), exports);
                    for (var other : List.of("opens", "uses", "provides")) {
                        assertFalse(Pattern.compile("\\b" + other + "\\s").matcher(text).find(), other);
                    }
                    var descriptor = RestClient.class.getModule().getDescriptor();
                    var declared = descriptor.requires().stream()
                            .filter(r -> !r.name().equals("java.base"))
                            .map(r -> r.modifiers().stream().map(ModuleDescriptor.Requires.Modifier::toString)
                                    .sorted().collect(Collectors.joining(" ")) + " " + r.name())
                            .collect(Collectors.toSet());
                    assertEquals(Set.of("TRANSITIVE java.net.http", "TRANSITIVE spola.json", "STATIC spola.web"), declared);
                    assertEquals(Set.of("spola.client"), descriptor.exports().stream()
                            .map(ModuleDescriptor.Exports::source).collect(Collectors.toSet()));
                }),
                test("the main code uses nothing from spola.web, spola.http, spola.validation, java.lang.reflect or"
                        + " java.lang.invoke", () -> {
                    var sources = mainSources();
                    assertTrue(sources.size() >= 8, "found " + sources.size() + " files");
                    for (var file : sources) {
                        var code = code(file);
                        for (var forbidden : List.of("spola.web", "spola.http", "spola.validation", "java.lang.reflect",
                                "java.lang.invoke", "getDeclared", "setAccessible", "Class.forName", "MethodHandle")) {
                            assertFalse(code.contains(forbidden), file + " uses " + forbidden);
                        }
                        var imports = Pattern.compile("^import\\s+(static\\s+)?([\\w.]+)", Pattern.MULTILINE).matcher(code)
                                .results().map(m -> m.group(2)).toList();
                        for (var imported : imports) {
                            assertTrue(imported.startsWith("java.") || imported.startsWith("javax.net.ssl.")
                                    || imported.startsWith("spola.json") || imported.startsWith("spola.client"),
                                    file + " imports " + imported);
                        }
                    }
                }),
                test("the main code logs nothing and prints nothing", () -> {
                    for (var file : mainSources()) {
                        var code = code(file);
                        for (var forbidden : List.of("System.Logger", "System.getLogger", "System.out", "System.err",
                                "printStackTrace", "java.util.logging")) {
                            assertFalse(code.contains(forbidden), file + " uses " + forbidden);
                        }
                    }
                }),
                test("the default limit is JsonLimits.DEFAULT's maxInputSize", () -> {
                    assertEquals(JsonLimits.DEFAULT.maxInputSize(), (long) RestClient.DEFAULT_MAX_RESPONSE_SIZE);
                    assertEquals(8_192, RestClient.MAX_ERROR_BODY);
                    assertEquals(1_000, RestClientResponseException.MAX_EXCERPT);
                    assertEquals(1_000, RestClientException.MAX_MESSAGE);
                }),
                test("the exception hierarchy is sealed, with final cases and no public constructors", () -> {
                    assertTrue(RestClientException.class.isSealed());
                    assertEquals(List.of(RestClientResponseException.class, RestClientTransportException.class),
                            List.of(RestClientException.class.getPermittedSubclasses()));
                    assertEquals(RuntimeException.class, RestClientException.class.getSuperclass());
                    for (var name : List.of("RestClient", "RestRequest", "RestClientException", "RestClientResponseException",
                            "RestClientTransportException")) {
                        var code = code(SOURCES.resolve("spola/client/" + name + ".java"));
                        assertFalse(Pattern.compile("public\\s+" + name + "\\s*\\(").matcher(code).find(),
                                name + " has a public constructor");
                    }
                    assertTrue(code(SOURCES.resolve("spola/client/RestClient.java")).contains("public final class RestClient "));
                    assertTrue(code(SOURCES.resolve("spola/client/RestClient.java")).contains("private Builder()"));
                    assertTrue(code(SOURCES.resolve("spola/client/RestRequest.java")).contains("public final class RestRequest "));
                    assertTrue(code(SOURCES.resolve("spola/client/RestClientException.java"))
                            .contains("public abstract sealed class RestClientException extends RuntimeException"));
                    assertTrue(code(SOURCES.resolve("spola/client/RestClientResponseException.java"))
                            .contains("public final class RestClientResponseException extends RestClientException"));
                    assertTrue(code(SOURCES.resolve("spola/client/RestClientTransportException.java"))
                            .contains("public final class RestClientTransportException extends RestClientException"));
                }));
    }
}
