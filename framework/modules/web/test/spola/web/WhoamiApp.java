package spola.web;

import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.Response;
import spola.web.ForwardedHeaders;
import spola.web.Router;
import spola.web.TrustedProxies;

final class WhoamiApp {

    static Router router(TrustedProxies proxies) {
        var router = Router.builder()
                .filter(ForwardedHeaders.trusting(proxies))  // first: every filter after it sees the client
                .get("/api/whoami", request -> Response.text(200,
                        request.scheme() + " " + request.clientAddress().getHostAddress()))
                .build();
        // Behind Caddy:          "https 203.0.113.7"
        // Directly, no proxy:    "http 127.0.0.1"
        // From outside with forged headers to a server that does not trust the sender: ignored
        return router;
    }

    public static void main(String[] args) throws Exception {
        // Caddy and the app on Docker's network; only Caddy's port is published
        var router = router(TrustedProxies.privateNetworks());  // 10/8, 172.16/12, 192.168/16, loopback, fc00::/7
        var server = HttpServer.start(HttpServerOptions.DEFAULT.withPort(8080), router);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
