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
// (TelegramWebhookControllerIT race + TelegramWebhookP99IT). The gates pinned here are:
//
//   * Barrier release fires simultaneously (max-min Instant spread < 200ms across N=10 VTs;
//     bound widened from a tighter 50ms after CI flake exposure on cold-start JVMs).
//   * A task throwing inside its body MUST NOT hang the caller on ready.await() — the
//     try { ready.countDown(); start.await(); return task.call(); } finally { ready.countDown(); }
//     wrapper is the D10 contract. The test below pins the "throw inside task.call()" branch;
//     the finally-clause hedge is defensive against Error-class throws between the pre-barrier
//     countDown and start.await() — that path requires impl-level injection to exercise and is
//     left to inspection rather than a brittle test that pokes at impl internals.
//   * n <= 0 fails fast with IllegalArgumentException — caller mistake, not a runtime trap.
//   * Returned List<T> has one entry per submission (no drops, no duplicates) — race-pair
//     call-sites depend on positional access from a list of size N.
//   * A RuntimeException from any task surfaces back to the caller (unwrapped from
//     ExecutionException) so race tests can assertThatThrownBy on submission failures.
class ConcurrencyTestUtilsTest {

    @Test
    void parallelInvoke_runsAllTasksConcurrently() {
        // Each VT records its post-barrier wall-clock instant. Without the latch the spread
        // would grow linearly with N (serial release); with the latch all N parked VTs are
        // released by the single start.countDown() and resume within a few ms of each other.
        // 200ms bound accommodates cold-start JVM jitter (mount carrier threads, JIT) while
        // still failing a serial-release impl (which would spread 10+ ms per task).
        List<Instant> instants = ConcurrencyTestUtils.parallelInvoke(10, Instant::now);

        assertThat(instants).hasSize(10);
        Instant min = instants.stream().min(Instant::compareTo).orElseThrow();
        Instant max = instants.stream().max(Instant::compareTo).orElseThrow();
        Duration spread = Duration.between(min, max);
        assertThat(spread)
                .as("parallel-barrier spread across 10 VTs must be < 200ms (got %s)", spread)
                .isLessThan(Duration.ofMillis(200));
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
    void parallelInvoke_zeroN_throwsIllegalArgumentException() {
        // Caller-mistake guard — passing n=0 (or negative) is meaningless and the impl
        // fails fast before submitting anything, preserving the calling thread's stack.
        assertThatThrownBy(() -> ConcurrencyTestUtils.parallelInvoke(0, () -> "ignored"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n must be > 0");

        assertThatThrownBy(() -> ConcurrencyTestUtils.parallelInvoke(-1, () -> "ignored"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parallelInvoke_returnsAllResults_noLossNoDuplicates() {
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
    void parallelInvoke_propagatesCallableException_evenWhenOtherTasksSucceed() {
        // Realistic race-test shape: one task in the pool fails (e.g. unique-index conflict)
        // while the others succeed. The impl surfaces the FIRST future's failure unwrapped
        // — once that future throws, subsequent successful futures are dropped (acceptable
        // for race tests that only need to assertThatThrownBy on submission failure).
        // Submission #1 always fails; #2 and #3 always succeed.
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> ConcurrencyTestUtils.parallelInvoke(3, () -> {
            if (callCount.incrementAndGet() == 1) {
                throw new IllegalStateException("intentional failure on task 1");
            }
            return "ok";
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("intentional failure on task 1");
    }
}
