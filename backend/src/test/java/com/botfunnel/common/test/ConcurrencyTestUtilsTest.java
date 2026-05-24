package com.botfunnel.common.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Contract tests for the D10 VT-barrier primitive consumed by Wave 2 race tests
// (BotConnectRaceIT) and Wave 9 webhook idempotency / P99 tests
// (TelegramWebhookControllerIT race + TelegramWebhookP99IT). The four gates pinned here are:
//
//   * Barrier release fires simultaneously (max-min Instant spread < 50ms across N=10 VTs).
//   * A task throwing inside its body MUST NOT hang the caller on ready.await() — the
//     try { ready.countDown(); start.await(); return task.call(); } finally { ready.countDown(); }
//     wrapper is the D10 contract; this test bounds wall-clock so the bug-shape fails fast.
//   * Returned List<T> has one entry per submission (no drops, no duplicates) — race-pair
//     call-sites depend on positional access from a list of size N.
//   * A RuntimeException from task.call() surfaces back to the caller (unwrapped from
//     ExecutionException) so race tests can assertThatThrownBy on submission failures.
class ConcurrencyTestUtilsTest {

    @Test
    void parallelInvoke_runsAllTasksConcurrently() {
        // Each VT records its post-barrier wall-clock instant. Without the latch the spread
        // would grow linearly with N (serial release); with the latch all N parked VTs are
        // released by the single start.countDown() and resume within a few ms of each other.
        List<Instant> instants = ConcurrencyTestUtils.parallelInvoke(10, Instant::now);

        assertThat(instants).hasSize(10);
        Instant min = instants.stream().min(Instant::compareTo).orElseThrow();
        Instant max = instants.stream().max(Instant::compareTo).orElseThrow();
        Duration spread = Duration.between(min, max);
        assertThat(spread)
                .as("parallel-barrier spread across 10 VTs must be < 50ms (got %s)", spread)
                .isLessThan(Duration.ofMillis(50));
    }

    @Test
    void parallelInvoke_taskThrowingBeforeBarrier_doesNotHang() {
        // D10 contract: try { ready.countDown(); start.await(); return task.call(); }
        //               finally { ready.countDown(); }
        // The unconditional countDown means a task whose body throws cannot leave ready > 0
        // and hang the parent on ready.await(). Bound elapsed wall-clock to prove the
        // contract — the bug-shape (countDown only after a successful task.call()) would
        // block the parent indefinitely on the throwing task's missed decrement.
        AtomicInteger callCount = new AtomicInteger();
        long startNs = System.nanoTime();

        assertThatThrownBy(() -> ConcurrencyTestUtils.parallelInvoke(5, () -> {
            if (callCount.incrementAndGet() == 1) {
                throw new IllegalStateException("first task explodes inside its body");
            }
            return "ok";
        })).isInstanceOf(IllegalStateException.class);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNs);
        assertThat(elapsed)
                .as("call must not hang on ready.await() when a task body throws")
                .isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void parallelInvoke_returnsResultsInSubmissionOrder() {
        // Impl iterates futures in submission order: results.get(i) corresponds to the
        // i-th submitted invocation. With a single shared Callable, per-submission identity
        // cannot be observed from inside the task body — so this test exercises the
        // underlying no-loss invariant that race-pair callers depend on: N submissions
        // contribute N distinct results, no drops, no duplicates, list length == N.
        AtomicInteger counter = new AtomicInteger();
        int n = 20;

        List<Integer> results = ConcurrencyTestUtils.parallelInvoke(n, counter::incrementAndGet);

        assertThat(results).hasSize(n);
        assertThat(results).doesNotHaveDuplicates();
        assertThat(results).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(1, n).boxed().toList());
    }

    @Test
    void parallelInvoke_propagatesCallableException() {
        // Race-test sites (BotConnectRaceIT) need assertThatThrownBy on submission failures.
        // An IllegalStateException from task.call() MUST surface back to the caller — the
        // impl unwraps the ExecutionException wrapper and rethrows the cause unchanged.
        assertThatThrownBy(() -> ConcurrencyTestUtils.parallelInvoke(3, () -> {
            throw new IllegalStateException("intentional failure");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("intentional failure");
    }
}
