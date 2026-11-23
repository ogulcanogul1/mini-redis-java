package io.github.ogulcanogul1.miniredis.concurrency;

/** Policy applied by {@link ThreadPool#execute(Runnable)} when the task queue is full. */
public enum RejectionPolicy {
    /** Block the caller until queue space frees up (applies backpressure). */
    BLOCK,
    /** Throw {@link java.util.concurrent.RejectedExecutionException} immediately. */
    ABORT,
    /** Run the task synchronously on the submitting thread. */
    CALLER_RUNS
}
