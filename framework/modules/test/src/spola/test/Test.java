package spola.test;

/**
 * A node in a test tree: a single {@link TestCase} or a {@link Suite} of further nodes.
 * Create them with {@link Tests}.
 */
public sealed interface Test permits TestCase, Suite {

    /**
     * The node's name, unique among its siblings.
     *
     * @return the name, never blank and without line breaks
     */
    String name();
}
