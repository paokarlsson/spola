package spola.web;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A cookie to set with a {@code Set-Cookie} response header (RFC 6265 §4.1), the counterpart of
 * Spring's {@code ResponseCookie}. Immutable and thread-safe; add it to a response with
 * {@link Responses#withCookie}:
 *
 * <pre>{@code
 * var cookie = ResponseCookie.of("session", token).maxAge(Duration.ofDays(30)).secure(true).build();
 * return Responses.withCookie(Response.text(200, "ok"), cookie);
 * // Set-Cookie: session=...; Path=/; Max-Age=2592000; Secure; HttpOnly; SameSite=Lax
 *
 * Responses.withCookie(response, ResponseCookie.expire("session").build());
 * // Set-Cookie: session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax
 * }</pre>
 *
 * <p><b>Defaults</b> (Spring has none): {@code Path=/}, {@code HttpOnly} and
 * {@code SameSite=Lax}. {@code Secure} is off unless set with {@link Builder#secure}, so that
 * development over {@code http://localhost} works. Without {@link Builder#maxAge} the cookie is a
 * session cookie. No {@code Expires} attribute is ever written, only {@code Max-Age} (every
 * browser in use understands it, and it does not depend on the server's clock).
 *
 * <p><b>Validation</b> happens in {@link Builder#build()}, which throws an
 * {@link IllegalArgumentException} with a short message (request data in it is escaped and
 * shortened):
 * <ul>
 *   <li>the name is a {@code token} (RFC 9110 §5.6.2), so not empty and without {@code =},
 *       spaces or control characters;</li>
 *   <li>the value is {@code *cookie-octet}, optionally inside {@code "} (visible US-ASCII
 *       without {@code "}, {@code ,}, {@code ;} and {@code \});</li>
 *   <li>the path starts with {@code /} and holds only US-ASCII without control characters and
 *       {@code ;};</li>
 *   <li>the domain is LDH labels (letters, digits, hyphens; not starting or ending with a
 *       hyphen; 1–63 characters) separated by dots, at most 253 characters; one leading dot is
 *       removed;</li>
 *   <li>{@code maxAge} is not negative and is whole seconds;</li>
 *   <li>{@code SameSite=None} needs {@code Secure};</li>
 *   <li>a name starting with {@code __Secure-} needs {@code Secure}; a name starting with
 *       {@code __Host-} needs {@code Secure}, {@code Path=/} and no domain (RFC 6265bis
 *       §4.1.3). The prefixes are compared case-sensitively.</li>
 * </ul>
 */
public final class ResponseCookie {

    /** The {@code SameSite} attribute (RFC 6265bis §4.1.2.7). */
    public enum SameSite {
        /** {@code SameSite=Strict}: only on same-site requests. */
        STRICT("Strict"),
        /** {@code SameSite=Lax}, the default: also on top-level navigations from other sites. */
        LAX("Lax"),
        /** {@code SameSite=None}: on cross-site requests too; needs {@code Secure}. */
        NONE("None");

        private final String text;

        SameSite(String text) {
            this.text = text;
        }

        /**
         * The attribute's value as written.
         *
         * @return {@code Strict}, {@code Lax} or {@code None}
         */
        public String text() {
            return text;
        }
    }

    private final String name;
    private final String value;
    private final String path;
    private final String domain;
    private final Duration maxAge;
    private final boolean secure;
    private final boolean httpOnly;
    private final SameSite sameSite;
    private final String headerValue;

    private ResponseCookie(Builder b) {
        this.name = b.name;
        this.value = b.value;
        this.path = b.path;
        this.domain = b.domain;
        this.maxAge = b.maxAge;
        this.secure = b.secure;
        this.httpOnly = b.httpOnly;
        this.sameSite = b.sameSite;
        var out = new StringBuilder(name).append('=').append(value).append("; Path=").append(path);
        if (domain != null) {
            out.append("; Domain=").append(domain);
        }
        if (maxAge != null) {
            out.append("; Max-Age=").append(maxAge.getSeconds());
        }
        if (secure) {
            out.append("; Secure");
        }
        if (httpOnly) {
            out.append("; HttpOnly");
        }
        this.headerValue = out.append("; SameSite=").append(sameSite.text()).toString();
    }

    /**
     * A builder for a cookie with the defaults {@code Path=/}, {@code HttpOnly},
     * {@code SameSite=Lax}, not {@code Secure}, no domain and no {@code Max-Age} (a session
     * cookie).
     *
     * @param name  the name, a token
     * @param value the value, {@code *cookie-octet} or quoted; may be empty
     * @return the builder; nothing is checked until {@link Builder#build()}
     */
    public static Builder of(String name, String value) {
        return new Builder(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"), "/", null,
                null, false, true, SameSite.LAX, false);
    }

    /**
     * A builder for a cookie that removes the cookie {@code name} from the browser: an empty
     * value and {@code Max-Age=0}, with the same defaults as {@link #of}. Give the same
     * {@code path} and {@code domain} the cookie was set with, or the browser keeps it. The
     * builder's {@link Builder#maxAge} cannot be used.
     *
     * @param name the name, a token
     * @return the builder
     */
    public static Builder expire(String name) {
        return new Builder(Objects.requireNonNull(name, "name"), "", "/", null, Duration.ZERO, false, true,
                SameSite.LAX, true);
    }

    /**
     * The name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * The value, quotes included if it is quoted.
     *
     * @return the value, possibly empty
     */
    public String value() {
        return value;
    }

    /**
     * The {@code Path} attribute, {@code /} by default.
     *
     * @return the path
     */
    public String path() {
        return path;
    }

    /**
     * The {@code Domain} attribute, without a leading dot.
     *
     * @return the domain, or empty for a host-only cookie
     */
    public Optional<String> domain() {
        return Optional.ofNullable(domain);
    }

    /**
     * The {@code Max-Age} attribute.
     *
     * @return the lifetime in whole seconds, or empty for a session cookie
     */
    public Optional<Duration> maxAge() {
        return Optional.ofNullable(maxAge);
    }

    /**
     * Whether the {@code Secure} attribute is set (off by default).
     *
     * @return whether the cookie is sent only over HTTPS
     */
    public boolean secure() {
        return secure;
    }

    /**
     * Whether the {@code HttpOnly} attribute is set (on by default).
     *
     * @return whether scripts cannot read the cookie
     */
    public boolean httpOnly() {
        return httpOnly;
    }

    /**
     * The {@code SameSite} attribute ({@link SameSite#LAX} by default).
     *
     * @return the value
     */
    public SameSite sameSite() {
        return sameSite;
    }

    /**
     * The {@code Set-Cookie} field value, attributes in this fixed order:
     * {@code name=value; Path=...; Domain=...; Max-Age=...; Secure; HttpOnly; SameSite=...}
     * (absent attributes are left out; {@code Path} and {@code SameSite} are always there).
     *
     * @return the field value
     */
    public String headerValue() {
        return headerValue;
    }

    /**
     * The same as {@link #headerValue()}.
     *
     * @return the {@code Set-Cookie} field value
     */
    @Override
    public String toString() {
        return headerValue;
    }

    /** Equal when the {@code Set-Cookie} values are equal. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ResponseCookie cookie && cookie.headerValue.equals(headerValue);
    }

    @Override
    public int hashCode() {
        return headerValue.hashCode();
    }

    /**
     * Builds a {@link ResponseCookie}. Immutable: every method returns a new builder, so a
     * builder can be shared and reused. Nothing is checked until {@link #build()}.
     */
    public static final class Builder {
        private final String name;
        private final String value;
        private final String path;
        private final String domain;
        private final Duration maxAge;
        private final boolean secure;
        private final boolean httpOnly;
        private final SameSite sameSite;
        private final boolean expiring;

        private Builder(String name, String value, String path, String domain, Duration maxAge, boolean secure,
                boolean httpOnly, SameSite sameSite, boolean expiring) {
            this.name = name;
            this.value = value;
            this.path = path;
            this.domain = domain;
            this.maxAge = maxAge;
            this.secure = secure;
            this.httpOnly = httpOnly;
            this.sameSite = sameSite;
            this.expiring = expiring;
        }

        /**
         * The {@code Path} attribute ({@code /} unless set).
         *
         * @param path starts with {@code /}; US-ASCII without control characters and {@code ;}
         * @return the new builder
         */
        public Builder path(String path) {
            return new Builder(name, value, Objects.requireNonNull(path, "path"), domain, maxAge, secure, httpOnly,
                    sameSite, expiring);
        }

        /**
         * The {@code Domain} attribute (none unless set: a host-only cookie). A leading dot is
         * removed, as browsers do.
         *
         * @param domain LDH labels separated by dots, such as {@code example.com}
         * @return the new builder
         */
        public Builder domain(String domain) {
            return new Builder(name, value, path, Objects.requireNonNull(domain, "domain"), maxAge, secure, httpOnly,
                    sameSite, expiring);
        }

        /**
         * The {@code Max-Age} attribute (none unless set: a session cookie). {@link Duration#ZERO}
         * removes the cookie; {@link ResponseCookie#expire} does that with an empty value.
         *
         * @param maxAge not negative, whole seconds
         * @return the new builder
         * @throws IllegalStateException if this builder came from {@link ResponseCookie#expire}
         */
        public Builder maxAge(Duration maxAge) {
            Objects.requireNonNull(maxAge, "maxAge");
            if (expiring) {
                throw new IllegalStateException("ResponseCookie.expire always has Max-Age=0; use ResponseCookie.of"
                        + " for another lifetime");
            }
            return new Builder(name, value, path, domain, maxAge, secure, httpOnly, sameSite, false);
        }

        /**
         * The {@code Secure} attribute (off unless set): the browser sends the cookie only over
         * HTTPS. Needed for {@code SameSite=None} and the {@code __Secure-} and {@code __Host-}
         * prefixes.
         *
         * @param secure whether to set it
         * @return the new builder
         */
        public Builder secure(boolean secure) {
            return new Builder(name, value, path, domain, maxAge, secure, httpOnly, sameSite, expiring);
        }

        /**
         * The {@code HttpOnly} attribute (on unless turned off): scripts cannot read the cookie.
         *
         * @param httpOnly whether to set it
         * @return the new builder
         */
        public Builder httpOnly(boolean httpOnly) {
            return new Builder(name, value, path, domain, maxAge, secure, httpOnly, sameSite, expiring);
        }

        /**
         * The {@code SameSite} attribute ({@link SameSite#LAX} unless set).
         *
         * @param sameSite the value; {@link SameSite#NONE} needs {@code Secure}
         * @return the new builder
         */
        public Builder sameSite(SameSite sameSite) {
            return new Builder(name, value, path, domain, maxAge, secure, httpOnly,
                    Objects.requireNonNull(sameSite, "sameSite"), expiring);
        }

        /**
         * Checks everything (see {@link ResponseCookie}) and builds the cookie.
         *
         * @return the cookie
         * @throws IllegalArgumentException if a rule is broken; the message is printable ASCII
         */
        public ResponseCookie build() {
            if (!RouterBuilder.isToken(name)) {
                throw new IllegalArgumentException("cookie name " + Text.quote(name)
                        + " is not a token (RFC 9110 5.6.2)");
            }
            if (!CookieState.isCookieValue(value)) {
                throw new IllegalArgumentException("cookie " + Text.quote(name) + ": the value " + Text.quote(value)
                        + " is not cookie-octets (RFC 6265 4.1.1: visible US-ASCII without '\"', ',', ';' and '\\')");
            }
            if (!isPath(path)) {
                throw new IllegalArgumentException("cookie " + Text.quote(name) + ": the path " + Text.quote(path)
                        + " must start with / and hold only US-ASCII without control characters and ';'");
            }
            var host = domain == null ? null : domain.startsWith(".") ? domain.substring(1) : domain;
            if (host != null && !isDomain(host)) {
                throw new IllegalArgumentException("cookie " + Text.quote(name) + ": the domain " + Text.quote(domain)
                        + " is not letters, digits and hyphens in labels separated by dots");
            }
            if (maxAge != null && maxAge.isNegative()) {
                throw new IllegalArgumentException("cookie " + Text.quote(name) + ": maxAge must not be negative");
            }
            if (maxAge != null && maxAge.getNano() != 0) {
                throw new IllegalArgumentException("cookie " + Text.quote(name)
                        + ": maxAge must be whole seconds (Max-Age has no fractions)");
            }
            if (sameSite == SameSite.NONE && !secure) {
                throw new IllegalArgumentException("cookie " + Text.quote(name) + ": SameSite=None needs Secure");
            }
            if (name.startsWith("__Secure-") && !secure) {
                throw new IllegalArgumentException("cookie " + Text.quote(name) + ": the __Secure- prefix needs Secure");
            }
            if (name.startsWith("__Host-")) {
                if (!secure) {
                    throw new IllegalArgumentException("cookie " + Text.quote(name) + ": the __Host- prefix needs Secure");
                }
                if (!path.equals("/")) {
                    throw new IllegalArgumentException("cookie " + Text.quote(name)
                            + ": the __Host- prefix needs Path=/");
                }
                if (host != null) {
                    throw new IllegalArgumentException("cookie " + Text.quote(name)
                            + ": the __Host- prefix allows no Domain");
                }
            }
            return new ResponseCookie(new Builder(name, value, path, host, maxAge, secure, httpOnly, sameSite,
                    expiring));
        }

        /** {@code path-value} of RFC 6265 §4.1.1 that starts with {@code /}. */
        private static boolean isPath(String path) {
            if (path.isEmpty() || path.charAt(0) != '/') {
                return false;
            }
            for (int i = 0; i < path.length(); i++) {
                char c = path.charAt(i);
                if (c < 0x20 || c >= 0x7F || c == ';') {
                    return false;
                }
            }
            return true;
        }

        /** LDH labels (RFC 1034 §3.5, RFC 1123 §2.1) separated by dots. */
        private static boolean isDomain(String domain) {
            if (domain.isEmpty() || domain.length() > 253) {
                return false;
            }
            for (var label : domain.split("\\.", -1)) {
                if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                    return false;
                }
                for (int i = 0; i < label.length(); i++) {
                    char c = label.charAt(i);
                    boolean ldh = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
                    if (!ldh) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
