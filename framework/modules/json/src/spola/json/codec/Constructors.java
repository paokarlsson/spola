package spola.json.codec;

/**
 * The constructor shapes {@link JsonCodec#object(Constructors.Of2, Field, Field)} and its
 * siblings take: one functional interface per number of fields, 1 to 16. You never write these
 * types; you pass a constructor reference such as {@code Person::new} (or a lambda), and the
 * compiler checks that its parameter types match the fields' types in order.
 */
public final class Constructors {

    private Constructors() {
    }

    /**
     * A constructor of 1 value.
     *
     * @param <A> the type of field 1
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of1<A, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @return the value
         */
        R create(A a);
    }

    /**
     * A constructor of 2 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of2<A, B, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @return the value
         */
        R create(A a, B b);
    }

    /**
     * A constructor of 3 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of3<A, B, C, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @return the value
         */
        R create(A a, B b, C c);
    }

    /**
     * A constructor of 4 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of4<A, B, C, D, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @return the value
         */
        R create(A a, B b, C c, D d);
    }

    /**
     * A constructor of 5 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of5<A, B, C, D, E, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @return the value
         */
        R create(A a, B b, C c, D d, E e);
    }

    /**
     * A constructor of 6 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of6<A, B, C, D, E, F, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f);
    }

    /**
     * A constructor of 7 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of7<A, B, C, D, E, F, G, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g);
    }

    /**
     * A constructor of 8 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of8<A, B, C, D, E, F, G, H, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h);
    }

    /**
     * A constructor of 9 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of9<A, B, C, D, E, F, G, H, I, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i);
    }

    /**
     * A constructor of 10 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of10<A, B, C, D, E, F, G, H, I, J, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j);
    }

    /**
     * A constructor of 11 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of11<A, B, C, D, E, F, G, H, I, J, K, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @param k the value of field 11
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k);
    }

    /**
     * A constructor of 12 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of12<A, B, C, D, E, F, G, H, I, J, K, L, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @param k the value of field 11
         * @param l the value of field 12
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l);
    }

    /**
     * A constructor of 13 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of13<A, B, C, D, E, F, G, H, I, J, K, L, M, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @param k the value of field 11
         * @param l the value of field 12
         * @param m the value of field 13
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m);
    }

    /**
     * A constructor of 14 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <N> the type of field 14
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of14<A, B, C, D, E, F, G, H, I, J, K, L, M, N, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @param k the value of field 11
         * @param l the value of field 12
         * @param m the value of field 13
         * @param n the value of field 14
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m, N n);
    }

    /**
     * A constructor of 15 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <N> the type of field 14
     * @param <O> the type of field 15
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of15<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @param k the value of field 11
         * @param l the value of field 12
         * @param m the value of field 13
         * @param n the value of field 14
         * @param o the value of field 15
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m, N n, O o);
    }

    /**
     * A constructor of 16 values.
     *
     * @param <A> the type of field 1
     * @param <B> the type of field 2
     * @param <C> the type of field 3
     * @param <D> the type of field 4
     * @param <E> the type of field 5
     * @param <F> the type of field 6
     * @param <G> the type of field 7
     * @param <H> the type of field 8
     * @param <I> the type of field 9
     * @param <J> the type of field 10
     * @param <K> the type of field 11
     * @param <L> the type of field 12
     * @param <M> the type of field 13
     * @param <N> the type of field 14
     * @param <O> the type of field 15
     * @param <P> the type of field 16
     * @param <R> the type it creates
     */
    @FunctionalInterface
    public interface Of16<A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, R> {

        /**
         * Creates the value.
         *
         * @param a the value of field 1
         * @param b the value of field 2
         * @param c the value of field 3
         * @param d the value of field 4
         * @param e the value of field 5
         * @param f the value of field 6
         * @param g the value of field 7
         * @param h the value of field 8
         * @param i the value of field 9
         * @param j the value of field 10
         * @param k the value of field 11
         * @param l the value of field 12
         * @param m the value of field 13
         * @param n the value of field 14
         * @param o the value of field 15
         * @param p the value of field 16
         * @return the value
         */
        R create(A a, B b, C c, D d, E e, F f, G g, H h, I i, J j, K k, L l, M m, N n, O o, P p);
    }
}
