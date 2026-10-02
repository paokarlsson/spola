package spola.json.codec;

import static spola.json.codec.Problems.problem;
import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import spola.json.Json;
import spola.json.JsonArray;
import spola.json.JsonObject;
import spola.json.JsonString;
import spola.json.JsonValue;
import spola.test.Body;
import spola.test.Suite;

/** Deep values give a problem past 512 levels, never StackOverflowError, on any thread. */
final class DepthTests {

    private DepthTests() {
    }

    static final int DEEP = 100_000;

    /** A chain of {@code count} nodes; built in a loop, since the record's own methods would recurse. */
    static Model.Node chain(int count) {
        var node = new Model.Node("leaf", List.of());
        for (int i = 1; i < count; i++) {
            node = new Model.Node("n", List.of(node));
        }
        return node;
    }

    /** The same chain as a tree built in code. */
    static JsonValue chainJson(int count) {
        JsonValue node = JsonObject.of("name", JsonString.of("leaf"), "children", JsonArray.of());
        for (int i = 1; i < count; i++) {
            node = JsonObject.of("name", JsonString.of("n"), "children", JsonArray.of(node));
        }
        return node;
    }

    /** The path of the 513th nested container in a chain: an object after 256 objects and arrays each. */
    static final String TOO_DEEP_PATH = "$" + ".children[0]".repeat(256);

    static final String TOO_DEEP = "nesting deeper than 512 levels of objects and arrays";

    static void onThread(Thread.Builder builder, Body body) throws Exception {
        var failure = new AtomicReference<Throwable>();
        var thread = builder.start(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        thread.join();
        switch (failure.get()) {
            case null -> { }
            case Exception e -> throw e;
            case Error e -> throw e;
            case Throwable e -> throw new AssertionError(e);
        }
    }

    static void encodeAndDecodeDeep() {
        var value = chain(DEEP);
        var encoded = Problems.exception(() -> Model.NODE.encode(value));
        assertEquals(List.of(problem(TOO_DEEP_PATH, TOO_DEEP)), encoded.problems());
        Problems.exception(() -> Model.NODE.write(value));
        var decoded = Problems.exception(() -> Model.NODE.decode(chainJson(DEEP)));
        assertEquals(List.of(problem(TOO_DEEP_PATH, TOO_DEEP)), decoded.problems());
    }

    static Suite tests() {
        return suite("depth",
                test("100 000 levels via lazy: encoding and decoding fail with a problem on a platform thread", () ->
                        onThread(Thread.ofPlatform(), DepthTests::encodeAndDecodeDeep)),
                test("100 000 levels via lazy: encoding and decoding fail with a problem on a virtual thread", () ->
                        onThread(Thread.ofVirtual(), DepthTests::encodeAndDecodeDeep)),
                test("the depth limit leaves room on the stack: 100 000 levels on a thread with a 512 KiB stack", () ->
                        onThread(Thread.ofPlatform().stackSize(512 * 1024), DepthTests::encodeAndDecodeDeep)),
                test("a sealed hierarchy 100 000 levels deep fails the same way", () -> onThread(Thread.ofVirtual(), () -> {
                    Model.Shape shape = new Model.Circle(1);
                    for (int i = 0; i < DEEP; i++) {
                        shape = new Model.Group("g", List.of(shape));
                    }
                    var deep = shape;
                    var e = Problems.exception(() -> Model.SHAPE.encode(deep));
                    assertEquals(1, e.problems().size());
                    assertEquals(TOO_DEEP, e.problems().getFirst().message());
                    JsonValue json = JsonObject.of("type", JsonString.of("circle"), "radius", spola.json.JsonNumber.of(1));
                    for (int i = 0; i < DEEP; i++) {
                        json = JsonObject.of("type", JsonString.of("group"), "label", JsonString.of("g"),
                                "shapes", JsonArray.of(json));
                    }
                    var tree = json;
                    var d = Problems.exception(() -> Model.SHAPE.decode(tree));
                    assertEquals(1, d.problems().size());
                    assertEquals(TOO_DEEP, d.problems().getFirst().message());
                    assertEquals("$" + ".shapes[0]".repeat(256), d.problems().getFirst().path());
                })),
                test("512 levels, the parser's limit, encode and decode; 514 do not", () -> {
                    var ok = chain(256);
                    var text = Model.NODE.write(ok);
                    assertTrue(text.startsWith("{\"name\":\"n\",\"children\":[{\"name\":\"n\""), text);
                    Json.parse(text); // the parser accepts it too
                    var back = Model.NODE.read(text);
                    assertEquals(text, Model.NODE.write(back));
                    var tooDeep = chain(257);
                    assertEquals(List.of(problem(TOO_DEEP_PATH, TOO_DEEP)), Problems.of(() -> Model.NODE.encode(tooDeep)));
                    assertEquals(List.of(problem(TOO_DEEP_PATH, TOO_DEEP)), Problems.of(() -> Model.NODE.decode(chainJson(257))));
                }));
    }
}
