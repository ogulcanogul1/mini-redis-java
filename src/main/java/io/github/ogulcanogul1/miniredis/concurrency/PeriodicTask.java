package io.github.ogulcanogul1.miniredis.concurrency;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Wraps periodic {@link java.util.concurrent.ScheduledExecutorService} tasks so an uncaught
 * {@link RuntimeException} is routed to {@code errorHandler} without cancelling future executions
 * (K12).
 */
public final class PeriodicTask {

    private PeriodicTask() {}

    /**
     * Returns a {@link Runnable} that runs {@code task}, forwarding any {@link RuntimeException} to
     * {@code errorHandler} so {@code scheduleAtFixedRate} keeps running.
     */
    public static Runnable wrap(Runnable task, Consumer<Throwable> errorHandler) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(errorHandler, "errorHandler");
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                errorHandler.accept(e);
            }
        };
    }
}
