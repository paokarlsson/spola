package spola.di;

import static spola.test.Assertions.assertEquals;
import static spola.test.Assertions.assertThrows;
import static spola.test.Assertions.assertTrue;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import spola.test.Suite;

/** Wiring errors are found by {@code Container.create}, before any factory runs. */
final class ValidationTests {

    private ValidationTests() {
    }

    record A(B b) {
    }

    record B(C c) {
    }

    record C(A a) {
    }

    record OrderRepo() {
    }

    record OrderService(OrderRepo repo) {
    }

    record Request() {
    }

    record Handler(Request request) {
    }

    record Wrapper(Handler handler) {
    }

    enum Region { EU, US }

    /** The line this is called from. */
    static int line() {
        return StackWalker.getInstance().walk(frames -> frames.skip(1).findFirst().orElseThrow().getLineNumber());
    }

    static Suite tests() {
        return suite("validation",
                test("a cycle A -> B -> C -> A fails create with the whole chain", () -> {
                    var created = new AtomicInteger();
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        binder.bind(A.class).to(B.class, b -> {
                            created.incrementAndGet();
                            return new A(b);
                        });
                        binder.bind(B.class).to(C.class, c -> {
                            created.incrementAndGet();
                            return new B(c);
                        });
                        binder.bind(C.class).to(A.class, a -> {
                            created.incrementAndGet();
                            return new C(a);
                        });
                    }));
                    assertEquals(1, failure.problems().size());
                    var problem = failure.problems().getFirst();
                    assertTrue(problem.startsWith("dependency cycle: spola.di.ValidationTests.A -> spola.di.ValidationTests.B"
                            + " -> spola.di.ValidationTests.C -> spola.di.ValidationTests.A ("), problem);
                    assertTrue(failure.getMessage().contains("A -> spola.di.ValidationTests.B -> spola.di.ValidationTests.C"
                            + " -> spola.di.ValidationTests.A"), failure.getMessage());
                    assertEquals(0, created.get(), "no factory may run");
                }),
                test("a cycle through the resolver form and through eager singletons is found too", () -> {
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        binder.bind(A.class).to(Dependencies.of(B.class), r -> new A(r.get(B.class))).in(Scope.EAGER_SINGLETON);
                        binder.bind(B.class).to(Dependencies.of(A.class), r -> new B(null)).in(Scope.PROTOTYPE);
                    }));
                    assertEquals(List.of("dependency cycle: spola.di.ValidationTests.A -> spola.di.ValidationTests.B"
                            + " -> spola.di.ValidationTests.A"), failure.problems().stream()
                            .map(p -> p.substring(0, p.indexOf(" ("))).toList());
                }),
                test("a component depending on itself is a cycle", () -> {
                    var failure = assertThrows(WiringException.class, () -> Container.create(
                            binder -> binder.bind(A.class).to(A.class, a -> new A(null))));
                    assertTrue(failure.problems().getFirst().startsWith(
                            "dependency cycle: spola.di.ValidationTests.A -> spola.di.ValidationTests.A ("));
                }),
                test("a missing dependency fails create, naming who misses what and where it was bound", () -> {
                    var created = new AtomicInteger();
                    var lines = new int[1];
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        lines[0] = line(); binder.bind(OrderService.class).to(OrderRepo.class, repo -> {
                            created.incrementAndGet();
                            return new OrderService(repo);
                        });
                    }));
                    assertEquals(1, failure.problems().size());
                    var problem = failure.problems().getFirst();
                    // the binding's location is the module lambda's frame: Class.method(File:line)
                    assertTrue(problem.startsWith("spola.di.ValidationTests.OrderService (bound at"
                            + " spola.di.ValidationTests.lambda$"), problem);
                    assertTrue(problem.endsWith("(ValidationTests.java:" + lines[0] + "))"
                            + " depends on spola.di.ValidationTests.OrderRepo, which is not bound"), problem);
                    assertEquals("the container has 1 wiring problem:\n  - " + failure.problems().getFirst(),
                            failure.getMessage());
                    assertEquals(0, created.get(), "no factory may run");
                }),
                test("a missing qualified dependency lists the bindings of that type", () -> {
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        binder.bind(Key.of(OrderRepo.class, Region.US)).to(OrderRepo::new);
                        binder.bind(OrderService.class).to(Key.of(OrderRepo.class, Region.EU), OrderService::new);
                    }));
                    var problem = failure.problems().getFirst();
                    assertTrue(problem.endsWith(" depends on spola.di.ValidationTests.OrderRepo[EU], which is not bound"
                            + " (bound for that type: [spola.di.ValidationTests.OrderRepo[US]])"), problem);
                }),
                test("a key bound twice fails create with both places", () -> {
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        binder.bind(OrderRepo.class).to(OrderRepo::new);
                        binder.bind(OrderRepo.class).toInstance(new OrderRepo());
                    }));
                    var problem = failure.problems().getFirst();
                    assertTrue(problem.startsWith("spola.di.ValidationTests.OrderRepo is bound 2 times: at spola.di."), problem);
                    assertEquals(2, problem.split("ValidationTests.java:").length - 1, problem);
                }),
                test("a binding without a target fails create", () -> {
                    var failure = assertThrows(WiringException.class,
                            () -> Container.create(binder -> binder.bind(OrderRepo.class)));
                    assertTrue(failure.problems().getFirst().endsWith(" has no target; call to(...) or toInstance(...)"));
                }),
                test("a singleton may not capture a call-scoped component, also through a prototype", () -> {
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        binder.bind(Request.class).to(Request::new).in(Scope.CALL);
                        binder.bind(Handler.class).to(Request.class, Handler::new).in(Scope.PROTOTYPE);
                        binder.bind(Wrapper.class).to(Handler.class, Wrapper::new);
                    }));
                    assertEquals(1, failure.problems().size());
                    var problem = failure.problems().getFirst();
                    assertTrue(problem.startsWith("singleton spola.di.ValidationTests.Wrapper (bound at "), problem);
                    assertTrue(problem.contains(" depends on call-scoped spola.di.ValidationTests.Request"
                            + " (spola.di.ValidationTests.Wrapper -> spola.di.ValidationTests.Handler"
                            + " -> spola.di.ValidationTests.Request)"), problem);
                }),
                test("call- and prototype-scoped components may depend on call-scoped ones", () -> {
                    Container.create(binder -> {
                        binder.bind(Request.class).to(Request::new).in(Scope.CALL);
                        binder.bind(Handler.class).to(Request.class, Handler::new).in(Scope.PROTOTYPE);
                        binder.bind(Wrapper.class).to(Handler.class, Wrapper::new).in(Scope.CALL);
                    }).close();
                }),
                test("all problems are reported together", () -> {
                    var failure = assertThrows(WiringException.class, () -> Container.create(binder -> {
                        binder.bind(OrderService.class).to(OrderRepo.class, OrderService::new);
                        binder.bind(A.class).to(B.class, A::new);
                        binder.bind(B.class).to(C.class, B::new);
                        binder.bind(C.class).to(A.class, C::new);
                        binder.bind(Request.class);
                    }));
                    assertEquals(3, failure.problems().size(), failure.getMessage());
                    assertTrue(failure.getMessage().startsWith("the container has 3 wiring problems:\n  - "));
                    assertTrue(failure.problems().get(0).endsWith(" has no target; call to(...) or toInstance(...)"));
                    assertTrue(failure.problems().get(1).contains("depends on spola.di.ValidationTests.OrderRepo"));
                    assertTrue(failure.problems().get(2).startsWith("dependency cycle: "));
                }));
    }
}
