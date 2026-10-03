package spola.web;

import java.util.List;
import java.util.Map;

/**
 * The query of one request, parsed at most once, on first use, and shared by every
 * {@link WebRequest} derived from the same request ({@code withAttribute} keeps it). A failed
 * parse is kept too: every later use throws the same 400.
 */
final class QueryState {

    private final String raw;
    private Map<String, List<String>> params;
    private WebException failure;
    private int parses;

    /** @param raw the raw query, or {@code null} if the target has no {@code ?} */
    QueryState(String raw) {
        this.raw = raw;
    }

    synchronized Map<String, List<String>> params() {
        if (params == null && failure == null) {
            parses++;
            if (raw == null || raw.isEmpty()) {
                params = Map.of();
            } else {
                try {
                    params = Decoding.parseQuery(raw);
                } catch (WebException e) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            throw new WebException(failure.status(), failure.title(), failure.detail().orElse(null));
        }
        return params;
    }

    /** How many times the query was parsed (0 or 1), for tests. */
    synchronized int parses() {
        return parses;
    }
}
