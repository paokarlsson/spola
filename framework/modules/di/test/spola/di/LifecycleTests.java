package spola.di;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertInOrder;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import spola.test.Suite;

/** Closing in reverse creation order, and failures while closing. */
final class LifecycleTests {

    private LifecycleTests() {
    }

    /** A closeable component that logs its creation and closing, and may fail to close. */
    static class Resource implements AutoCloseable {
        final String name;
        final List<String> log;
        final boolean failOnClose;

        Resource(String name, List<String> log, boolean failOnClose) {
            this.name = name;
            this.log = log;
            this.failOnClose = failOnClose;
            log.add("create " + name);
        }

        @Override
        public void close() throws IOException {
            log.add("close " + name);
            if (failOnClose) {
                throw new IOException(name + " refused to close");
            }
        }
    }

    static final class Db extends Resource {
        Db(List<String> log) {
            super("db", log, false);
        }
    }

    static final class Repo extends Resource {
        Repo(Db db, List<String> log, boolean fail) {
            super("repo", log, fail);
        }
    }

    static final class Service extends Resource {
        Service(Repo repo, List<String> log) {
            super("service", log, false);
        }
    }

    static final class Cache extends Resource {
        Cache(List<String> log) {
            super("cache", log, false);
        }
    }

    static List<String> log() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    static ContainerModule shop(List<String> log, boolean repoFails) {
        return binder -> {
            binder.bind(Db.class).to(() -> new Db(log));
            binder.bind(Repo.class).to(Db.class, db -> new Repo(db, log, repoFails));
            binder.bind(Service.class).to(Repo.class, repo -> new Service(repo, log));
            binder.bind(Cache.class).to(() -> new Cache(log));
        };
    }

    static Suite tests() {
        return suite("lifecycle",
                test("closing goes in reverse creation order", () -> {
                    var log = log();
                    var container = Container.create(shop(log, false));
                    container.get(Cache.class);
                    container.get(Service.class);
                    container.close();
                    assertInOrder(List.of("create cache", "create db", "create repo", "create service",
                            "close service", "close repo", "close db", "close cache"), log);
                }),
                test("a failing close does not stop the others, and is reported", () -> {
                    var log = log();
                    var container = Container.create(shop(log, true));
                    container.get(Service.class);
                    container.get(Cache.class);
                    var failure = assertThrows(CloseException.class, container::close);
                    assertInOrder(List.of("create db", "create repo", "create service", "create cache",
                            "close cache", "close service", "close repo", "close db"), log);
                    assertEquals("closing the container: 1 component failed to close:\n"
                            + "  - spola.di.LifecycleTests.Repo: java.io.IOException: repo refused to close",
                            failure.getMessage());
                    assertEquals(1, failure.getSuppressed().length);
                    assertEquals("repo refused to close", failure.getSuppressed()[0].getMessage());
                }),
                test("several failing closes are all collected, in closing order", () -> {
                    var log = log();
                    var container = Container.create(binder -> {
                        binder.bind(Key.of(Resource.class, 1)).to(() -> new Resource("one", log, true));
                        binder.bind(Key.of(Resource.class, 2)).to(() -> new Resource("two", log, false));
                        binder.bind(Key.of(Resource.class, 3)).to(() -> new Resource("three", log, true));
                    });
                    container.get(Key.of(Resource.class, 1));
                    container.get(Key.of(Resource.class, 2));
                    container.get(Key.of(Resource.class, 3));
                    var failure = assertThrows(CloseException.class, container::close);
                    assertEquals(List.of("three refused to close", "one refused to close"),
                            List.of(failure.getSuppressed()[0].getMessage(), failure.getSuppressed()[1].getMessage()));
                    assertInOrder(List.of("close three", "close two", "close one"), log.subList(3, 6));
                }),
                test("close is idempotent and the container refuses use afterwards", () -> {
                    var log = log();
                    var container = Container.create(shop(log, false));
                    container.get(Db.class);
                    container.close();
                    container.close();
                    assertEquals(List.of("create db", "close db"), log);
                    assertThrows(IllegalStateException.class, () -> container.get(Db.class));
                    assertThrows(IllegalStateException.class, () -> container.runInCallScope(() -> { }));
                }),
                test("instances bound with toInstance and prototypes are not closed", () -> {
                    var log = log();
                    var given = new Resource("given", log, false);
                    var container = Container.create(binder -> {
                        binder.bind(Key.of(Resource.class, "given")).toInstance(given);
                        binder.bind(Key.of(Resource.class, "proto")).to(() -> new Resource("proto", log, false))
                                .in(Scope.PROTOTYPE);
                    });
                    container.get(Key.of(Resource.class, "given"));
                    container.get(Key.of(Resource.class, "proto"));
                    container.close();
                    assertEquals(List.of("create given", "create proto"), log);
                }),
                test("call-scoped components are closed in reverse order when their scope ends", () -> {
                    var log = log();
                    try (var container = Container.create(binder -> {
                        binder.bind(Db.class).to(() -> new Db(log));
                        binder.bind(Key.of(Resource.class, "a")).to(Db.class, db -> new Resource("a", log, true))
                                .in(Scope.CALL);
                        binder.bind(Key.of(Resource.class, "b")).to(Key.of(Resource.class, "a"),
                                a -> new Resource("b", log, false)).in(Scope.CALL);
                    })) {
                        var failure = assertThrows(CloseException.class, () -> container.runInCallScope(() ->
                                container.get(Key.of(Resource.class, "b"))));
                        assertTrue(failure.getMessage().startsWith("closing a call scope: 1 component failed"),
                                failure.getMessage());
                        assertEquals(List.of("create db", "create a", "create b", "close b", "close a"), log);
                    }
                    assertEquals("close db", log.getLast());
                }),
                test("when the call scope's body throws, close failures are added to its exception", () -> {
                    var log = log();
                    try (var container = Container.create(binder -> binder.bind(Resource.class)
                            .to(() -> new Resource("r", log, true)).in(Scope.CALL))) {
                        var failure = assertThrows(IllegalStateException.class, () -> container.runInCallScope(() -> {
                            container.get(Resource.class);
                            throw new IllegalStateException("body failed");
                        }));
                        assertEquals("body failed", failure.getMessage());
                        assertEquals(1, failure.getSuppressed().length);
                        assertTrue(failure.getSuppressed()[0] instanceof CloseException);
                    }
                }),
                test("a lazy singleton finishing after close is closed at once and not handed out", () -> {
                    var log = log();
                    var entered = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    var container = Container.create(binder -> binder.bind(Db.class).to(() -> {
                        entered.countDown();
                        release.await();
                        return new Db(log);
                    }));
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        var fetch = executor.submit(() -> container.get(Db.class));
                        entered.await();
                        container.close();
                        release.countDown();
                        var failure = assertThrows(ExecutionException.class, fetch::get);
                        assertTrue(failure.getCause() instanceof ResolutionException, String.valueOf(failure.getCause()));
                        assertTrue(failure.getCause().getMessage().endsWith("the container was closed while"
                                + " spola.di.LifecycleTests.Db was being created; it has been closed again"),
                                failure.getCause().getMessage());
                    }
                    assertEquals(List.of("create db", "close db"), log);
                }),
                test("a failing eager singleton closes what create already made", () -> {
                    var log = log();
                    var failure = assertThrows(ResolutionException.class, () -> Container.create(binder -> {
                        binder.bind(Db.class).to(() -> new Db(log)).in(Scope.EAGER_SINGLETON);
                        binder.bind(Repo.class).to(Db.class, db -> {
                            throw new IOException("schema mismatch");
                        }).in(Scope.EAGER_SINGLETON);
                    }));
                    assertTrue(failure.getMessage().contains("schema mismatch"), failure.getMessage());
                    assertEquals(List.of("create db", "close db"), log);
                }));
    }
}
