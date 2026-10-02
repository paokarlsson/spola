package spola.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses a request head (request-line and header section) strictly after RFC 9112 and RFC 9110,
 * and decides how the body is framed (RFC 9112 §6). Everything that is not exactly right is
 * refused with a {@link ProtocolException}; nothing is repaired or guessed. The descriptions in
 * the exceptions are fixed texts, never request data.
 */
final class RequestParser {

    /** Empty lines (CRLF) skipped before a request-line (RFC 9112 §2.2); more give 400. */
    static final int MAX_EMPTY_LINES = 8;

    private RequestParser() {
    }

    /** How the request body is delimited. */
    enum Framing {
        /** No body (no Content-Length or Content-Length: 0, no Transfer-Encoding). */
        NONE,
        /** Content-Length bytes. */
        LENGTH,
        /** Transfer-Encoding: chunked. */
        CHUNKED
    }

    /**
     * A parsed request head.
     *
     * @param authority      the authority of an absolute-form target, or {@code null}
     * @param query          the query without {@code ?}, or {@code null} if the target has no {@code ?}
     * @param host           the authority of an absolute-form target, otherwise the Host value
     *                       ({@code ""} without either)
     * @param contentLength  the length for {@link Framing#LENGTH}, otherwise 0
     * @param expectContinue the client sent {@code Expect: 100-continue} (HTTP/1.1) and a body
     * @param closeRequested the client sent {@code Connection: close}
     */
    record Head(String method, String target, String path, String query, String authority, String host,
                HttpVersion version, Headers headers, Framing framing, long contentLength,
                boolean expectContinue, boolean closeRequested) {
    }

    static Head parse(ConnectionInput in, HttpLimits limits) throws IOException, ProtocolException {
        String line;
        int empty = 0;
        while ((line = in.readLine(limits.maxRequestLineLength(), 414, "request-line")).isEmpty()) {
            if (++empty > MAX_EMPTY_LINES) {
                throw new ProtocolException(400, "too many empty lines before the request-line (RFC 9112 2.2)");
            }
        }
        int sp1 = line.indexOf(' ');
        int sp2 = sp1 < 0 ? -1 : line.indexOf(' ', sp1 + 1);
        if (sp1 <= 0 || sp2 < 0) {
            throw new ProtocolException(400, "request-line is not method SP request-target SP version (RFC 9112 3)");
        }
        var method = line.substring(0, sp1);
        var target = line.substring(sp1 + 1, sp2);
        var version = version(line.substring(sp2 + 1));
        if (!Syntax.isToken(method)) {
            throw new ProtocolException(400, "method is not a token (RFC 9110 9.1)");
        }
        if (target.isEmpty()) {
            throw new ProtocolException(400, "empty request-target (RFC 9112 3.2)");
        }
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                throw new ProtocolException(400,
                        "control character, space or non-ASCII byte in the request-target (RFC 9112 3.2)");
            }
        }
        var parsed = target(method, target);
        var headers = headerSection(in, limits, "header section");
        return head(method, target, parsed, version, headers, limits);
    }

    /** {@code HTTP-version = HTTP-name "/" DIGIT "." DIGIT} (RFC 9112 §2.3), case-sensitive. */
    private static HttpVersion version(String text) throws ProtocolException {
        if (text.length() != 8 || !text.startsWith("HTTP/") || !Syntax.isDigit(text.charAt(5))
                || text.charAt(6) != '.' || !Syntax.isDigit(text.charAt(7))) {
            throw new ProtocolException(400, "malformed HTTP-version (RFC 9112 2.3)");
        }
        return switch (text) {
            case "HTTP/1.1" -> HttpVersion.HTTP_1_1;
            case "HTTP/1.0" -> HttpVersion.HTTP_1_0;
            default -> throw new ProtocolException(505, "HTTP version not supported (RFC 9110 15.6.6)");
        };
    }

    /** Path, query and authority of the target. */
    private record Target(String path, String query, String authority) {
    }

    /** The request-target forms of RFC 9112 §3.2. */
    private static Target target(String method, String target) throws ProtocolException {
        if (method.equals("CONNECT")) {
            throw new ProtocolException(501, "CONNECT (authority-form) is not supported (RFC 9112 3.2.3)");
        }
        if (target.charAt(0) == '/') {
            return pathAndQuery(target, 0, null);
        }
        if (target.equals("*")) {
            if (!method.equals("OPTIONS")) {
                throw new ProtocolException(400, "asterisk-form is only for OPTIONS (RFC 9112 3.2.4)");
            }
            return new Target("*", null, null);
        }
        var lower = target.toLowerCase(Locale.ROOT);
        int start = lower.startsWith("http://") ? 7 : lower.startsWith("https://") ? 8 : -1;
        if (start < 0) {
            throw new ProtocolException(400,
                    "request-target is not in origin-form, absolute-form or asterisk-form (RFC 9112 3.2)");
        }
        int end = start;
        while (end < target.length() && target.charAt(end) != '/' && target.charAt(end) != '?') {
            end++;
        }
        var authority = target.substring(start, end);
        if (authority.indexOf('@') >= 0) {
            throw new ProtocolException(400, "userinfo in the request-target (RFC 9110 4.2.4)");
        }
        if (!Syntax.isHostAndPort(authority) || authority.isEmpty() || authority.charAt(0) == ':') {
            throw new ProtocolException(400, "invalid authority in the request-target (RFC 9110 4.2.1)");
        }
        return pathAndQuery(target, end, authority);
    }

    private static Target pathAndQuery(String target, int from, String authority) throws ProtocolException {
        int question = target.indexOf('?', from);
        int pathEnd = question < 0 ? target.length() : question;
        if (!Syntax.isUriPart(target, from, pathEnd, "/")) {
            throw new ProtocolException(400, "invalid character in the path (RFC 3986 3.3)");
        }
        String query = null;
        if (question >= 0) {
            if (!Syntax.isUriPart(target, question + 1, target.length(), "/?")) {
                throw new ProtocolException(400, "invalid character in the query (RFC 3986 3.4)");
            }
            query = target.substring(question + 1);
        }
        var path = pathEnd == from ? "/" : target.substring(from, pathEnd);
        return new Target(path, query, authority);
    }

    /**
     * A header (or trailer) section: field lines up to the empty line, within the size and count
     * limits (431), each a token, a colon and a value (RFC 9112 §5, RFC 9110 §5.5).
     */
    static Headers headerSection(ConnectionInput in, HttpLimits limits, String what)
            throws IOException, ProtocolException {
        var fields = new ArrayList<Headers.Field>();
        int budget = limits.maxHeaderSectionSize();
        while (true) {
            var line = in.readLine(Math.max(0, budget - 2), 431, what);
            if (line.isEmpty()) {
                return Headers.of(fields);
            }
            if (fields.size() >= limits.maxHeaderCount()) {
                throw new ProtocolException(431, "more than " + limits.maxHeaderCount() + " fields in the " + what);
            }
            budget -= line.length() + 2;
            fields.add(field(line));
        }
    }

    private static Headers.Field field(String line) throws ProtocolException {
        if (Syntax.isWhitespace(line.charAt(0))) {
            throw new ProtocolException(400, "obs-fold or whitespace at the start of a field line (RFC 9112 5.2, 2.2)");
        }
        int colon = line.indexOf(':');
        if (colon < 0) {
            throw new ProtocolException(400, "field line without a colon (RFC 9112 5)");
        }
        if (colon == 0) {
            throw new ProtocolException(400, "empty field name (RFC 9110 5.1)");
        }
        var name = line.substring(0, colon);
        if (Syntax.isWhitespace(name.charAt(name.length() - 1))) {
            throw new ProtocolException(400, "whitespace between field name and colon (RFC 9112 5.1)");
        }
        if (!Syntax.isToken(name)) {
            throw new ProtocolException(400, "field name is not a token (RFC 9110 5.1)");
        }
        int start = colon + 1;
        int end = line.length();
        while (start < end && Syntax.isWhitespace(line.charAt(start))) {
            start++;
        }
        while (end > start && Syntax.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        for (int i = start; i < end; i++) {
            char c = line.charAt(i);
            if (!Syntax.isFieldValueChar(c)) {
                throw new ProtocolException(400, c == 0 ? "NUL in a field value (RFC 9110 5.5)"
                        : "control character in a field value (RFC 9110 5.5)");
            }
        }
        return new Headers.Field(name, line.substring(start, end));
    }

    private static Head head(String method, String target, Target parsed, HttpVersion version, Headers headers,
                             HttpLimits limits) throws ProtocolException {
        var hosts = headers.all("Host");
        if (hosts.size() > 1) {
            throw new ProtocolException(400, "more than one Host field (RFC 9112 3.2)");
        }
        if (hosts.isEmpty() && version == HttpVersion.HTTP_1_1) {
            throw new ProtocolException(400, "HTTP/1.1 request without Host (RFC 9112 3.2)");
        }
        if (!hosts.isEmpty() && !Syntax.isHostAndPort(hosts.getFirst())) {
            throw new ProtocolException(400, "invalid Host field value (RFC 9112 3.2)");
        }
        var host = parsed.authority() != null ? parsed.authority() : hosts.isEmpty() ? "" : hosts.getFirst();

        var framing = Framing.NONE;
        long length = 0;
        var transferEncodings = headers.all("Transfer-Encoding");
        var contentLengths = headers.all("Content-Length");
        if (!transferEncodings.isEmpty()) {
            if (version == HttpVersion.HTTP_1_0) {
                throw new ProtocolException(400, "Transfer-Encoding in an HTTP/1.0 request (RFC 9112 6.1)");
            }
            if (!contentLengths.isEmpty()) {
                throw new ProtocolException(400, "both Transfer-Encoding and Content-Length (RFC 9112 6.1)");
            }
            var codings = listElements(transferEncodings);
            if (codings.isEmpty() || !codings.getLast().equalsIgnoreCase("chunked")) {
                throw new ProtocolException(400, "chunked is not the final transfer coding (RFC 9112 6.3)");
            }
            if (codings.size() > 1) {
                throw new ProtocolException(501, "transfer coding other than exactly chunked (RFC 9112 6.1)");
            }
            framing = Framing.CHUNKED;
        } else if (!contentLengths.isEmpty()) {
            if (contentLengths.size() > 1) {
                throw new ProtocolException(400, "more than one Content-Length field (RFC 9112 6.3)");
            }
            length = contentLength(contentLengths.getFirst());
            if (length > limits.maxBodySize()) {
                throw new ProtocolException(413, "Content-Length over maxBodySize (RFC 9110 15.5.14)");
            }
            framing = length == 0 ? Framing.NONE : Framing.LENGTH;
        }

        boolean expectContinue = false;
        if (version == HttpVersion.HTTP_1_1) { // RFC 9110 10.1.1: ignored in HTTP/1.0
            for (var expectation : listElements(headers.all("Expect"))) {
                if (!expectation.equalsIgnoreCase("100-continue")) {
                    throw new ProtocolException(417, "expectation other than 100-continue (RFC 9110 10.1.1)");
                }
                expectContinue = framing != Framing.NONE;
            }
        }
        boolean close = listElements(headers.all("Connection")).stream().anyMatch(t -> t.equalsIgnoreCase("close"));
        return new Head(method, target, parsed.path(), parsed.query(), parsed.authority(), host, version, headers,
                framing, length, expectContinue, close);
    }

    /** {@code Content-Length = 1*DIGIT} (RFC 9110 §8.6), within {@code long}. */
    private static long contentLength(String value) throws ProtocolException {
        if (value.isEmpty()) {
            throw new ProtocolException(400, "empty Content-Length (RFC 9110 8.6)");
        }
        long length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Syntax.isDigit(c)) {
                throw new ProtocolException(400, "Content-Length is not only decimal digits (RFC 9110 8.6)");
            }
            if (length > (Long.MAX_VALUE - (c - '0')) / 10) {
                throw new ProtocolException(400, "Content-Length does not fit in 63 bits (RFC 9110 8.6)");
            }
            length = length * 10 + (c - '0');
        }
        return length;
    }

    /** The non-empty, trimmed elements of comma-separated list values (RFC 9110 §5.6.1). */
    private static List<String> listElements(List<String> values) {
        var elements = new ArrayList<String>();
        for (var value : values) {
            for (var element : value.split(",", -1)) {
                var trimmed = element.strip();
                if (!trimmed.isEmpty()) {
                    elements.add(trimmed);
                }
            }
        }
        return elements;
    }
}
