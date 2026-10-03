package spola.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import spola.http.Headers;
import spola.json.Json;
import spola.json.JsonLimits;
import spola.json.JsonParseException;
import spola.json.JsonValue;
import spola.json.codec.JsonCodec;
import spola.json.codec.JsonMappingException;

/** Reads a request body as JSON for {@link WebRequest#readJson}; the rules are documented there. */
final class JsonBody {

    private JsonBody() {
    }

    /** What a 415 from {@code readJson} tells the client to send. */
    private static final Headers ACCEPT_JSON = Headers.of("Accept", "application/json");

    /** What a 415 for a {@code Content-Encoding} tells the client to send: JSON, without content coding. */
    private static final Headers ACCEPT_IDENTITY = Headers.of("Accept", "application/json",
            "Accept-Encoding", "identity");

    /** Steps 1–5 of {@link WebRequest#readJson}, after the body was claimed. */
    static <T> T read(Headers headers, InputStream body, JsonCodec<T> codec, JsonLimits limits) throws IOException {
        checkEncoding(headers);
        checkContentType(headers);
        var input = new LimitedInput(body, limits.maxInputSize());
        JsonValue json;
        try {
            json = Json.parse(input, limits);
        } catch (JsonParseException e) {
            if (input.exceeded()) {
                throw tooLarge(limits);
            }
            if (input.count() == 0) {
                throw new WebException(400, "Request body is required");
            }
            throw new WebException(400, "Request body is not valid JSON: line " + e.line() + ", column " + e.column()
                    + ": " + withoutExcerpts(e.description()));
        }
        if (input.exceeded()) {
            throw tooLarge(limits); // the parser checks this too; never trust a limit to one place
        }
        try {
            return codec.decode(json);
        } catch (JsonMappingException e) {
            throw invalid(e);
        }
    }

    /** Step 1: {@code Content-Encoding} must be absent or {@code identity} (RFC 9110 §8.4). */
    static void checkEncoding(Headers headers) {
        checkEncoding(headers, ACCEPT_IDENTITY);
    }

    /**
     * {@code Content-Encoding} must be absent or {@code identity} (RFC 9110 §8.4); otherwise 415
     * with {@code accept} as the response's headers. Shared with {@link FormBody}.
     */
    static void checkEncoding(Headers headers, Headers accept) {
        for (var value : headers.all("Content-Encoding")) {
            for (var element : value.split(",", -1)) {
                var coding = element.strip();
                if (!coding.isEmpty() && !coding.equalsIgnoreCase("identity")) {
                    throw new WebException(415, null, "Content-Encoding " + Text.quote(coding)
                            + " is not supported; send the body without content coding.", accept);
                }
            }
        }
    }

    /** Step 2: one {@code Content-Type} field that is JSON in UTF-8. */
    static void checkContentType(Headers headers) {
        var values = headers.all("Content-Type");
        if (values.isEmpty()) {
            throw unsupported("Content-Type is missing; send application/json.");
        }
        var type = single(values, JsonBody::unsupported);
        if (!type.isJson()) {
            throw unsupported("Content-Type " + Text.quote(type.toString()) + " is not JSON; send application/json.");
        }
        var charset = type.charset();
        if (charset.isPresent() && !charset.get().equals("utf-8")) {
            throw unsupported("charset " + Text.quote(charset.get()) + " is not supported; JSON is UTF-8 (RFC 8259"
                    + " section 8.1).");
        }
    }

    /** {@link WebRequest#contentType()}: 415 (without {@code Accept}) if it is not one valid field. */
    static Optional<MediaType> contentType(Headers headers) {
        var values = headers.all("Content-Type");
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(single(values, detail -> new WebException(415, detail)));
    }

    /**
     * The one {@code Content-Type} in {@code values} (not empty), parsed; otherwise
     * {@code error}'s 415. Shared with {@link FormBody}.
     */
    static MediaType single(List<String> values, Function<String, WebException> error) {
        if (values.size() > 1) {
            throw error.apply("Content-Type is given " + values.size() + " times; send it once.");
        }
        try {
            return MediaType.parse(values.getFirst());
        } catch (IllegalArgumentException e) {
            throw error.apply("Invalid Content-Type: " + e.getMessage() + ".");
        }
    }

    private static WebException unsupported(String detail) {
        return new WebException(415, null, detail, ACCEPT_JSON);
    }

    private static WebException tooLarge(JsonLimits limits) {
        return new WebException(413, "Request body is larger than " + limits.maxInputSize() + " bytes.");
    }

    /** 400 with every mapping problem in {@code errors}. */
    private static WebException invalid(JsonMappingException e) {
        int count = e.problemCount();
        var problems = e.problems();
        // the codec lists at most 100 problems and then one that counts the rest: leave that one out
        var listed = problems.subList(0, Math.min(problems.size(), Math.min(count, Problem.MAX_ERRORS)));
        var errors = new ArrayList<Problem.FieldError>(listed.size());
        for (var problem : listed) {
            errors.add(new Problem.FieldError(problem.path(), problem.message()));
        }
        var detail = "Request body has " + count + (count == 1 ? " problem." : " problems.")
                + (count > errors.size() ? " The first " + errors.size() + " are listed." : "");
        return new WebException(400, null, detail, Headers.EMPTY, errors);
    }

    /**
     * A parser description without the parts quoted from the input: a double-quoted excerpt
     * of more than one character (the parser quotes a duplicate member name, escaped, up to
     * 40 characters) becomes {@code "..."}. A single character as the parser shows it
     * ({@code found 'x'}, {@code '"'}, {@code "'"}) stays: one character cannot carry data.
     */
    static String withoutExcerpts(String description) {
        var out = new StringBuilder(description.length());
        int n = description.length();
        int i = 0;
        while (i < n) {
            char c = description.charAt(i);
            if (c == '\'' && i + 2 < n && description.charAt(i + 2) == '\'') {
                out.append(description, i, i + 3); // 'x', also '"'
                i += 3;
            } else if (c == '"') {
                int end = i + 1;
                while (end < n && description.charAt(end) != '"') {
                    end += description.charAt(end) == '\\' ? 2 : 1;
                }
                if (end >= n) {
                    out.append("\"...\"");
                    break;
                }
                out.append(end - i - 1 <= 1 ? description.substring(i, end + 1) : "\"...\"");
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** A stream that gives at most one byte past {@code max} and counts what it gave. */
    static final class LimitedInput extends InputStream {
        private final InputStream in;
        private final long max;
        private long count;

        LimitedInput(InputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        /** Bytes read so far: never more than {@code max + 1}. */
        long count() {
            return count;
        }

        /** Whether the body is larger than {@code max}. */
        boolean exceeded() {
            return count > max;
        }

        @Override
        public int read() throws IOException {
            if (exceeded()) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (exceeded()) {
                return -1;
            }
            long room = max - count; // >= 0: one byte more than this is allowed, to see "too large"
            int allowed = room >= length ? length : (int) room + 1;
            int n = in.read(buffer, offset, allowed);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
