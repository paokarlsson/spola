package spola.di;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertSame;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import spola.test.Suite;

/** Binding forms, qualifiers, modules and how factories are called. */
final class BindingTests {

    private BindingTests() {
    }

    enum Lang { EN, SV }

    interface Greeter {
        String greet(String name);
    }

    record EnglishGreeter() implements Greeter {
        public String greet(String name) {
            return "Hello " + name;
        }
    }

    record SwedishGreeter() implements Greeter {
        public String greet(String name) {
            return "Hej " + name;
        }
    }

    /** Depends on both greeters, told apart by qualifier. */
    record Welcome(Greeter english, Greeter swedish) {
    }

    record Repo() {
    }

    record Clock() {
    }

    record Service(Repo repo, Clock clock) {
    }

    record Five(Repo a, Clock b, Repo c, Clock d, Repo e) {
    }

    /** A module class with value-based equality, installed from two places. */
    record SharedModule(AtomicInteger runs) implements ContainerModule {
        @Override
        public void configure(Binder binder) {
            runs.incrementAndGet();
            binder.bind(Repo.class).to(Repo::new);
        }
    }

    static Suite tests() {
        return suite("bindings",
                test("two implementations of one type, fetched by qualifier", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Key.of(Greeter.class, Lang.EN)).to(EnglishGreeter::new);
                        binder.bind(Key.of(Greeter.class, Lang.SV)).to(SwedishGreeter::new);
                        binder.bind(Welcome.class).to(Key.of(Greeter.class, Lang.EN), Key.of(Greeter.class, Lang.SV),
                                Welcome::new);
                    })) {
                        assertEquals("Hello Ada", container.get(Key.of(Greeter.class, Lang.EN)).greet("Ada"));
                        assertEquals("Hej Ada", container.get(Key.of(Greeter.class, Lang.SV)).greet("Ada"));
                        var welcome = container.get(Welcome.class);
                        assertSame(container.get(Key.of(Greeter.class, Lang.EN)), welcome.english());
                        assertSame(container.get(Key.of(Greeter.class, Lang.SV)), welcome.swedish());
                        var unqualified = assertThrows(ResolutionException.class, () -> container.get(Greeter.class));
                        assertEquals("cannot resolve spola.di.BindingTests.Greeter: spola.di.BindingTests.Greeter"
                                + " is not bound (bound for that type: [spola.di.BindingTests.Greeter[EN],"
                                + " spola.di.BindingTests.Greeter[SV]])", unqualified.getMessage());
                    }
                }),
                test("typed form passes the declared dependencies as arguments", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Repo.class).to(Repo::new);
                        binder.bind(Clock.class).to(Clock::new);
                        binder.bind(Service.class).to(Repo.class, Clock.class, Service::new);
                        binder.bind(Five.class).to(Repo.class, Clock.class, Repo.class, Clock.class, Repo.class, Five::new);
                    })) {
                        var service = container.get(Service.class);
                        assertSame(container.get(Repo.class), service.repo());
                        assertSame(container.get(Clock.class), service.clock());
                        assertSame(container.get(Repo.class), container.get(Five.class).e());
                    }
                }),
                test("resolver form, as in the mission's example", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Repo.class).to(Repo::new);
                        binder.bind(Clock.class).to(Clock::new);
                        binder.bind(Service.class).to(Dependencies.of(Repo.class, Clock.class),
                                r -> new Service(r.get(Repo.class), r.get(Clock.class)));
                    })) {
                        assertSame(container.get(Repo.class), container.get(Service.class).repo());
                    }
                }),
                test("toInstance binds a ready object", () -> {
                    var repo = new Repo();
                    try (var container = Container.create(binder -> binder.bind(Repo.class).toInstance(repo))) {
                        assertSame(repo, container.get(Repo.class));
                    }
                }),
                test("modules group bindings, install others, and run once each", () -> {
                    var runs = new AtomicInteger();
                    ContainerModule clocks = binder -> {
                        binder.install(new SharedModule(runs));
                        binder.bind(Clock.class).to(Clock::new);
                    };
                    ContainerModule services = binder -> {
                        binder.install(new SharedModule(runs));
                        binder.install(clocks);
                        binder.bind(Service.class).to(Repo.class, Clock.class, Service::new);
                    };
                    try (var container = Container.create(services, clocks)) {
                        assertEquals(1, runs.get());
                        assertSame(container.get(Clock.class), container.get(Service.class).clock());
                    }
                }),
                test("a factory exception fails with the dependency chain and the cause", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Repo.class).to(() -> {
                            throw new IOException("disk gone");
                        });
                        binder.bind(Clock.class).to(Clock::new);
                        binder.bind(Service.class).to(Repo.class, Clock.class, Service::new);
                    })) {
                        var failure = assertThrows(ResolutionException.class, () -> container.get(Service.class));
                        assertTrue(failure.getMessage().startsWith("cannot resolve spola.di.BindingTests.Service"
                                        + " -> spola.di.BindingTests.Repo: the factory of spola.di.BindingTests.Repo (bound at "),
                                failure.getMessage());
                        assertTrue(failure.getMessage().endsWith(" threw java.io.IOException: disk gone"), failure.getMessage());
                        assertTrue(failure.getCause() instanceof IOException);
                    }
                }),
                test("a factory returning null fails", () -> {
                    try (var container = Container.create(binder -> binder.bind(Repo.class).to(() -> null))) {
                        var failure = assertThrows(ResolutionException.class, () -> container.get(Repo.class));
                        assertTrue(failure.getMessage().endsWith(" returned null"), failure.getMessage());
                    }
                }),
                test("a resolver refuses undeclared dependencies", () -> {
                    try (var container = Container.create(binder -> {
                        binder.bind(Repo.class).to(Repo::new);
                        binder.bind(Clock.class).to(Clock::new);
                        binder.bind(Service.class).to(Dependencies.of(Repo.class),
                                r -> new Service(r.get(Repo.class), r.get(Clock.class)));
                    })) {
                        var failure = assertThrows(ResolutionException.class, () -> container.get(Service.class));
                        assertTrue(failure.getMessage().contains("asked for spola.di.BindingTests.Clock, which it does not"
                                + " declare; declared: [spola.di.BindingTests.Repo]"), failure.getMessage());
                    }
                }),
                test("a resolver is unusable after its factory returned", () -> {
                    var leaked = new AtomicReference<Resolver>();
                    try (var container = Container.create(binder -> {
                        binder.bind(Repo.class).to(Repo::new);
                        binder.bind(Clock.class).to(Dependencies.of(Repo.class), r -> {
                            leaked.set(r);
                            return new Clock();
                        });
                    })) {
                        container.get(Clock.class);
                        var failure = assertThrows(ResolutionException.class, () -> leaked.get().get(Repo.class));
                        assertTrue(failure.getMessage().contains("was used after its factory returned"), failure.getMessage());
                    }
                }),
                test("Container.get from inside a factory is refused", () -> {
                    var self = new AtomicReference<Container>();
                    try (var container = Container.create(binder -> {
                        binder.bind(Repo.class).to(Repo::new);
                        binder.bind(Clock.class).to(() -> {
                            self.get().get(Repo.class);
                            return new Clock();
                        });
                    })) {
                        self.set(container);
                        var failure = assertThrows(ResolutionException.class, () -> container.get(Clock.class));
                        assertTrue(failure.getMessage().contains("Container.get was called from the factory of"
                                + " spola.di.BindingTests.Clock"), failure.getMessage());
                    }
                }),
                test("a binding takes one target and one scope", () -> {
                    Container.create(binder -> {
                        var builder = binder.bind(Repo.class);
                        var binding = builder.to(Repo::new);
                        assertThrows(IllegalStateException.class, () -> builder.to(Repo::new));
                        assertThrows(IllegalStateException.class, () -> builder.toInstance(new Repo()));
                        binding.in(Scope.PROTOTYPE);
                        assertThrows(IllegalStateException.class, () -> binding.in(Scope.SINGLETON));
                    }).close();
                }),
                test("the binder refuses use after the container is created", () -> {
                    var kept = new AtomicReference<Binder>();
                    var builder = new AtomicReference<BindingBuilder<Repo>>();
                    Container.create(binder -> {
                        kept.set(binder);
                        builder.set(binder.bind(Repo.class));
                        builder.get().to(Repo::new);
                    }).close();
                    assertThrows(IllegalStateException.class, () -> kept.get().bind(Clock.class));
                    assertThrows(IllegalStateException.class, () -> kept.get().install(binder -> { }));
                    assertThrows(IllegalStateException.class, () -> builder.get().toInstance(new Repo()));
                }));
    }
}
