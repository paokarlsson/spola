package spola.security.web;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import spola.security.AuthResult;
import spola.security.Authenticator;
import spola.web.WebException;
import spola.web.WebRequest;

/**
 * HTTP Basic authentication (RFC 7617), the counterpart of Spring Security's
 * {@code httpBasic()} ({@code BasicAuthenticationFilter} with
 * {@code BasicAuthenticationEntryPoint}). Register it with
 * {@link WebSecurity.Builder#authentication}:
 *
 * <pre>{@code
 * WebSecurity.builder().authentication(HttpBasic.of(authenticator, "app"))
 * }</pre>
 *
 * <h2>How the {@code Authorization} field is read</h2>
 * <ul>
 *   <li>No {@code Authorization} field, or one whose scheme is not {@code Basic}, gives
 *       {@link AuthOutcome.None}, so another mechanism can read it (a {@code Bearer} token,
 *       say). The scheme is the leading run of token characters and is compared without regard
 *       to case ({@code basic}, {@code BASIC}).</li>
 *   <li>More than one {@code Authorization} field is a {@link WebException} 400: the field is a
 *       singleton (RFC 9110 §11.6.2). The {@link WebSecurity} filter checks this before any
 *       mechanism runs.</li>
 *   <li>After {@code Basic} come one or more spaces (SP) and a token68. Anything else (no
 *       token, a tab, a second word) gives {@link AuthOutcome.Rejected}.</li>
 *   <li>A token68 longer than {@value #MAX_TOKEN_CHARS} characters is rejected before it is
 *       decoded. The token must be strict, canonical standard Base64 (RFC 4648 §4) <b>with</b>
 *       padding: no {@code -_} alphabet, no missing or extra {@code =}, no non-zero unused bits
 *       (decoding and encoding again gives the same text).</li>
 *   <li>The bytes are decoded as UTF-8 with {@link CodingErrorAction#REPORT} (invalid UTF-8 is
 *       rejected, never replaced) and split at the <b>first</b> {@code :}; the rest, which may
 *       hold {@code :}, is the password. No {@code :} is rejected. A username or a password with
 *       a control character (U+0000–U+001F, U+007F; RFC 7617 §2) is rejected, and so is an
 *       empty username. There is no normalisation.</li>
 *   <li>Only then is {@link Authenticator#authenticate} called, exactly once:
 *       {@link AuthResult.Authenticated} gives {@link AuthOutcome.Success} and
 *       {@link AuthResult.Failed} (either reason) gives {@link AuthOutcome.Rejected}. An
 *       exception from the authenticator (from its user store, say) is not caught: it goes to
 *       the router's exception mappers, 500 without one.</li>
 * </ul>
 *
 * <p><b>Secrets.</b> Nothing here is logged and no exception or response holds the username,
 * the password or the field's value. The decoded bytes and characters are overwritten with
 * zeros when the authenticator returns (best effort). The password reaches the authenticator as
 * a {@link CharSequence} over that character array, valid only during the call; the username is
 * a {@code String}, and the field's value is a {@code String} in the request, so neither can be
 * cleared, nor can copies that the authenticator, its hasher or its store make.
 *
 * <p><b>Cost.</b> Every request with Basic credentials that pass the checks costs one password
 * hash check (PBKDF2), by design of Basic: successful logins are not cached and there is no
 * rate limiting.
 */
public final class HttpBasic implements RequestAuthentication {

    /** The longest token68 that is decoded, in characters. */
    public static final int MAX_TOKEN_CHARS = 8_192;

    /** The longest realm, in characters. */
    public static final int MAX_REALM_CHARS = 100;

    private static final String SCHEME = "Basic";
    private static final AuthOutcome NONE = new AuthOutcome.None();
    private static final AuthOutcome REJECTED = new AuthOutcome.Rejected();

    private final Authenticator authenticator;
    private final String realm;
    private final String challenge;

    private HttpBasic(Authenticator authenticator, String realm) {
        this.authenticator = authenticator;
        this.realm = realm;
        this.challenge = SCHEME + " realm=\"" + realm + "\", charset=\"UTF-8\"";
    }

    /**
     * HTTP Basic against {@code authenticator}.
     *
     * @param authenticator checks the username and the password
     * @param realm         the realm in {@code WWW-Authenticate}: 1 to 100 characters of
     *                      printable ASCII (space included) without {@code "} and {@code \}
     * @return the mechanism
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code realm} breaks the rules above
     */
    public static HttpBasic of(Authenticator authenticator, String realm) {
        Objects.requireNonNull(authenticator, "authenticator");
        Objects.requireNonNull(realm, "realm");
        if (realm.isEmpty() || realm.length() > MAX_REALM_CHARS) {
            throw new IllegalArgumentException("realm must be 1 to " + MAX_REALM_CHARS + " characters, was "
                    + realm.length());
        }
        for (int i = 0; i < realm.length(); i++) {
            char c = realm.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '"' || c == '\\') {
                throw new IllegalArgumentException("realm " + Text.quote(realm)
                        + " must be printable ASCII without \" and \\");
            }
        }
        return new HttpBasic(authenticator, realm);
    }

    /**
     * The realm given to {@link #of}.
     *
     * @return the realm
     */
    public String realm() {
        return realm;
    }

    /**
     * The value of the {@code WWW-Authenticate} field that a 401 carries for this mechanism.
     *
     * @return {@code Basic realm="<realm>", charset="UTF-8"}
     */
    public String challenge() {
        return challenge;
    }

    /**
     * Reads the request's {@code Authorization} field as described in the class documentation.
     *
     * @param request the request
     * @return {@link AuthOutcome.None}, {@link AuthOutcome.Success} or
     *         {@link AuthOutcome.Rejected}
     * @throws WebException     400 if the request has more than one {@code Authorization}
     *                          field
     * @throws RuntimeException whatever the authenticator throws
     */
    @Override
    public AuthOutcome authenticate(WebRequest request) {
        var values = request.headers().all("Authorization");
        if (values.isEmpty()) {
            return NONE;
        }
        if (values.size() > 1) {
            throw WebSecurity.duplicateAuthorization();
        }
        var value = values.getFirst();
        int schemeEnd = 0;
        while (schemeEnd < value.length() && Matchers.isTchar(value.charAt(schemeEnd))) {
            schemeEnd++;
        }
        if (!value.substring(0, schemeEnd).equalsIgnoreCase(SCHEME)) {
            return NONE;
        }
        int tokenStart = schemeEnd;
        while (tokenStart < value.length() && value.charAt(tokenStart) == ' ') {
            tokenStart++;
        }
        if (tokenStart == schemeEnd || tokenStart == value.length()) {
            return REJECTED; // no space after the scheme, or no token
        }
        if (value.length() - tokenStart > MAX_TOKEN_CHARS) {
            return REJECTED;
        }
        var token = value.substring(tokenStart);
        if (token.length() % 4 != 0) {
            return REJECTED; // padding is required
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            return REJECTED;
        }
        var chars = new char[bytes.length]; // UTF-8 never gives more chars than bytes
        try {
            if (!Base64.getEncoder().encodeToString(bytes).equals(token)) {
                return REJECTED; // not canonical: non-zero unused bits
            }
            int length = decodeUtf8(bytes, chars);
            return length < 0 ? REJECTED : check(chars, length);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Arrays.fill(chars, '\0');
        }
    }

    /** Splits at the first {@code :}, checks both parts and asks the authenticator. */
    private AuthOutcome check(char[] chars, int length) {
        int colon = -1;
        for (int i = 0; i < length; i++) {
            char c = chars[i];
            if (c < 0x20 || c == 0x7F) {
                return REJECTED; // RFC 7617 §2: no control characters in the user-id or the password
            }
            if (c == ':' && colon < 0) {
                colon = i;
            }
        }
        if (colon <= 0) {
            return REJECTED; // no colon, or an empty username
        }
        var username = new String(chars, 0, colon);
        var password = CharBuffer.wrap(chars, colon + 1, length - colon - 1).asReadOnlyBuffer();
        return switch (authenticator.authenticate(username, password)) {
            case AuthResult.Authenticated authenticated -> new AuthOutcome.Success(authenticated.identity());
            case AuthResult.Failed failed -> REJECTED;
        };
    }

    /**
     * Decodes {@code bytes} as strict UTF-8 into {@code chars} (at least as long as
     * {@code bytes}): the number of characters, or -1 if the bytes are not valid UTF-8.
     */
    private static int decodeUtf8(byte[] bytes, char[] chars) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var in = ByteBuffer.wrap(bytes);
        var out = CharBuffer.wrap(chars);
        var result = decoder.decode(in, out, true);
        if (result.isError() || result.isOverflow()) {
            return -1;
        }
        result = decoder.flush(out);
        if (result.isError() || result.isOverflow() || in.hasRemaining()) {
            return -1;
        }
        return out.position();
    }

    /**
     * The realm, never credentials.
     *
     * @return {@code HttpBasic[realm="..."]}
     */
    @Override
    public String toString() {
        return "HttpBasic[realm=" + Text.quote(realm) + "]";
    }
}
