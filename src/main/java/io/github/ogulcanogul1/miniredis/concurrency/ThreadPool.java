package io.github.ogulcanogul1.miniredis.concurrency;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fixed-size worker pool backed by {@link BoundedBlockingQueue}. Stays independent of logging and
 * server layers (PLAN §2.3); task exceptions are delivered to the configured {@code errorHandler}
 * without killing the worker thread (K12).
 */
public final class ThreadPool implements AutoCloseable {

    private final BoundedBlockingQueue<Runnable> queue;
    private final RejectionPolicy rejectionPolicy;
    private final Consumer<Throwable> errorHandler;
    private final Thread[] workers;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger remainingWorkers;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private volatile boolean immediateStop;

    public ThreadPool(int workerCount, int queueCapacity, String threadPrefix) {
        this(workerCount, queueCapacity, new NamedThreadFactory(threadPrefix), RejectionPolicy.BLOCK, t -> {});
    }

    public ThreadPool(
            int workerCount,
            int queueCapacity,
            ThreadFactory threadFactory,
            RejectionPolicy rejectionPolicy,
            Consumer<Throwable> errorHandler) {
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be >= 1, got " + workerCount);
        }
        Objects.requireNonNull(threadFactory, "threadFactory");
        this.rejectionPolicy = Objects.requireNonNull(rejectionPolicy, "rejectionPolicy");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.queue = new BoundedBlockingQueue<>(queueCapacity);
        this.workers = new Thread[workerCount];
        this.remainingWorkers = new AtomicInteger(workerCount);
        for (int i = 0; i < workerCount; i++) {
            Thread worker = threadFactory.newThread(this::workerLoop);
            if (worker == null) {
                throw new IllegalStateException("threadFactory returned null");
            }
            this.workers[i] = worker;
            worker.start();
        }
    }

    /**
     * Submits {@code task} for execution according to {@link RejectionPolicy}.
     *
     * @throws RejectedExecutionException if the pool is shut down or {@link RejectionPolicy#ABORT}
     *     is triggered
     */
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (shutdown.get()) {
            throw new RejectedExecutionException("thread pool is shut down");
        }
        switch (rejectionPolicy) {
            case BLOCK -> {
                try {
                    queue.put(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("interrupted while submitting task", e);
                } catch (IllegalStateException e) {
                    throw new RejectedExecutionException("thread pool is shut down", e);
                }
            }
            case ABORT -> {
                if (!queue.offer(task)) {
                    throw new RejectedExecutionException("task queue is full (capacity=" + queue.capacity() + ")");
                }
            }
            case CALLER_RUNS -> {
                if (!queue.offer(task)) {
                    if (shutdown.get()) {
                        throw new RejectedExecutionException("thread pool is shut down");
                    }
                    runSafely(task);
                }
            }
        }
    }

    /** Initiates an orderly shutdown: queued tasks still run, new tasks are rejected. */
    public void shutdown() {
        shutdown.set(true);
        queue.close();
    }

    /**
     * Stops the pool immediately: rejects new tasks, discards buffered unstarted tasks, and
     * interrupts worker threads.
     */
    public List<Runnable> shutdownNow() {
        shutdown.set(true);
        immediateStop = true;
        queue.close();
        List<Runnable> unstarted = queue.drainAll();
        for (Thread worker : workers) {
            worker.interrupt();
        }
        return unstarted;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        return terminated.await(timeout, unit);
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public boolean isTerminated() {
        return terminated.getCount() == 0;
    }

    public int queuedTasks() {
        return queue.size();
    }

    public int workerCount() {
        return workers.length;
    }

    @Override
    public void close() {
        boolean interrupted = false;
        if (!isTerminated()) {
            shutdown();
            boolean done = false;
            while (!done) {
                try {
                    done = awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException e) {
                    if (!interrupted) {
                        shutdownNow();
                        interrupted = true;
                    }
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerLoop() {
        try {
            while (!immediateStop) {
                Runnable task;
                try {
                    task = queue.take();
                } catch (InterruptedException e) {
                    if (immediateStop) {
                        break;
                    }
                    // Normal shutdown lets workers drain remaining queued tasks even if interrupted once.
                    continue;
                }
                if (task == null) {
                    break;
                }
                runSafely(task);
            }
        } finally {
            if (remainingWorkers.decrementAndGet() == 0) {
                terminated.countDown();
            }
        }
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            reportError(t);
            if (t instanceof Error err) {
                throw err;
            }
        }
    }

    private void reportError(Throwable t) {
        try {
            errorHandler.accept(t);
        } catch (RuntimeException ignored) {
            // An error handler failure must not take down the worker loop.
        }
    }
}
