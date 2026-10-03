package spola.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import spola.http.Headers;

/**
 * Reads an {@code application/x-www-form-urlencoded} body for {@link WebRequest#readForm};
 * the rules are documented there. The order of the checks is that of {@link JsonBody}, and the
 * parsing is the query's ({@link Decoding#parseQuery}).
 */
final class FormBody {

    private FormBody() {
    }

    /** The router's default for {@code readForm()}: 2 MiB, as Tomcat's {@code maxPostSize}. */
    static final int DEFAULT_MAX_SIZE = 2 << 20;

    private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED.toString();

    /** What a 415 from {@code readForm} tells the client to send. */
    private static final Headers ACCEPT_FORM = Headers.of("Accept", FORM);

    /** What a 415 for a {@code Content-Encoding} tells the client to send: a form, without content coding. */
    private static final Headers ACCEPT_IDENTITY = Headers.of("Accept", FORM, "Accept-Encoding", "identity");

    /** Steps 1–4 of {@link WebRequest#readForm(int)}, after the body was claimed. */
    static FormData read(Headers headers, InputStream body, int maxBytes) throws IOException {
        JsonBody.checkEncoding(headers, ACCEPT_IDENTITY);
        checkContentType(headers);
        var bytes = readAtMost(body, maxBytes);
        for (byte b : bytes) {
            if (b < 0) {
                throw new WebException(400, "Bad Request", "The form body holds a byte above 0x7F; it must be"
                        + " percent-encoded US-ASCII.");
            }
        }
        return new FormData(Decoding.parseQuery(new String(bytes, StandardCharsets.US_ASCII), "The form body"));
    }

    /** Step 2: one {@code Content-Type} field that is {@code application/x-www-form-urlencoded} in UTF-8. */
    static void checkContentType(Headers headers) {
        var values = headers.all("Content-Type");
        if (values.isEmpty()) {
            throw unsupported("Content-Type is missing; send " + FORM + ".");
        }
        var type = JsonBody.single(values, FormBody::unsupported);
        if (!type.type().equals("application") || !type.subtype().equals("x-www-form-urlencoded")) {
            throw unsupported("Content-Type " + Text.quote(type.toString()) + " is not a form; send " + FORM + ".");
        }
        var charset = type.charset();
        if (charset.isPresent() && !charset.get().equals("utf-8")) {
            throw unsupported("charset " + Text.quote(charset.get()) + " is not supported; send the form in UTF-8.");
        }
    }

    /** Step 3: the body, read as it streams in; 413 (reading stops one byte past the limit) if larger than {@code max}. */
    static byte[] readAtMost(InputStream body, int max) throws IOException {
        var input = new JsonBody.LimitedInput(body, max);
        var out = new ByteArrayOutputStream(Math.min(max, 8_192) + 1);
        var buffer = new byte[8_192];
        int n;
        while ((n = input.read(buffer, 0, buffer.length)) >= 0) {
            out.write(buffer, 0, n);
        }
        if (input.exceeded()) {
            throw new WebException(413, "Request body is larger than " + max + " bytes.");
        }
        return out.toByteArray();
    }

    private static WebException unsupported(String detail) {
        return new WebException(415, null, detail, ACCEPT_FORM);
    }
}
