/**
 * Spola's HTTP/1.1 server, a replacement for the embedded Tomcat that Spring Boot starts.
 *
 * <p>{@link spola.http.HttpServer#start} binds a {@link java.net.ServerSocket} and serves every
 * connection on its own virtual thread with blocking I/O: keep-alive, pipelining, strict
 * parsing after RFC 9112 and RFC 9110, chunked transfer coding in both directions,
 * {@code Expect: 100-continue}, limits ({@link spola.http.HttpLimits}) and timeouts
 * ({@link spola.http.HttpTimeouts}) as values, protection against request smuggling and
 * response splitting, and a graceful stop ({@link spola.http.HttpServer#close}).
 *
 * <p>HTTPS through {@link spola.http.HttpServerOptions#withTls}: {@link spola.http.TlsOptions}
 * with a PKCS#12 keystore, PEM files or a ready {@code SSLContext}, TLS 1.3 and 1.2 only, ALPN
 * {@code http/1.1}, optional client certificates ({@link spola.http.ClientAuth}), and a
 * handshake on the connection's own thread within a total
 * {@link spola.http.HttpTimeouts#handshakeTimeout()}. The handler sees the negotiated TLS in
 * {@link spola.http.Request#tls()}. TLS itself is the JDK's {@code javax.net.ssl}; the module
 * sets no system or security properties.
 *
 * <ul>
 *   <li>{@link spola.http.Handler}: {@code Response handle(Request request)}, called
 *       concurrently from many connections.</li>
 *   <li>{@link spola.http.Request}: method, request-target as sent, raw path and query, version,
 *       {@link spola.http.Headers}, the body as a bounded {@link java.io.InputStream}, and the
 *       connection: {@link spola.http.Request#scheme()}, {@link spola.http.Request#tls()} and
 *       the sender's address and port, {@link spola.http.Request#remoteAddress()} (taken at
 *       accept, without a name lookup; behind a reverse proxy the proxy's, see
 *       {@code ForwardedHeaders} in {@code spola.web}).</li>
 *   <li>{@link spola.http.Response}: status, headers and a {@link spola.http.ResponseBody}
 *       (empty, bytes or streaming).</li>
 * </ul>
 *
 * <p>The module requires no other Spola module, uses no reflection and logs through
 * {@link java.lang.System.Logger} (logger name {@code spola.http}). It does not use
 * {@code com.sun.net.httpserver}.
 *
 * <p>{@code requires static java.net.http} is for the module's tests only (they talk to the
 * server with {@link java.net.http.HttpClient}); it is optional at run time and no main code
 * uses it. Tests are compiled as part of the module, so they can only read what the module
 * reads.
 */
module spola.http {
    requires static java.net.http;

    exports spola.http;
}
