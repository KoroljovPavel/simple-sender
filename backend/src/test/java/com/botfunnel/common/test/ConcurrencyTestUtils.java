package com.botfunnel.common.test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

// D10: Virtual-thread concurrency primitive for race tests. Replaces the prior
// parallel(N).runOn(boundedElastic()) Reactor pattern with a CountDownLatch barrier so N tasks
// fire simultaneously after all of them have parked. Defensive try/finally on ready.countDown()
// ensures a thread that throws before reaching the barrier (e.g. interrupted while submitting)
// cannot hang the caller on ready.await().
public final class ConcurrencyTestUtils {

    private ConcurrencyTestUtils() {
    }

    public static <T> List<T> parallelInvoke(int n, Callable<T> task) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be > 0, got " + n);
        }
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<T>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        // Announce we're parked at the barrier. Defensive: countDown also lives in
                        // the finally so any exception en route here still releases the caller.
                        ready.countDown();
                        start.await();
                        return task.call();
                    } finally {
                        ready.countDown();
                    }
                }));
            }
            try {
                ready.await();
                start.countDown();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while waiting for parallel tasks to park", ex);
            }
            List<T> results = new ArrayList<>(n);
            for (Future<T> f : futures) {
                try {
                    results.add(f.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted while collecting parallel result", ex);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    throw new RuntimeException(cause);
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
