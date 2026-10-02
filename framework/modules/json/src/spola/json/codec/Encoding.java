package spola.json.codec;

import java.util.List;

/**
 * The state of one {@code encode} call: the nesting depth and the chain of {@code lazy} codecs
 * entered since the last object or array. Encoding stops at the first problem: a value that
 * cannot be written is a programming error, not input to report back, so
 * {@link #fail(Path, String)} returns the exception to throw.
 */
final class Encoding {

    private int depth;
    private int lazyChain;

    /** The exception for a value that cannot be encoded at {@code path}. */
    static JsonMappingException fail(Path path, String message) {
        return new JsonMappingException(List.of(new JsonProblem(path.toString(), message)));
    }

    /** The exception for a value that is {@code null} where the codec is not {@code nullable}. */
    static JsonMappingException nullValue(Path path) {
        return fail(path, "value is null (wrap the codec in nullable(...) to write null)");
    }

    /** A {@code lazy} codec that resolves to itself without an object or array in between. */
    static IllegalStateException lazyLoop() {
        return new IllegalStateException("more than " + Decoding.MAX_LAZY_CHAIN
                + " lazy codecs in a row without an object or array between them;"
                + " does a lazy codec resolve to itself?");
    }

    /** Enters an object or array at {@code path}; fails past {@link Decoding#MAX_DEPTH}. */
    int enter(Path path) {
        if (depth >= Decoding.MAX_DEPTH) {
            throw fail(path, "nesting deeper than " + Decoding.MAX_DEPTH + " levels of objects and arrays");
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

    /**
     * Enters a {@code lazy} codec. A recursive type always has an object or array between two
     * visits of the same lazy codec; a long chain without one means a codec that refers to
     * itself directly and would recurse until the stack overflows.
     */
    void enterLazy() {
        if (++lazyChain > Decoding.MAX_LAZY_CHAIN) {
            lazyChain = 0;
            throw lazyLoop();
        }
    }

    /** Leaves a {@code lazy} codec. */
    void exitLazy() {
        lazyChain--;
    }
}
