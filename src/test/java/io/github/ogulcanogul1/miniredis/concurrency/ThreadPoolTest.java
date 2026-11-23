package io.github.ogulcanogul1.miniredis.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThreadPoolTest {

    @Test
    void runsTasksAndGracefulShutdownDrainsQueue() throws Exception {
        AtomicInteger completed = new AtomicInteger();
        try (ThreadPool pool = new ThreadPool(4, 32, "test-worker")) {
            for (int i = 0; i < 60; i++) {
                pool.execute(() -> {
                    assertThat(Thread.currentThread().getName()).startsWith("test-worker-");
                    completed.incrementAndGet();
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(completed.get()).isEqualTo(60);
            assertThatThrownBy(() -> pool.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
        }
    }

    @Test
    void taskExceptionCallsErrorHandlerAndKeepsWorkerAlive() throws Exception {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (ThreadPool pool =
                new ThreadPool(1, 8, new NamedThreadFactory("err-worker"), RejectionPolicy.BLOCK, errors::add)) {
            pool.execute(() -> {
                throw new IllegalStateException("boom");
            });
            CountDownLatch nextRan = new CountDownLatch(1);
            pool.execute(nextRan::countDown);
            assertThat(nextRan.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("boom");
        }
    }

    @Test
    void abortAndCallerRunsPoliciesBehaveAsConfigured() throws Exception {
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);

        try (ThreadPool abortPool =
                new ThreadPool(1, 1, new NamedThreadFactory("abort"), RejectionPolicy.ABORT, t -> {})) {
            abortPool.execute(() -> {
                running.countDown();
                await(blocker);
            });
            assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
            abortPool.execute(() -> {}); // fills queue (capacity=1)
            assertThatThrownBy(() -> abortPool.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
            blocker.countDown();
        }

        CountDownLatch callerBlocker = new CountDownLatch(1);
        CountDownLatch callerStarted = new CountDownLatch(1);
        try (ThreadPool callerPool =
                new ThreadPool(1, 1, new NamedThreadFactory("cr"), RejectionPolicy.CALLER_RUNS, t -> {})) {
            callerPool.execute(() -> {
                callerStarted.countDown();
                await(callerBlocker);
            });
            assertThat(callerStarted.await(2, TimeUnit.SECONDS)).isTrue();
            callerPool.execute(() -> {}); // fills queue
            String callerThread = Thread.currentThread().getName();
            AtomicInteger ranOnCaller = new AtomicInteger();
            callerPool.execute(() -> {
                if (Thread.currentThread().getName().equals(callerThread)) {
                    ranOnCaller.incrementAndGet();
                }
            });
            assertThat(ranOnCaller.get()).isEqualTo(1);
            callerBlocker.countDown();
        }
    }

    @Test
    void shutdownNowReturnsDroppedTasks() throws Exception {
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ThreadPool pool = new ThreadPool(1, 4, "now");
        pool.execute(() -> {
            started.countDown();
            try {
                blocker.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        pool.execute(() -> {});
        pool.execute(() -> {});

        List<Runnable> dropped = pool.shutdownNow();
        assertThat(dropped).hasSize(2);
        assertThat(pool.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
