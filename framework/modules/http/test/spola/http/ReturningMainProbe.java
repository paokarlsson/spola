package spola.http;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An application whose {@code main} returns right after {@link HttpServer#start}, run in a JVM of
 * its own by {@code ShutdownTests}: the server alone keeps that JVM alive, and a handler stops it
 * ({@code /shutdown}). Prints {@code port <n>} when it listens and {@code entered <path>} when a
 * handler starts. Not an entry of any module in the build.
 */
public final class ReturningMainProbe {

    private ReturningMainProbe() {
    }

    /**
     * Starts the server on a free port and returns.
     *
     * @param args unused
     * @throws Exception if the server cannot start
     */
    public static void main(String[] args) throws Exception {
        var server = new AtomicReference<HttpServer>();
        server.set(HttpServer.start(HttpServerOptions.DEFAULT.withAddress(java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))
                .withPort(0), request -> {
            System.out.println("entered " + request.path());
            System.out.flush();
            return switch (request.path()) {
                case "/slow" -> {
                    Thread.sleep(1_000);
                    yield Response.text(200, "slow done");
                }
                case "/shutdown" -> {
                    server.get().close();
                    yield Response.text(200, "bye");
                }
                default -> Response.text(200, "hi");
            };
        }));
        System.out.println("port " + server.get().port());
        System.out.flush();
    }
}
