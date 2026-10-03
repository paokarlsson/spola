package spola.security;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** The rules for usernames and roles shared by {@link User} and {@link Identity}, and escaping for {@code toString()}. */
final class Names {

    /** The longest username, in {@code char}s. */
    static final int MAX_USERNAME_CHARS = 256;
    /** The longest role, in characters. */
    static final int MAX_ROLE_CHARS = 100;

    private Names() {
    }

    static String username(String username, String what) {
        Objects.requireNonNull(username, what);
        if (username.isEmpty() || username.length() > MAX_USERNAME_CHARS) {
            throw new IllegalArgumentException(what + " must be 1 to " + MAX_USERNAME_CHARS + " characters");
        }
        return username;
    }

    /** An unmodifiable, sorted copy; every role is 1 to 100 characters of printable ASCII without spaces. */
    static Set<String> roles(Set<String> roles) {
        Objects.requireNonNull(roles, "roles");
        var copy = new TreeSet<String>();
        for (var role : roles) {
            Objects.requireNonNull(role, "role");
            if (role.isEmpty() || role.length() > MAX_ROLE_CHARS) {
                throw new IllegalArgumentException("a role must be 1 to " + MAX_ROLE_CHARS + " characters");
            }
            for (int i = 0; i < role.length(); i++) {
                char c = role.charAt(i);
                if (c < 0x21 || c > 0x7e) {
                    throw new IllegalArgumentException("a role must be printable ASCII without spaces");
                }
            }
            copy.add(role);
        }
        return Collections.unmodifiableSortedSet(copy);
    }

    /**
     * {@code text} with control characters, line and paragraph separators, bidirectional
     * controls and lone surrogates written as {@code \}{@code uXXXX}, so that a username in a
     * {@code toString()} cannot break or forge a log line. A backslash is doubled.
     */
    static String escape(String text) {
        var out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean pair = Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1));
            if (pair) {
                out.append(c).append(text.charAt(++i));
            } else if (c == '\\') {
                out.append("\\\\");
            } else if (Character.isISOControl(c) || Character.isSurrogate(c) || c == ' ' || c == ' '
                    || c == '؜' || c == '‎' || c == '‏' || (c >= '‪' && c <= '‮')
                    || (c >= '⁦' && c <= '⁩') || c == '﻿') {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
