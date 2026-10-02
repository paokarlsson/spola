package spola.json;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Runs a document through every entry point of {@link Json}: String, Reader, byte[], InputStream. */
final class Inputs {

    private Inputs() {
    }

    /** One way of handing a document to the parser. */
    record Entry(String name, Parse parse) {
    }

    @FunctionalInterface
    interface Parse {
        JsonValue run(JsonLimits limits);
    }

    /**
     * Every entry point for a text document: String and Reader, and byte[] and InputStream with
     * its UTF-8 encoding when it has one (a text with an unpaired surrogate has none).
     */
    static List<Entry> entries(String text) {
        var entries = new ArrayList<Entry>();
        entries.add(new Entry("String", limits -> Json.parse(text, limits)));
        entries.add(new Entry("Reader", limits -> {
            try {
                return Json.parse(new StringReader(text), limits);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
        utf8(text).ifPresent(bytes -> entries.addAll(entries(bytes)));
        return entries;
    }

    /** The byte entry points: byte[] and InputStream. */
    static List<Entry> entries(byte[] bytes) {
        return List.of(
                new Entry("byte[]", limits -> Json.parse(bytes.clone(), limits)),
                new Entry("InputStream", limits -> {
                    try {
                        return Json.parse(new ByteArrayInputStream(bytes), limits);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
    }

    /** {@code text} for a test name or message: printable ASCII as is, everything else as \\uXXXX. */
    static String show(String text) {
        var out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    /** The UTF-8 encoding, or empty if the text has an unpaired surrogate. */
    static Optional<byte[]> utf8(String text) {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            var bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return Optional.of(bytes);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** The JDK's strict UTF-8 decoding, or empty if the bytes are not UTF-8. */
    static Optional<String> jdkDecode(byte[] bytes) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** Parses through every entry point, checks that all agree, and returns the value. */
    static JsonValue parseAll(String text) {
        return parseAll(entries(text), JsonLimits.DEFAULT);
    }

    static JsonValue parseAll(List<Entry> entries, JsonLimits limits) {
        JsonValue first = null;
        for (var entry : entries) {
            var value = entry.parse().run(limits);
            if (first == null) {
                first = value;
            } else {
                assertEquals(first, value, "the " + entry.name() + " entry point disagrees");
                assertEquals(Json.write(first), Json.write(value), "the " + entry.name() + " entry point disagrees");
            }
        }
        return first;
    }

    /**
     * Checks that every entry point rejects the input with a {@link JsonParseException} (not
     * another exception or an Error), and returns the exception of the first entry point.
     */
    static JsonParseException rejectAll(List<Entry> entries, JsonLimits limits) {
        JsonParseException first = null;
        for (var entry : entries) {
            var thrown = assertThrows(JsonParseException.class, () -> entry.parse().run(limits));
            if (first == null) {
                first = thrown;
            }
        }
        return first;
    }
}
