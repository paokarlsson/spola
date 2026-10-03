package spola.validation;

import static spola.test.Assertions.assertEquals;
import static spola.test.Tests.suite;
import static spola.test.Tests.test;
import static spola.validation.Constraints.notBlank;
import static spola.validation.Constraints.notNull;
import static spola.validation.Constraints.range;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import spola.test.Suite;

/** Criterion 11: one shared validator, 200 virtual threads, each gets exactly its own violations. */
final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    record Ticket(String owner, int seat, List<String> tags) {
    }

    static final Validator<Ticket> TICKET = Validator.<Ticket>builder()
            .field("owner", Ticket::owner, notBlank())
            .field("seat", Ticket::seat, range(0, 99))
            .each("tags", Ticket::tags, notNull())
            .build();

    /** Thread {@code i}'s ticket: seat {@code i} (invalid from 100), a null tag at {@code i % 7}. */
    static Ticket ticket(int i) {
        var tags = new ArrayList<String>();
        for (int k = 0; k <= i % 7; k++) {
            tags.add(k == i % 7 && i % 2 == 0 ? null : "t" + k);
        }
        return new Ticket(i % 3 == 0 ? " " : "owner" + i, i, tags);
    }

    static List<Violation> expected(int i) {
        var out = new ArrayList<Violation>();
        if (i % 3 == 0) {
            out.add(new Violation("$.owner", "notBlank", "must not be blank"));
        }
        if (i >= 100) {
            out.add(new Violation("$.seat", "range", "must be between 0 and 99"));
        }
        if (i % 2 == 0) {
            out.add(new Violation("$.tags[" + (i % 7) + "]", "notNull", "must not be null"));
        }
        return out;
    }

    static Suite tests() {
        return suite("threads",
                test("200 virtual threads share a static final validator and each gets its own violations", () -> {
                    int threads = 200;
                    var start = new CountDownLatch(1);
                    var failures = ConcurrentHashMap.<String>newKeySet();
                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int t = 0; t < threads; t++) {
                            int i = t;
                            executor.submit(() -> {
                                start.await();
                                for (int round = 0; round < 200; round++) {
                                    var expected = expected(i);
                                    var actual = switch (TICKET.check(ticket(i))) {
                                        case Validated.Valid<Ticket> v -> List.<Violation>of();
                                        case Validated.Invalid<Ticket> invalid -> invalid.violations();
                                    };
                                    if (!expected.equals(actual)) {
                                        failures.add("thread " + i + ": expected " + expected + " but got " + actual);
                                    }
                                    Thread.yield();
                                }
                                return null;
                            });
                        }
                        start.countDown();
                    }
                    assertEquals(List.of(), List.copyOf(failures));
                    assertEquals(List.of(new Violation("$.owner", "notBlank", "must not be blank"),
                            new Violation("$.seat", "range", "must be between 0 and 99"),
                            new Violation("$.tags[3]", "notNull", "must not be null")), expected(150));
                    assertEquals(Arrays.asList("t0", "t1", "t2", null), ticket(150).tags());
                }));
    }
}
