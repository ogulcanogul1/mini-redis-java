package io.github.ogulcanogul1.miniredis.concurrency;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ThreadFactory} that assigns predictable names ({@code prefix-1}, {@code prefix-2}, ...),
 * daemon status, and an {@link Thread.UncaughtExceptionHandler} (K12).
 */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean daemon;
    private final Thread.UncaughtExceptionHandler uncaughtHandler;
    private final AtomicLong nextId = new AtomicLong(1);

    public NamedThreadFactory(String prefix) {
        this(prefix, false, (t, e) -> {});
    }

    public NamedThreadFactory(String prefix, boolean daemon, Thread.UncaughtExceptionHandler uncaughtHandler) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        this.prefix = prefix;
        this.daemon = daemon;
        this.uncaughtHandler = Objects.requireNonNull(uncaughtHandler, "uncaughtHandler");
    }

    @Override
    public Thread newThread(Runnable task) {
        Objects.requireNonNull(task, "task");
        Thread thread = new Thread(task, prefix + "-" + nextId.getAndIncrement());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler(uncaughtHandler);
        return thread;
    }
}
