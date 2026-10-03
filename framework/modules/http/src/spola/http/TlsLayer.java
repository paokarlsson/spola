package spola.http;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The TLS of one server: an {@link SSLContext} built once at start from {@link TlsOptions}, and
 * the parameters every connection gets (protocols, cipher suites, client authentication, ALPN
 * {@code http/1.1}). {@link #layer} puts a server-mode {@link SSLSocket} over an accepted
 * socket without a handshake; the connection's own thread starts the handshake.
 */
final class TlsLayer {

    /** The only application protocol the server offers (ALPN, RFC 7301). */
    static final String HTTP_1_1 = "http/1.1";

    private final SSLSocketFactory factory;
    private final SSLParameters parameters;

    private TlsLayer(SSLSocketFactory factory, SSLParameters parameters) {
        this.factory = factory;
        this.parameters = parameters;
    }

    static TlsLayer of(TlsOptions options) {
        var identity = options.identity();
        var context = identity.context();
        if (context == null) {
            try {
                context = SSLContext.getInstance("TLS");
                context.init(identity.keyManagers(),
                        options.clientTrust().map(TlsTrust::trustManagers).orElse(null), null);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("the JDK could not set up TLS: " + LoggedThrowable.describe(e));
            }
        }
        var parameters = context.getDefaultSSLParameters();
        parameters.setProtocols(options.protocols().toArray(new String[0]));
        if (!options.cipherSuites().isEmpty()) {
            parameters.setCipherSuites(options.cipherSuites().toArray(new String[0]));
        }
        parameters.setUseCipherSuitesOrder(true);
        switch (options.clientAuth()) {
            case NONE -> {
                parameters.setNeedClientAuth(false);
                parameters.setWantClientAuth(false);
            }
            case WANT -> parameters.setWantClientAuth(true);
            case NEED -> parameters.setNeedClientAuth(true);
        }
        parameters.setApplicationProtocols(new String[] {HTTP_1_1});
        return new TlsLayer(context.getSocketFactory(), parameters);
    }

    /** A server-mode TLS socket over {@code socket}, which it closes when it is closed. No handshake yet. */
    SSLSocket layer(Socket socket) throws IOException {
        var ssl = (SSLSocket) factory.createSocket(socket, null, true);
        ssl.setUseClientMode(false);
        ssl.setSSLParameters(parameters);
        return ssl;
    }
}
