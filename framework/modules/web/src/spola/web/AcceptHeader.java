package spola.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A parsed {@code Accept} header (RFC 9110 §12.5.1) and content negotiation against it, the
 * counterpart of Spring's {@code MediaType.parseMediaTypes} and {@code ContentNegotiationManager}
 * for code that negotiates on its own (see {@link WebRequest#negotiate}).
 *
 * <p><b>Syntax.</b> A comma-separated list of media ranges ({@link MediaType} syntax, with
 * {@code *}{@code /*} and {@code type/*}), each optionally followed by a weight
 * {@code ;q=<qvalue>}: {@code 0} to {@code 1} with at most three decimals ({@code q=0.5},
 * {@code q=1.000}; not {@code q=1.5}, {@code q=-1}, {@code q=0.0001}, {@code q=1.0000} or a
 * quoted value). Parameters before {@code q} belong to the range; parameters after it
 * (accept-ext) are checked for syntax and ignored. A comma inside a quoted parameter value does
 * not split. Empty list elements ({@code a, ,b}) are ignored (RFC 9110 §5.6.1.2). Several
 * {@code Accept} fields are combined as one list (§5.3). At most {@value #MAX_RANGES} ranges;
 * every element is limited like a {@link MediaType} (1 024 characters, 64 parameters).
 * Anything else is an {@link IllegalArgumentException} with a short printable ASCII message.
 *
 * <p><b>Quality.</b> The quality of an offered media type is the weight of the <em>most
 * specific</em> range that includes it ({@link MediaType#includes}), whatever the order in the
 * header: a range with parameters before one without, {@code type/subtype} before
 * {@code type/*}, {@code type/*} before {@code *}{@code /*}. Among ranges equally specific the
 * highest weight counts (so the result never depends on the order). No including range means
 * quality 0; {@code q=0} means "not acceptable", also when {@code *}{@code /*} would accept it.
 *
 * <p>No {@code Accept} at all, or one with only empty elements, accepts everything at quality
 * 1 (RFC 9110 §12.5.1; Spring does the same).
 */
public final class AcceptHeader {

    /** The most media ranges in one request (all {@code Accept} fields together). */
    public static final int MAX_RANGES = 100;

    /** No {@code Accept}: everything is acceptable. */
    public static final AcceptHeader ANY = new AcceptHeader(List.of());

    /**
     * One media range and its weight.
     *
     * @param range   the media range, with the parameters given before {@code q}
     * @param quality the weight in thousandths: 0–1000 ({@code q=0.5} is 500; no {@code q} is 1000)
     */
    public record Range(MediaType range, int quality) {

        /**
         * Checks the components.
         *
         * @throws NullPointerException     if {@code range} is {@code null}
         * @throws IllegalArgumentException if {@code quality} is outside 0–1000
         */
        public Range {
            Objects.requireNonNull(range, "range");
            if (quality < 0 || quality > 1_000) {
                throw new IllegalArgumentException("quality must be between 0 and 1000, was " + quality);
            }
        }

        /** How specific the range is: wildcards first, then the number of parameters. */
        int specificity() {
            int level = range.type().equals("*") ? 0 : range.subtype().equals("*") ? 1 : 2;
            return level * 1_000 + range.parameters().size();
        }
    }

    private final List<Range> ranges;

    private AcceptHeader(List<Range> ranges) {
        this.ranges = ranges;
    }

    /**
     * Parses one {@code Accept} field value.
     *
     * @param value the value
     * @return the header
     * @throws IllegalArgumentException if the value is not a valid {@code Accept} or has more
     *                                  than {@value #MAX_RANGES} ranges
     */
    public static AcceptHeader parse(String value) {
        return parse(List.of(Objects.requireNonNull(value, "value")));
    }

    /**
     * Parses every {@code Accept} field of a request as one list. An empty list (no field) is
     * {@link #ANY}.
     *
     * @param values the field values in order
     * @return the header
     * @throws IllegalArgumentException if a value is not valid or there are more than
     *                                  {@value #MAX_RANGES} ranges in all
     */
    public static AcceptHeader parse(List<String> values) {
        var ranges = new ArrayList<Range>();
        for (var value : values) {
            split(Objects.requireNonNull(value, "value"), ranges);
        }
        return ranges.isEmpty() ? ANY : new AcceptHeader(List.copyOf(ranges));
    }

    /**
     * Splits on commas outside quoted strings and parses every non-empty element. An
     * unterminated quote runs to the end, so the last element's parser reports it.
     */
    private static void split(String value, List<Range> ranges) {
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++; // quoted-pair: the next character never ends the string
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                add(value.substring(start, i), ranges);
                start = i + 1;
            }
        }
        add(value.substring(start), ranges);
    }

    private static void add(String element, List<Range> ranges) {
        var trimmed = stripWhitespace(element);
        if (trimmed.isEmpty()) {
            return;
        }
        if (ranges.size() == MAX_RANGES) {
            throw new IllegalArgumentException("invalid Accept: more than " + MAX_RANGES + " media ranges");
        }
        var parsed = MediaTypeParser.parse(trimmed, true);
        ranges.add(new Range(parsed.mediaType(), parsed.quality()));
    }

    private static String stripWhitespace(String text) {
        int from = 0;
        int to = text.length();
        while (from < to && (text.charAt(from) == ' ' || text.charAt(from) == '\t')) {
            from++;
        }
        while (to > from && (text.charAt(to - 1) == ' ' || text.charAt(to - 1) == '\t')) {
            to--;
        }
        return text.substring(from, to);
    }

    /**
     * The ranges in the order of the header.
     *
     * @return the ranges, unmodifiable; empty for {@link #ANY}
     */
    public List<Range> ranges() {
        return ranges;
    }

    /**
     * The quality of {@code offered}: the weight of the most specific range that includes it
     * (see the class documentation).
     *
     * @param offered a media type without wildcards
     * @return 0–1000; 1000 when there is no {@code Accept}
     * @throws IllegalArgumentException if {@code offered} has a wildcard
     */
    public int quality(MediaType offered) {
        checkOffered(offered);
        if (ranges.isEmpty()) {
            return 1_000;
        }
        int bestSpecificity = -1;
        int quality = 0;
        for (var range : ranges) {
            if (!range.range().includes(offered)) {
                continue;
            }
            int specificity = range.specificity();
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                quality = range.quality();
            } else if (specificity == bestSpecificity) {
                quality = Math.max(quality, range.quality());
            }
        }
        return quality;
    }

    /**
     * Chooses among the media types a handler can produce: the one with the highest quality
     * above 0; on equal quality the first in {@code offered} (the server's order wins). Without
     * {@code Accept} that is the first one.
     *
     * @param offered the media types, without wildcards, in the server's order of preference
     * @return the chosen one, or empty if none is acceptable (406)
     * @throws IllegalArgumentException if {@code offered} is empty or has a wildcard
     */
    public Optional<MediaType> negotiate(List<MediaType> offered) {
        Objects.requireNonNull(offered, "offered");
        if (offered.isEmpty()) {
            throw new IllegalArgumentException("offer at least one media type");
        }
        MediaType best = null;
        int bestQuality = 0;
        for (var type : offered) {
            int quality = quality(type);
            if (quality > bestQuality) {
                best = type;
                bestQuality = quality;
            }
        }
        return Optional.ofNullable(best);
    }

    private static void checkOffered(MediaType offered) {
        Objects.requireNonNull(offered, "offered");
        if (offered.isWildcard()) {
            throw new IllegalArgumentException("an offered media type must not be a wildcard: " + offered);
        }
    }

    /** The ranges in canonical form, with their weights. */
    @Override
    public String toString() {
        if (ranges.isEmpty()) {
            return "*/*";
        }
        var out = new StringBuilder();
        for (var range : ranges) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(range.range());
            if (range.quality() != 1_000) {
                out.append(";q=").append(range.quality() / 1_000).append('.')
                        .append(String.format("%03d", range.quality() % 1_000));
            }
        }
        return out.toString();
    }
}
