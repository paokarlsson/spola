package spola.json.codec;

import java.util.ArrayList;
import java.util.List;
import spola.json.JsonLimits;

/**
 * The state of one {@code decode} call: the problems found so far (at most
 * {@link #MAX_PROBLEMS} kept, the rest counted), the nesting depth and the chain of
 * {@code lazy} codecs entered since the last object or array. Confined to the calling thread;
 * a codec never stores it.
 *
 * <p>A codec reports a problem and returns {@code null}; its caller sees that it failed because
 * {@link #count()} went up. That is how an object knows not to call its constructor.
 */
final class Decoding {

    /** Problems kept per decode call; more are counted and summarized in one last problem. */
    static final int MAX_PROBLEMS = 100;

    /** The deepest nesting of objects and arrays a codec descends into, as in the parser. */
    static final int MAX_DEPTH = JsonLimits.DEFAULT.maxDepth();

    /** {@code lazy} codecs entered in a row without an object or array between them. */
    static final int MAX_LAZY_CHAIN = 64;

    private final ArrayList<JsonProblem> problems = new ArrayList<>();
    private int count;
    private int depth;
    private int lazyChain;

    /** Records a problem at {@code path}; past {@link #MAX_PROBLEMS} it is only counted. */
    void report(Path path, String message) {
        count++;
        if (problems.size() < MAX_PROBLEMS) {
            problems.add(new JsonProblem(path.toString(), message));
        }
    }

    /** Every problem reported so far, kept or not. */
    int count() {
        return count;
    }

    /**
     * Enters an object or array at {@code path}. Returns the state to give to {@link #exit},
     * or -1 (after reporting a problem) when the nesting would exceed {@link #MAX_DEPTH}; the
     * caller then does not descend.
     */
    int enter(Path path) {
        if (depth >= MAX_DEPTH) {
            report(path, "nesting deeper than " + MAX_DEPTH + " levels of objects and arrays");
            return -1;
        }
        depth++;
        int saved = lazyChain;
        lazyChain = 0;
        return saved;
    }

    /** Leaves the object or array entered with {@link #enter}. */
    void exit(int saved) {
        depth--;
        lazyChain = saved;
    }

    /** Enters a {@code lazy} codec; see {@link Encoding#enterLazy()}. */
    void enterLazy() {
        if (++lazyChain > MAX_LAZY_CHAIN) {
            lazyChain = 0;
            throw Encoding.lazyLoop();
        }
    }

    /** Leaves a {@code lazy} codec. */
    void exitLazy() {
        lazyChain--;
    }

    /** The problems, with the summary of the omitted ones last; empty if there were none. */
    List<JsonProblem> problems() {
        if (count <= problems.size()) {
            return List.copyOf(problems);
        }
        var all = new ArrayList<>(problems);
        int omitted = count - problems.size();
        all.add(new JsonProblem(Path.ROOT.toString(), omitted + " more problem" + (omitted == 1 ? "" : "s")
                + " not listed (at most " + MAX_PROBLEMS + " are listed)"));
        return List.copyOf(all);
    }
}
