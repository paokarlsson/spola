package spola.di;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertFalse;
import static spola.test.Assertions.assertNotNull;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/** Lazy and eager singletons, prototypes and the call scope. */
final class ScopeTests {

    private ScopeTests() {
    }

    /** Identity matters here, so a plain class rather than a record. */
    static final class Thing {
    }

    static final class Holder {
        final Thing thing;

        Holder(Thing thing) {
            this.thing = thing;
        }
    }

    static Suite tests() {
        return suite("scopes",
                test("a lazy singleton is created on first use, once", () -> {
                    var created = new AtomicInteger();
                    try (var container = Container.create(binder -> binder.bind(Thing.class).to(() -> {
                        created.incrementAndGet();
                        return new Thing();
                    }))) {
                        assertEquals(0, created.get());
                        assertSame(container.get(Thing.class), container.get(Thing.class));
                        assertEquals(1, created.get());
                    }
                }),
                test("an eager singleton is created by create", () -> {
                    var created = new AtomicInteger();
                    try (var container = Container.create(binder -> binder.bind(Thing.class).to(() -> {
                        created.incrementAndGet();
                        return new Thing();
                    }).in(Scope.EAGER_SINGLETON))) {
                        assertEquals(1, created.get());
                        assertSame(container.get(Thing.class), container.get(Thing.class));
                        assertEquals(1, created.get());
                    }
                }),
                test("a failing eager singleton fails create", () -> {
                    var failure = assertThrows(ResolutionException.class, () -> Container.create(binder ->
                            binder.bind(Thing.class).to(() -> {
                                throw new IOException("no config");
                            }).in(Scope.EAGER_SINGLETON)));
                    assertTrue(failure.getMessage().startsWith("cannot resolve spola.di.ScopeTests.Thing:"), failure.getMessage());
                }),
                test("a prototype is new on every fetch and every injection", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Thing.class).to(Thing::new).in(Scope.PROTOTYPE);
                        binder.bind(Holder.class).to(Thing.class, Holder::new).in(Scope.PROTOTYPE);
                    })) {
                        var first = container.get(Thing.class);
                        var second = container.get(Thing.class);
                        assertNotNull(first);
                        assertFalse(first == second, "two fetches gave the same instance");
                        assertFalse(container.get(Holder.class).thing == container.get(Holder.class).thing,
                                "two injections gave the same instance");
                    }
                }),
                test("the call scope gives one instance per scope and different ones across scopes", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Thing.class).to(Thing::new).in(Scope.CALL);
                        binder.bind(Holder.class).to(Thing.class, Holder::new).in(Scope.PROTOTYPE);
                    })) {
                        var inFirst = new AtomicReference<Thing>();
                        container.runInCallScope(() -> {
                            var thing = container.get(Thing.class);
                            assertSame(thing, container.get(Thing.class));
                            assertSame(thing, container.get(Holder.class).thing);
                            inFirst.set(thing);
                        });
                        var inSecond = container.callInCallScope(() -> {
                            var thing = container.get(Thing.class);
                            assertSame(thing, container.get(Thing.class));
                            return thing;
                        });
                        assertNotNull(inFirst.get());
                        assertFalse(inFirst.get() == inSecond, "two call scopes shared an instance");
                    }
                }),
                test("a nested call scope has its own instances", () -> {
                    try (var container = Container.create(binder -> binder.bind(Thing.class).to(Thing::new).in(Scope.CALL))) {
                        container.runInCallScope(() -> {
                            var outer = container.get(Thing.class);
                            var inner = container.callInCallScope(() -> container.get(Thing.class));
                            assertFalse(outer == inner, "the nested scope reused the outer instance");
                            assertSame(outer, container.get(Thing.class));
                        });
                    }
                }),
                test("a call-scoped component outside a call scope fails", () -> {
                    try (var container = Container.create(binder -> binder.bind(Thing.class).to(Thing::new).in(Scope.CALL))) {
                        var failure = assertThrows(ResolutionException.class, () -> container.get(Thing.class));
                        assertEquals("cannot resolve spola.di.ScopeTests.Thing: spola.di.ScopeTests.Thing is call-scoped"
                                + " and no call scope is active; use Container.runInCallScope or callInCallScope",
                                failure.getMessage());
                    }
                }),
                test("callInCallScope passes the body's checked exception through", () -> {
                    try (var container = Container.create()) {
                        var failure = assertThrows(IOException.class, () -> container.callInCallScope(() -> {
                            throw new IOException("boom");
                        }));
                        assertEquals("boom", failure.getMessage());
                    }
                }),
                test("call scopes of two containers are independent", () -> {
                    try (var one = Container.create(binder -> binder.bind(Thing.class).to(Thing::new).in(Scope.CALL));
                         var two = Container.create(binder -> binder.bind(Thing.class).to(Thing::new).in(Scope.CALL))) {
                        one.runInCallScope(() ->
                                assertThrows(ResolutionException.class, () -> two.get(Thing.class)));
                    }
                }));
    }
}
