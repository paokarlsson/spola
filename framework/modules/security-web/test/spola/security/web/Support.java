package spola.security.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import spola.http.HttpServer;
import spola.http.HttpServerOptions;
import spola.http.HttpTimeouts;
import spola.security.Authenticator;
import spola.security.Identity;
import spola.security.InMemoryUserStore;
import spola.security.PasswordHasher;
import spola.security.Pbkdf2PasswordHasher;
import spola.security.User;
import spola.security.UserStore;
import spola.web.Router;
import spola.web.RouterBuilder;

/**
 * Shared test helpers: users, a counting authenticator, servers on 127.0.0.1 port 0, a raw
 * socket client, and the record of every response and every router log line that the leak test
 * (criterion 11) checks at the end of the suite.
 */
final class Support {

    private Support() {
    }

    // ---- users -------------------------------------------------------------------------------

    /** The hasher every test uses: the cheapest settings {@code Pbkdf2PasswordHasher.of} allows. */
    static final Pbkdf2PasswordHasher HASHER = Pbkdf2PasswordHasher.of(10_000, 16, 32);

    /** Every test username holds this marker; the leak test looks for it. */
    static final String USER_MARKER = "zUsr";
    /** Every test password holds this marker; the leak test looks for it. */
    static final String PASSWORD_MARKER = "zPwd";

    static final String ADA = "zUsrAda";
    static final String ADA_PASSWORD = "zPwdAda-correct horse";
    static final String BOB = "zUsrBob";
    static final String BOB_PASSWORD = "zPwdBob-battery staple";
    static final String EVE = "zUsrEve";
    static final String EVE_PASSWORD = "zPwdEve-disabled";
    /** A password with colons. */
    static final String COLON = "zUsrColon";
    static final String COLON_PASSWORD = "zPwd:with:colons:";
    /** A user and a password outside ASCII, the password with a surrogate pair. */
    static final String UNICODE = "zUsrÅsa-名前";
    static final String UNICODE_PASSWORD = "zPwdé-ß-😀-日本";

    static final Identity ADA_IDENTITY = new Identity(ADA, Set.of("USER", "ADMIN"));
    static final Identity BOB_IDENTITY = new Identity(BOB, Set.of("USER"));
    static final Identity COLON_IDENTITY = new Identity(COLON, Set.of("USER"));
    static final Identity UNICODE_IDENTITY = new Identity(UNICODE, Set.of("USER"));

    /** The users: ada (USER, ADMIN), bob (USER), eve (disabled), colon and unicode (USER). */
    static final InMemoryUserStore USERS = users();

    private static InMemoryUserStore users() {
        var users = new InMemoryUserStore();
        users.add(new User(ADA, HASHER.hash(ADA_PASSWORD), Set.of("USER", "ADMIN"), true));
        users.add(new User(BOB, HASHER.hash(BOB_PASSWORD), Set.of("USER"), true));
        users.add(new User(EVE, HASHER.hash(EVE_PASSWORD), Set.of("USER"), false));
        users.add(new User(COLON, HASHER.hash(COLON_PASSWORD), Set.of("USER"), true));
        users.add(new User(UNICODE, HASHER.hash(UNICODE_PASSWORD), Set.of("USER"), true));
        return users;
    }

    /** An authenticator over {@link #USERS}. */
    static Authenticator authenticator() {
        return Authenticator.of(USERS, HASHER);
    }

    /**
     * An authenticator over a store and a hasher that count and record what they get: the
     * usernames looked up and the passwords checked. {@code Authenticator} calls
     * {@code matches} exactly once per {@code authenticate}, so {@link #calls()} is the number
     * of {@code authenticate} calls.
     */
    static final class Counting {
        final List<String> usernames = new ArrayList<>();
        final List<String> passwords = new ArrayList<>();
        final AtomicInteger finds = new AtomicInteger();
        final AtomicInteger matches = new AtomicInteger();
        final Authenticator authenticator;

        Counting() {
            this(USERS);
        }

        Counting(UserStore delegate) {
            UserStore store = username -> {
                finds.incrementAndGet();
                synchronized (usernames) {
                    usernames.add(username);
                }
                return delegate.find(username);
            };
            PasswordHasher hasher = new PasswordHasher() {
                @Override
                public String hash(CharSequence password) {
                    return HASHER.hash(password);
                }

                @Override
                public boolean matches(CharSequence password, String encoded) {
                    matches.incrementAndGet();
                    synchronized (passwords) {
                        passwords.add(password.toString());
                    }
                    return HASHER.matches(password, encoded);
                }

                @Override
                public boolean needsRehash(String encoded) {
                    return HASHER.needsRehash(encoded);
                }
            };
            authenticator = Authenticator.of(store, hasher);
        }

        int calls() {
            return matches.get();
        }

        String lastUsername() {
            synchronized (usernames) {
                return usernames.getLast();
            }
        }

        String lastPassword() {
            synchronized (passwords) {
                return passwords.getLast();
            }
        }
    }

    /** {@code Basic <base64(username:password)>}, the password encoded as UTF-8. */
    static String basic(String username, String password) {
        return "Basic " + token(username + ":" + password);
    }

    static String token(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static String token(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ---- servers and requests ----------------------------------------------------------------

    static final InetAddress LOCALHOST;

    static {
        try {
            LOCALHOST = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    static final HttpTimeouts FAST = new HttpTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));

    static HttpServerOptions options() {
        return HttpServerOptions.DEFAULT.withAddress(LOCALHOST).withPort(0).withTimeouts(FAST);
    }

    static HttpServer serve(Router router) throws IOException {
        return HttpServer.start(options(), router);
    }

    /** Every router of the tests logs here, so that the leak test can read every line. */
    static final CapturedLog LOG = new CapturedLog();

    /** A router builder that logs to {@link #LOG}. */
    static RouterBuilder router() {
        return Router.builder().log(LOG);
    }

    /** Every reply any test received, raw, for the leak test. */
    static final List<Reply> REPLIES = new ArrayList<>();
    /** Every {@code Authorization} value any test sent, for the leak test. */
    static final List<String> SENT = new ArrayList<>();

    static void recordSent(String authorization) {
        synchronized (SENT) {
            SENT.add(authorization);
        }
    }

    static void recordReply(Reply reply) {
        synchronized (REPLIES) {
            REPLIES.add(reply);
        }
    }

    /**
     * A response as read from the wire.
     *
     * @param status  the status code
     * @param head    the status line and header lines, raw (ISO-8859-1)
     * @param headers the header lines as name and value
     * @param body    the body as UTF-8 text
     */
    record Reply(int status, String head, List<String[]> headers, String body) {
        String header(String name) {
            for (var h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    return h[1];
                }
            }
            return null;
        }

        List<String> all(String name) {
            var values = new ArrayList<String>();
            for (var h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    values.add(h[1]);
                }
            }
            return values;
        }

        /** The response byte for byte, except the {@code Date} line (it changes every second). */
        String withoutDate() {
            var lines = new ArrayList<String>();
            for (var line : head.split("\r\n")) {
                if (!line.regionMatches(true, 0, "Date:", 0, 5)) {
                    lines.add(line);
                }
            }
            return String.join("\r\n", lines) + "\r\n\r\n" + body;
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    /** One request without credentials. */
    static Reply send(int port, String method, String target) throws IOException {
        return sendWith(port, method, target, List.of());
    }

    /** One request with one {@code Authorization} field. */
    static Reply send(int port, String method, String target, String authorization) throws IOException {
        return sendWith(port, method, target, List.of(authorization));
    }

    /**
     * One request on a new connection with {@code Connection: close} and one
     * {@code Authorization} field per element of {@code authorizations}; the whole response.
     */
    static Reply sendWith(int port, String method, String target, List<String> authorizations) throws IOException {
        var request = new StringBuilder(method).append(' ').append(target)
                .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        for (var authorization : authorizations) {
            recordSent(authorization);
            request.append("Authorization: ").append(authorization).append("\r\n");
        }
        request.append("\r\n");
        return raw(port, request.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Sends {@code bytes} and reads the one response until the server closes. */
    static Reply raw(int port, byte[] bytes) throws IOException {
        try (var socket = new Socket(LOCALHOST, port)) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            var all = new ByteArrayOutputStream();
            var buffer = new byte[8_192];
            try {
                int n;
                while ((n = socket.getInputStream().read(buffer)) >= 0) {
                    all.write(buffer, 0, n);
                }
            } catch (SocketException e) {
                // reset after the response: closed as well
            }
            var reply = parse(all.toByteArray());
            recordReply(reply);
            return reply;
        }
    }

    private static Reply parse(byte[] bytes) throws IOException {
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) {
            throw new IOException("no complete response: \"" + text + "\"");
        }
        var head = text.substring(0, end);
        var lines = head.split("\r\n");
        int status = Integer.parseInt(lines[0].substring(9, 12));
        var headers = new ArrayList<String[]>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.add(new String[] {lines[i].substring(0, colon), lines[i].substring(colon + 1).strip()});
        }
        var body = new String(bytes, end + 4, bytes.length - end - 4, StandardCharsets.UTF_8);
        return new Reply(status, head, List.copyOf(headers), body);
    }

    /** What a handler saw: the identity through both accessors. */
    record Seen(Optional<Identity> identity, Optional<Identity> required) {
    }

    /** A System.Logger that keeps every record, rendered with its stack trace. */
    static final class CapturedLog implements System.Logger {
        private final List<String> records = new ArrayList<>();

        @Override
        public String getName() {
            return "spola.web";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            var text = new StringBuilder(level + " " + message);
            if (thrown != null) {
                var trace = new StringWriter();
                thrown.printStackTrace(new PrintWriter(trace));
                text.append('\n').append(trace);
            }
            synchronized (records) {
                records.add(text.toString());
            }
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            log(level, bundle, params == null || params.length == 0 ? format : MessageFormat.format(format, params),
                    (Throwable) null);
        }

        List<String> records() {
            synchronized (records) {
                return List.copyOf(records);
            }
        }
    }
}
