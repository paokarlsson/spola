package spola.http;

import java.io.IOException;
import java.util.concurrent.Callable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * How a test talks to the server: plain TCP or TLS. The protocol tests of 07a take a transport,
 * so that the same cases run over both (the plain run is the original one, unchanged).
 */
sealed interface Transport {

    /** Plain HTTP: the 07a tests as they were. */
    Transport PLAIN = new Plain();

    /** The options with this transport's TLS, if any. */
    HttpServerOptions apply(HttpServerOptions options) throws Exception;

    /** A raw client on a new connection, after the TLS handshake if any. */
    Support.Client client(int port, int readTimeoutMillis) throws Exception;

    default Support.Client client(int port) throws Exception {
        return client(port, 5_000);
    }

    default HttpServer start(Handler handler) throws Exception {
        return start(Support.options(), handler);
    }

    default HttpServer start(HttpServerOptions options, Handler handler) throws Exception {
        return Support.start(apply(options), handler);
    }

    /** Plain TCP. */
    record Plain() implements Transport {
        @Override
        public HttpServerOptions apply(HttpServerOptions options) {
            return options;
        }

        @Override
        public Support.Client client(int port, int readTimeoutMillis) throws IOException {
            return new Support.Client(port, readTimeoutMillis);
        }

        @Override
        public String toString() {
            return "plain";
        }
    }

    /**
     * TLS with the test PKI's server key; every client gets its own context that trusts only the
     * test CA and checks the server's name (SAN 127.0.0.1).
     */
    record Tls(Callable<TlsOptions> server, Callable<SSLContext> client) implements Transport {

        /** The RSA server key from PKCS#12, a client that trusts the test CA. */
        static Tls standard() {
            return new Tls(() -> TestPki.get().serverOptions(), () -> TestPki.get().clientContext());
        }

        @Override
        public HttpServerOptions apply(HttpServerOptions options) throws Exception {
            return options.withTls(server.call());
        }

        @Override
        public Support.Client client(int port, int readTimeoutMillis) throws Exception {
            return Support.Client.over(handshake(client.call(), port, readTimeoutMillis), readTimeoutMillis);
        }

        @Override
        public String toString() {
            return "TLS";
        }
    }

    /** A TLS client socket to 127.0.0.1:port that checks the server's name, after its handshake. */
    static SSLSocket handshake(SSLContext context, int port, int readTimeoutMillis) throws IOException {
        var socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);
        try {
            var parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(readTimeoutMillis);
            socket.startHandshake();
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }
}
